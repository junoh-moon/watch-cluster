package com.watchcluster.controller

import com.watchcluster.model.*
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.CronScheduler
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Component
class WatchController(
    private val kubernetesClient: KubernetesClient,
    private val webhookService: WebhookService,
    private val imageChecker: ImageChecker,
    private val deploymentUpdater: DeploymentUpdater,
    private val deploymentUpdateManager: DeploymentUpdateManager,
    private val cronScheduler: CronScheduler,
) {
    private val watchedDeployments = ConcurrentHashMap<String, WatchedDeployment>()
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    suspend fun start() {
        logger.info { "Starting deployment watcher..." }
        
        kubernetesClient.apps().deployments()
            .inAnyNamespace()
            .inform(object : ResourceEventHandler<Deployment> {
                override fun onAdd(deployment: Deployment) {
                    scope.launch {
                        handleDeployment(deployment)
                    }
                }

                override fun onUpdate(oldDeployment: Deployment, newDeployment: Deployment) {
                    scope.launch {
                        handleDeployment(newDeployment)
                    }
                }

                override fun onDelete(deployment: Deployment, deletedFinalStateUnknown: Boolean) {
                    val key = "${deployment.metadata.namespace}/${deployment.metadata.name}"
                    watchedDeployments.remove(key)
                    cronScheduler.cancelJob(key)
                    deploymentUpdateManager.removeDeployment(
                        deployment.metadata.namespace,
                        deployment.metadata.name
                    )
                    logger.info { "Stopped watching deployment: $key" }
                }
            })
    }

    private suspend fun handleDeployment(deployment: Deployment) {
        val annotations = deployment.metadata.annotations ?: return
        val enabled = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false
        
        if (!enabled) return
        
        val namespace = deployment.metadata.namespace
        val name = deployment.metadata.name
        val key = "$namespace/$name"
        
        val cronExpression = annotations[WatchClusterAnnotations.CRON] ?: "0 */5 * * * ?"
        val strategyStr = annotations[WatchClusterAnnotations.STRATEGY] ?: "version"
        val strategy = UpdateStrategy.fromString(strategyStr)
        
        val containers = deployment.spec.template.spec.containers
        if (containers.isEmpty()) return
        
        val currentImage = containers[0].image
        
        val imagePullSecrets = deployment.spec.template.spec.imagePullSecrets?.map { it.name }
        
        val watchedDeployment = WatchedDeployment(
            namespace = namespace,
            name = name,
            cronExpression = cronExpression,
            updateStrategy = strategy,
            currentImage = currentImage,
            imagePullSecrets = imagePullSecrets
        )
        
        watchedDeployments[key] = watchedDeployment
        
        cronScheduler.scheduleJob(key, cronExpression) {
			checkAndUpdateDeployment(watchedDeployment)
        }
        
		webhookService.sendWebhook(WebhookEvent(
			eventType = WebhookEventType.DEPLOYMENT_DETECTED,
			timestamp = java.time.Instant.now().toString(),
			deployment = DeploymentInfo(namespace, name, currentImage),
			details = mapOf(
				"cronExpression" to cronExpression,
				"updateStrategy" to strategy.displayName
			)
		))
        
        logger.info { "Watching deployment: $key with cron: $cronExpression and strategy: $strategy" }
    }

    // parseStrategy method removed - using UpdateStrategy.fromString() directly

    private suspend fun checkAndUpdateDeployment(deployment: WatchedDeployment) {
        // Use DeploymentUpdateManager to ensure serialized updates for each deployment
        deploymentUpdateManager.executeUpdate(
            deployment.namespace,
            deployment.name
        ) {
            runCatching {
                logger.info { "Checking for updates: ${deployment.namespace}/${deployment.name}" }
                
                val updateResult = imageChecker.checkForUpdate(
                    deployment.currentImage,
                    deployment.updateStrategy,
                    deployment.namespace,
                    deployment.imagePullSecrets,
                    deployment.name
                )
                
                when {
                    updateResult.hasUpdate -> {
                        logger.info {
                            buildString {
                                append("Found update for ${deployment.namespace}/${deployment.name}: ${updateResult.newImage}")
                                updateResult.reason?.let { append(" $it") }
                            }
                        }
                        deploymentUpdater.updateDeployment(
                            deployment.namespace,
                            deployment.name,
                            updateResult.newImage!!,
                            updateResult
                        )
                    }
                    else -> {
                        logger.debug {
                            buildString {
                                append("No update available for ${deployment.namespace}/${deployment.name}.")
                                updateResult.reason?.let { append(" $it") }
                            }
                        }
                    }
                }
            }.onFailure { e ->
                logger.error(e) {"Error checking deployment ${deployment.namespace}/${deployment.name}" }
            }
        }
    }

    fun stop() {
        cronScheduler.shutdown()
		scope.cancel()
    }
}
