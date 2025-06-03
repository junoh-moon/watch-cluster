package com.watchcluster.service

import com.watchcluster.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mu.KotlinLogging
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

class DeploymentUpdater(
    private val kubernetesClient: KubernetesClient,
    private val webhookService: WebhookService
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    suspend fun updateDeployment(namespace: String, name: String, newImage: String, updateResult: ImageUpdateResult? = null, updateStrategy: UpdateStrategy? = null) {
        try {
            logger.info { "Updating deployment $namespace/$name with new image: $newImage" }
            
            scope.launch {
                webhookService.sendWebhook(WebhookEvent(
                    eventType = WebhookEventType.IMAGE_ROLLOUT_STARTED,
                    timestamp = java.time.Instant.now().toString(),
                    deployment = DeploymentInfo(namespace, name, newImage),
                    details = mapOf("previousImage" to getCurrentImage(namespace, name))
                ))
            }
            
            val deploymentResource = kubernetesClient.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(name)
            
            val deployment = deploymentResource.get()
                ?: throw IllegalStateException("Deployment $namespace/$name not found")
            
            val containers = deployment.spec.template.spec.containers
            if (containers.isEmpty()) {
                throw IllegalStateException("No containers found in deployment $namespace/$name")
            }
            
            // Check if this is a latest strategy update and handle specially
            if (updateStrategy is UpdateStrategy.Latest && updateResult?.newDigest != null) {
                // Handle latest tag with special pull policy logic
                // This method internally handles the rollout wait
                handleLatestTagUpdate(deploymentResource, deployment, newImage, updateResult)
                
                logger.info { "Successfully updated deployment $namespace/$name to image: $newImage (latest strategy)" }
                
                // Add update annotation after successful rollout
                addUpdateAnnotation(deploymentResource, newImage, updateResult)
                
                // Verify the digest after rollout
                verifyDeployedDigest(namespace, name, updateResult.newDigest)
            } else {
                // Normal update flow for non-latest strategy
                containers[0].image = newImage
                deploymentResource.patch(deployment)
                
                logger.info { "Successfully updated deployment $namespace/$name to image: $newImage" }
                
                // Add update annotation
                addUpdateAnnotation(deploymentResource, newImage, updateResult)
                
                // Wait for rollout to complete
                waitForRollout(deploymentResource, namespace, name, newImage)
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to update deployment $namespace/$name" }
            
            scope.launch {
                webhookService.sendWebhook(WebhookEvent(
                    eventType = WebhookEventType.IMAGE_ROLLOUT_FAILED,
                    timestamp = java.time.Instant.now().toString(),
                    deployment = DeploymentInfo(namespace, name, newImage),
                    details = mapOf("error" to (e.message ?: "Unknown error"))
                ))
            }
            
            throw e
        }
    }
    
    private fun addUpdateAnnotation(deploymentResource: RollableScalableResource<Deployment>, newImage: String, updateResult: ImageUpdateResult?) {
        try {
            val deployment = deploymentResource.get()
            val annotations = deployment.metadata.annotations ?: mutableMapOf()
            
            // Use ISO 8601 format with local timezone
            val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            annotations["watch-cluster.io/last-update"] = timestamp
            annotations["watch-cluster.io/last-update-image"] = newImage
            
            // Add digest information if available
            updateResult?.currentDigest?.let {
                annotations["watch-cluster.io/last-update-from-digest"] = it
            }
            updateResult?.newDigest?.let {
                annotations["watch-cluster.io/last-update-to-digest"] = it
            }
            
            deployment.metadata.annotations = annotations
            deploymentResource.patch(deployment)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to add update annotation" }
        }
    }
    
    private fun getCurrentImage(namespace: String, name: String): String {
        return try {
            val deployment = kubernetesClient.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(name)
                .get()
            deployment?.spec?.template?.spec?.containers?.get(0)?.image ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
    
    private fun waitForRollout(
        deploymentResource: RollableScalableResource<Deployment>,
        namespace: String,
        name: String,
        newImage: String
    ) {
        try {
            logger.info { "Waiting for rollout to complete..." }
            val timeout = 300000L
            val startTime = System.currentTimeMillis()
            
            while (System.currentTimeMillis() - startTime < timeout) {
                val deployment = deploymentResource.get()
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
                        
                        scope.launch {
                            webhookService.sendWebhook(WebhookEvent(
                                eventType = WebhookEventType.IMAGE_ROLLOUT_COMPLETED,
                                timestamp = java.time.Instant.now().toString(),
                                deployment = DeploymentInfo(namespace, name, newImage),
                                details = mapOf("rolloutDuration" to "${System.currentTimeMillis() - startTime}ms")
                            ))
                        }
                        
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
                
                Thread.sleep(5000)
            }
            
            logger.warn { "Rollout timeout after ${timeout/1000} seconds" }
        } catch (e: Exception) {
            logger.warn(e) { "Error waiting for rollout" }
        }
    }
    
    private fun handleLatestTagUpdate(
        deploymentResource: RollableScalableResource<Deployment>,
        deployment: Deployment,
        newImage: String,
        updateResult: ImageUpdateResult
    ) {
        logger.info { "Handling latest tag update with special pull policy logic" }
        
        val container = deployment.spec.template.spec.containers[0]
        
        // Backup the original imagePullPolicy
        val originalPullPolicy = container.imagePullPolicy
        logger.info { "Backing up original imagePullPolicy: $originalPullPolicy" }
        
        try {
            // Step 1: Set imagePullPolicy to Always to force pull the latest image
            container.imagePullPolicy = "Always"
            container.image = newImage
            
            // Apply the patch with Always pull policy
            deploymentResource.patch(deployment)
            logger.info { "Applied deployment patch with imagePullPolicy=Always" }
            
            // Step 2: Trigger a rollout restart to force the pull
            logger.info { "Triggering rollout restart to force image pull" }
            deploymentResource.rolling().restart()
            
            // Step 3: Wait for rollout to complete
            waitForRollout(deploymentResource, deployment.metadata.namespace, deployment.metadata.name, newImage)
            
            // Step 4: Restore the original imagePullPolicy after rollout is complete
            if (originalPullPolicy != null && originalPullPolicy != "Always") {
                logger.info { "Restoring original imagePullPolicy: $originalPullPolicy" }
                val updatedDeployment = deploymentResource.get()
                    ?: throw IllegalStateException("Deployment not found after rollout")
                updatedDeployment.spec.template.spec.containers[0].imagePullPolicy = originalPullPolicy
                deploymentResource.patch(updatedDeployment)
                logger.info { "Successfully restored imagePullPolicy to: $originalPullPolicy" }
            }
            
        } catch (e: Exception) {
            logger.error(e) { "Error during latest tag update" }
            
            // Attempt to restore original pull policy on error
            try {
                if (originalPullPolicy != null && originalPullPolicy != "Always") {
                    logger.info { "Attempting to restore original imagePullPolicy after error" }
                    val currentDeployment = deploymentResource.get()
                    if (currentDeployment != null) {
                        currentDeployment.spec.template.spec.containers[0].imagePullPolicy = originalPullPolicy
                        deploymentResource.patch(currentDeployment)
                    }
                }
            } catch (restoreError: Exception) {
                logger.error(restoreError) { "Failed to restore original imagePullPolicy" }
            }
            
            throw e
        }
    }
    
    private fun verifyDeployedDigest(namespace: String, deploymentName: String, expectedDigest: String) {
        try {
            logger.info { "Verifying deployed image digest matches expected: $expectedDigest" }
            
            // Give pods time to fully start
            Thread.sleep(10000)
            
            val podList = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("app", deploymentName)
                .list()
            
            if (podList.items.isNotEmpty()) {
                val runningPods = podList.items.filter { pod ->
                    pod.status?.phase == "Running"
                }
                
                if (runningPods.isNotEmpty()) {
                    val pod = runningPods.first()
                    val containerStatus = pod.status?.containerStatuses?.firstOrNull()
                    val imageID = containerStatus?.imageID
                    
                    if (imageID != null && imageID.contains("@")) {
                        val actualDigest = imageID.substringAfter("@")
                        
                        if (actualDigest == expectedDigest) {
                            logger.info { "✓ Digest verification successful: deployed image matches expected digest" }
                        } else {
                            logger.warn { 
                                "⚠ Digest mismatch! Expected: $expectedDigest, Actual: $actualDigest"
                            }
                            
                            scope.launch {
                                webhookService.sendWebhook(WebhookEvent(
                                    eventType = WebhookEventType.IMAGE_ROLLOUT_COMPLETED,
                                    timestamp = java.time.Instant.now().toString(),
                                    deployment = DeploymentInfo(namespace, deploymentName, ""),
                                    details = mapOf(
                                        "warning" to "Digest mismatch after deployment",
                                        "expectedDigest" to expectedDigest,
                                        "actualDigest" to actualDigest
                                    )
                                ))
                            }
                        }
                    } else {
                        logger.warn { "Could not extract digest from pod imageID: $imageID" }
                    }
                } else {
                    logger.warn { "No running pods found for verification" }
                }
            } else {
                logger.warn { "No pods found for deployment $deploymentName" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error verifying deployed digest" }
        }
    }
}