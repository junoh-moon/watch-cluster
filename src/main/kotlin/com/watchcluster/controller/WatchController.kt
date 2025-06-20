package com.watchcluster.controller

import com.watchcluster.model.*
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.CronScheduler
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

class WatchController(
    private val kubernetesClient: KubernetesClient,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val webhookConfig = WebhookConfig.fromEnvironment()
    private val webhookService = WebhookService(webhookConfig)
    private val imageChecker = ImageChecker(kubernetesClient)
    private val deploymentUpdater = DeploymentUpdater(kubernetesClient, webhookService)
    private val cronScheduler = CronScheduler()
    private val watchedDeployments = ConcurrentHashMap<String, WatchedDeployment>()
    private val deploymentMutexes = ConcurrentHashMap<String, Mutex>()

    fun start() {
        val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        logger.info { "Starting deployment watcher..." }
        
        kubernetesClient.apps().deployments()
            .inAnyNamespace()
            .watch(object : Watcher<Deployment> {
                override fun eventReceived(action: Watcher.Action, deployment: Deployment) {
                    when (action) {
                        Watcher.Action.ADDED, Watcher.Action.MODIFIED -> {
                            coroutineScope.async {
                                handleDeployment(deployment)
                            }
                        }
                        Watcher.Action.DELETED -> {
                            val key = "${deployment.metadata.namespace}/${deployment.metadata.name}"
                            watchedDeployments.remove(key)
                            deploymentMutexes.remove(key)
                            cronScheduler.cancelJob(key)
                            logger.info { "Stopped watching deployment: $key" }
                        }
                        else -> {
                            logger.warn { "Ignore action: $action" }
                        }
                    }
                }

                override fun onClose(cause: WatcherException?) {
                    logger.warn { "Deployment watch closed: ${cause?.message}" }
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
        deploymentMutexes.computeIfAbsent(key) { Mutex() }
        
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
        val key = "${deployment.namespace}/${deployment.name}"
        val mutex = deploymentMutexes[key] ?: run {
            logger.warn { "Mutex not found for deployment $key" }
            return
        }
        
        mutex.withLock {
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
        imageChecker.shutdown()
        deploymentUpdater.shutdown()
        coroutineScope.cancel()
    }
}
