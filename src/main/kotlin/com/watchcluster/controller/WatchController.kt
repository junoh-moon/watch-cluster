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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

class WatchController(
    private val kubernetesClient: KubernetesClient
) {
    private val webhookConfig = WebhookConfig.fromEnvironment()
    private val webhookService = WebhookService(webhookConfig)
    private val imageChecker = ImageChecker(kubernetesClient)
    private val deploymentUpdater = DeploymentUpdater(kubernetesClient, webhookService)
    private val cronScheduler = CronScheduler()
    private val watchedDeployments = ConcurrentHashMap<String, WatchedDeployment>()

    suspend fun start() {
        logger.info { "Starting deployment watcher..." }
        
        val currentScope = CoroutineScope(coroutineContext)
        
        kubernetesClient.apps().deployments()
            .inAnyNamespace()
            .inform(object : ResourceEventHandler<Deployment> {
                override fun onAdd(deployment: Deployment) {
                    currentScope.async {
                        handleDeployment(deployment)
                    }
                }

                override fun onUpdate(oldDeployment: Deployment, newDeployment: Deployment) {
                    currentScope.async {
                        handleDeployment(newDeployment)
                    }
                }

                override fun onDelete(deployment: Deployment, deletedFinalStateUnknown: Boolean) {
                    val key = "${deployment.metadata.namespace}/${deployment.metadata.name}"
                    watchedDeployments.remove(key)
                    cronScheduler.cancelJob(key)
                    logger.info { "Stopped watching deployment: $key" }
                }
            })
    }

    private suspend fun handleDeployment(deployment: Deployment) {
        val annotations = deployment.metadata.annotations ?: return
        val enabled = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false
        
        if (!enabled) return
        
        val currentScope = CoroutineScope(coroutineContext)
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
        
        currentScope.async {
            webhookService.sendWebhook(WebhookEvent(
                eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                timestamp = java.time.Instant.now().toString(),
                deployment = DeploymentInfo(namespace, name, currentImage),
                details = mapOf(
                    "cronExpression" to cronExpression,
                    "updateStrategy" to strategy.displayName
                )
            ))
        }
        
        logger.info { "Watching deployment: $key with cron: $cronExpression and strategy: $strategy" }
    }

    // parseStrategy method removed - using UpdateStrategy.fromString() directly

    private suspend fun checkAndUpdateDeployment(deployment: WatchedDeployment) {
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

    fun stop() {
        cronScheduler.shutdown()
    }
}
