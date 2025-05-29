package com.watchcluster.service

import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

class DeploymentUpdater(
    private val kubernetesClient: KubernetesClient
) {
    suspend fun updateDeployment(namespace: String, name: String, newImage: String) {
        try {
            logger.info { "Updating deployment $namespace/$name with new image: $newImage" }
            
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
            
            containers[0].image = newImage
            
            deploymentResource.patch(deployment)
            
            logger.info { "Successfully updated deployment $namespace/$name to image: $newImage" }
            
            addUpdateAnnotation(deploymentResource, newImage)
            
            waitForRollout(deploymentResource)
            
        } catch (e: Exception) {
            logger.error(e) { "Failed to update deployment $namespace/$name" }
            throw e
        }
    }
    
    private fun addUpdateAnnotation(deploymentResource: RollableScalableResource<Deployment>, newImage: String) {
        try {
            val deployment = deploymentResource.get()
            val annotations = deployment.metadata.annotations ?: mutableMapOf()
            annotations["watch-cluster.io/last-update"] = System.currentTimeMillis().toString()
            annotations["watch-cluster.io/last-update-image"] = newImage
            deployment.metadata.annotations = annotations
            deploymentResource.patch(deployment)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to add update annotation" }
        }
    }
    
    private fun waitForRollout(deploymentResource: RollableScalableResource<Deployment>) {
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
                        return
                    }
                    
                    logger.debug { 
                        "Rollout progress - Updated: $updatedReplicas/$replicas, " +
                        "Ready: $readyReplicas/$replicas, Available: $availableReplicas/$replicas" 
                    }
                }
                
                Thread.sleep(5000)
            }
            
            logger.warn { "Rollout timeout after ${timeout/1000} seconds" }
        } catch (e: Exception) {
            logger.warn(e) { "Error waiting for rollout" }
        }
    }
}