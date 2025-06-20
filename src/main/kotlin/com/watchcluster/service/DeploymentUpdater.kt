package com.watchcluster.service

import com.watchcluster.model.*
import com.watchcluster.util.ImageParser
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import mu.KotlinLogging
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

class DeploymentUpdater(
    private val kubernetesClient: KubernetesClient,
    private val webhookService: WebhookService
) {
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
            
            val deployment = withContext(Dispatchers.IO) {
                deploymentResource.get()
            } ?: throw IllegalStateException("Deployment $namespace/$name not found")
            
            val containers = deployment.spec.template.spec.containers
            if (containers.isEmpty()) {
                throw IllegalStateException("No containers found in deployment $namespace/$name")
            }
            
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
            annotationMap["watch-cluster.io/last-update-image"] = imageToSet
            
            // Add digest information if available
            updateResult?.currentDigest?.let {
                annotationMap["watch-cluster.io/last-update-from-digest"] = it
            }
            updateResult?.newDigest?.let {
                annotationMap["watch-cluster.io/last-update-to-digest"] = it
            }
            
            // Create combined patch JSON for both image and annotations
            val patchJson = buildCombinedPatch(imageToSet, annotationMap)
            
            withContext(Dispatchers.IO) {
                deploymentResource.patch(patchJson)
            }
            
            logger.info { "Successfully updated deployment $namespace/$name to image: $imageToSet with annotations" }
            
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
    
    private fun buildCombinedPatch(imageToSet: String, annotations: Map<String, String>): String {
        val annotationsJson = annotations.entries.joinToString(",\n      ") { (key, value) ->
            "\"$key\": \"${value.replace("\"", "\\\"")}\""
        }
        
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
                    "image": "$imageToSet"  // 첫 번째 이미지만 업데이트
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
            val deployment = withContext(Dispatchers.IO) {
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
                val deployment = withContext(Dispatchers.IO) {
                    deploymentResource.get()
                }
                val status = deployment.status
                
                if (status != null) {
                    val replicas = deployment.spec.replicas ?: 1
                    val updatedReplicas = status.updatedReplicas ?: 0
                    val readyReplicas = status.readyReplicas ?: 0
                    val availableReplicas = status.availableReplicas ?: 0
                    
                    if (updatedReplicas == replicas && 
                        readyReplicas == replicas && 
                        availableReplicas == replicas) {
                        logger.info { "Rollout completed successfully" }
                        
                        webhookService.sendWebhook(WebhookEvent(
                            eventType = WebhookEventType.IMAGE_ROLLOUT_COMPLETED,
                            timestamp = java.time.Instant.now().toString(),
                            deployment = DeploymentInfo(namespace, name, newImage),
                            details = mapOf("rolloutDuration" to "${System.currentTimeMillis() - startTime}ms")
                        ))
                        
                        return
                    }
                    
                    logger.debug { 
                        listOf(
                            "Rollout progress - Updated: $updatedReplicas/$replicas",
                            "Ready: $readyReplicas/$replicas",
                            "Available: $availableReplicas/$replicas"
                        ).joinToString(", ")
                    }
                }
                
                kotlinx.coroutines.delay(5000)
            }
            
            logger.warn { "Rollout timeout after \\${timeout/1000} seconds" }
        }.onFailure { e ->
            logger.warn(e) { "Error waiting for rollout" }
        }
    }
}
