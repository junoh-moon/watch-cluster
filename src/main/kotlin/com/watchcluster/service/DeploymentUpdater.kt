package com.watchcluster.service

import com.watchcluster.model.*
import com.watchcluster.util.ImageParser
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import mu.KotlinLogging
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

class DeploymentUpdater(
    private val kubernetesClient: KubernetesClient,
    private val webhookService: WebhookService
) {
    private val k8sThreadPool = Executors.newFixedThreadPool(
        maxOf(4, Runtime.getRuntime().availableProcessors())
    )
    private val k8sDispatcher = k8sThreadPool.asCoroutineDispatcher()

    suspend fun updateDeployment(namespace: String, name: String, newImage: String, updateResult: ImageUpdateResult? = null) {
        runCatching {
            logger.info { "Updating deployment $namespace/$name with new image: $newImage" }
            
            val previousImage = getCurrentImage(namespace, name)
            webhookService.sendWebhook(WebhookEvent(
                eventType = WebhookEventType.IMAGE_ROLLOUT_STARTED,
                timestamp = java.time.Instant.now().toString(),
                deployment = DeploymentInfo(namespace, name, newImage),
                details = mapOf("previousImage" to previousImage)
            ))
            
            val deploymentResource = kubernetesClient.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(name)
            
            val deployment = withContext(k8sDispatcher) {
                deploymentResource.get()
            } ?: throw IllegalStateException("Deployment $namespace/$name not found")
            
            val containers = deployment.spec.template.spec.containers
            if (containers.isEmpty()) {
                throw IllegalStateException("No containers found in deployment $namespace/$name")
            }
            
            // Get the first container's name (or find the appropriate container)
            val containerName = containers[0].name
            
            // digest가 있으면 명시적으로 붙여서 배포
            val imageToSet = updateResult
                ?.newDigest
                ?.takeIf { it.isNotBlank() }
                ?.let { ImageParser.addDigest(newImage, it) }
                ?: newImage
            
            // Prepare annotations for combined update
            val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val annotationMap = mutableMapOf<String, String>()
            annotationMap["watch-cluster.io/last-update"] = timestamp
            annotationMap["watch-cluster.io/change"] = "$previousImage -> $imageToSet"
            
            
            // Create combined patch JSON for both image and annotations
            val patchJson = buildCombinedPatch(containerName to imageToSet, annotationMap )
            
            withContext(k8sDispatcher) {
                deploymentResource.patch(patchJson)
            }?.let { logger.info { "Patch successful - deployment $namespace/$name updated with new image: $imageToSet" } }
			?: run {
                logger.error { "Patch returned null response for deployment $namespace/$name" }
                throw IllegalStateException("Patch operation returned null deployment")
			}
            
            waitForRollout(deploymentResource, namespace, name, imageToSet)
        }.onFailure { e ->
            logger.error(e) { "Failed to update deployment $namespace/$name" }
            
            webhookService.sendWebhook(WebhookEvent(
                eventType = WebhookEventType.IMAGE_ROLLOUT_FAILED,
                timestamp = java.time.Instant.now().toString(),
                deployment = DeploymentInfo(namespace, name, newImage),
                details = mapOf("error" to (e.message ?: "Unknown error"))
            ))
            
            throw e
        }
    }
    
    private fun buildCombinedPatch(containerImage: Pair<String, String>, annotations: Map<String, String>): String {
        val annotationsJson = annotations.entries.joinToString(",\n      ") { (key, value) ->
            "\"$key\": \"${value.replace("\"", "\\\"")}\""
        }

		val (containerName, imageToSet) = containerImage
        
        return """
        {
          "metadata": {
            "annotations": {
              $annotationsJson
            }
          },
          "spec": {
            "template": {
              "spec": {
                "containers": [
                  {
                    "name": "$containerName",
                    "image": "$imageToSet"
                  }
                ]
              }
            }
          }
        }
        """.trimIndent()
    }
    
    private suspend fun getCurrentImage(namespace: String, name: String): String {
        return runCatching {
            val deployment = withContext(k8sDispatcher) {
                kubernetesClient.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .withName(name)
                    .get()
            }
            deployment?.spec?.template?.spec?.containers?.get(0)?.image ?: "unknown"
        }.getOrElse {
            "unknown"
        }
    }
    
    private suspend fun waitForRollout(
        deploymentResource: RollableScalableResource<Deployment>,
        namespace: String,
        name: String,
        newImage: String
    ) {
        runCatching {
            logger.info { "Waiting for rollout to complete..." }
            val timeout = 300000L
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < timeout) {
                val deployment = withContext(k8sDispatcher) {
                    deploymentResource.get()
                }
                val status = deployment.status
                
                if (status != null) {
                    // Check if controller has observed the latest generation
                    val generationMatch = status.observedGeneration == deployment.metadata.generation
                    if (!generationMatch) {
                        logger.debug { "Waiting for controller to observe generation ${deployment.metadata.generation} (current: ${status.observedGeneration})" }
                        kotlinx.coroutines.delay(2000)
                        continue
                    }
                    
                    // Check deployment conditions
                    val conditions = status.conditions ?: emptyList()
                    val progressingCondition = conditions.find { it.type == "Progressing" }
                    val availableCondition = conditions.find { it.type == "Available" }
                    
                    val isProgressing = progressingCondition?.status == "True"
                    val isAvailable = availableCondition?.status == "True"
                    val isComplete = progressingCondition?.reason == "NewReplicaSetAvailable"
                    
                    // Check replica counts
                    val replicas = deployment.spec.replicas ?: 1
                    val updatedReplicas = status.updatedReplicas ?: 0
                    val readyReplicas = status.readyReplicas ?: 0
                    val availableReplicas = status.availableReplicas ?: 0
                    
                    // Check if all replicas are updated and ready
                    val replicasReady = updatedReplicas == replicas && 
                                       readyReplicas == replicas && 
                                       availableReplicas == replicas
                    
                    if (replicasReady && isAvailable && isComplete) {
                        // Verify actual pod images
                        val allPodsUpdated = verifyPodImages(deployment, namespace, newImage)
                        
                        if (allPodsUpdated) {
                            logger.info { "Rollout completed successfully - all pods running image: $newImage" }
                            
                            webhookService.sendWebhook(WebhookEvent(
                                eventType = WebhookEventType.IMAGE_ROLLOUT_COMPLETED,
                                timestamp = java.time.Instant.now().toString(),
                                deployment = DeploymentInfo(namespace, name, newImage),
                                details = mapOf("rolloutDuration" to "${System.currentTimeMillis() - startTime}ms")
                            ))
                            
                            return
                        } else {
                            logger.debug { "Waiting for all pods to update to new image" }
                        }
                    }
                    
                    logger.debug { 
                        listOf(
                            "Rollout progress - Generation: ${status.observedGeneration}/${deployment.metadata.generation}",
                            "Updated: $updatedReplicas/$replicas",
                            "Ready: $readyReplicas/$replicas", 
                            "Available: $availableReplicas/$replicas",
                            "Progressing: $isProgressing (${progressingCondition?.reason})",
                            "Available: ${availableCondition?.status}"
                        ).joinToString(", ")
                    }
                }
                
                kotlinx.coroutines.delay(5000)
            }
            
            logger.warn { "Rollout timeout after ${timeout/1000} seconds" }
        }.onFailure { e ->
            logger.warn(e) { "Error waiting for rollout" }
        }
    }
    
    private suspend fun verifyPodImages(deployment: Deployment, namespace: String, expectedImage: String): Boolean {
        return runCatching {
            val pods = withContext(k8sDispatcher) {
                kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withLabels(deployment.spec.selector.matchLabels)
                    .list()
                    .items
            }
            
            if (pods.isEmpty()) {
                logger.warn { "No pods found for deployment $namespace/${deployment.metadata.name}" }
                return false
            }
            
            val allPodsUpdated = pods.all { pod ->
                val podReady = pod.status?.conditions?.find { it.type == "Ready" }?.status == "True"
                val containerImages = pod.spec?.containers?.map { it.image } ?: emptyList()
                val hasCorrectImage = containerImages.any { image ->
                    image == expectedImage
                }
                
                if (!hasCorrectImage) {
                    logger.debug { "Pod ${pod.metadata.name} has images: $containerImages, expected: $expectedImage" }
                }
                
                podReady && hasCorrectImage
            }
            
            logger.debug { "Pod image verification: ${pods.size} pods checked, all updated: $allPodsUpdated" }
            allPodsUpdated
        }.getOrElse { e ->
            logger.error(e) { "Failed to verify pod images" }
            false
        }
    }
    
    fun shutdown() {
        k8sDispatcher.close()
        k8sThreadPool.shutdown()
    }
}
