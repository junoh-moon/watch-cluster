package com.watchcluster.controller

import com.watchcluster.model.*
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.CronScheduler
import com.watchcluster.client.K8sClient
import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.*
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

class WatchController(
    private val k8sClient: K8sClient,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    private val webhookConfig = WebhookConfig.fromEnvironment()
    private val webhookService = WebhookService(webhookConfig)
    private val imageChecker = ImageChecker(k8sClient)
    private val deploymentUpdater = DeploymentUpdater(k8sClient, webhookService)
    private val cronScheduler = CronScheduler()
    private val watchedDeployments = ConcurrentHashMap<String, WatchedDeployment>()
    private val deploymentMutexes = ConcurrentHashMap<String, Mutex>()

    fun start() {
        val coroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        logger.info { "Starting deployment watcher..." }
        
        k8sClient.watchDeployments(object : K8sWatcher<com.watchcluster.client.domain.DeploymentInfo> {
            override fun eventReceived(event: K8sWatchEvent<com.watchcluster.client.domain.DeploymentInfo>) {
                val deployment = event.resource
                when (event.type) {
                    EventType.ADDED, EventType.MODIFIED -> {
                        coroutineScope.async {
                            handleDeployment(deployment)
                        }
                    }
                    EventType.DELETED -> {
                        val key = "${deployment.namespace}/${deployment.name}"
                        watchedDeployments.remove(key)
                        deploymentMutexes.remove(key)
                        cronScheduler.cancelJob(key)
                        logger.info { "Stopped watching deployment: $key" }
                    }
                    EventType.ERROR -> {
                        logger.warn { "Watch error for deployment ${deployment.namespace}/${deployment.name}" }
                    }
                }
            }

            override fun onClose(exception: Exception?) {
                logger.warn { "Deployment watch closed: ${exception?.message}" }
            }
        })
    }

    private suspend fun handleDeployment(deployment: com.watchcluster.client.domain.DeploymentInfo) {
        val annotations = deployment.annotations
        val enabled = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false
        
        if (!enabled) return
        
        val namespace = deployment.namespace
        val name = deployment.name
        val key = "$namespace/$name"
        
        val cronExpression = annotations[WatchClusterAnnotations.CRON] ?: "0 */5 * * * ?"
        val strategyStr = annotations[WatchClusterAnnotations.STRATEGY] ?: "version"
        val strategy = UpdateStrategy.fromString(strategyStr)
        
        val containers = deployment.containers
        if (containers.isEmpty()) return
        
        val currentImage = containers[0].image
        
        val imagePullSecrets = deployment.imagePullSecrets
        
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
			deployment = DeploymentEventData(namespace, name, currentImage),
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
