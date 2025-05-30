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
import java.util.concurrent.ConcurrentHashMap

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
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun start() {
        logger.info { "Starting deployment watcher..." }
        
        kubernetesClient.apps().deployments()
            .inAnyNamespace()
            .inform(object : ResourceEventHandler<Deployment> {
                override fun onAdd(deployment: Deployment) {
                    handleDeployment(deployment)
                }

                override fun onUpdate(oldDeployment: Deployment, newDeployment: Deployment) {
                    handleDeployment(newDeployment)
                }

                override fun onDelete(deployment: Deployment, deletedFinalStateUnknown: Boolean) {
                    val key = "${deployment.metadata.namespace}/${deployment.metadata.name}"
                    watchedDeployments.remove(key)
                    cronScheduler.cancelJob(key)
                    logger.info { "Stopped watching deployment: $key" }
                }
            })
    }

    private fun handleDeployment(deployment: Deployment) {
        val annotations = deployment.metadata.annotations ?: return
        val enabled = annotations[Annotations.ENABLED]?.toBoolean() ?: false
        
        if (!enabled) return
        
        val namespace = deployment.metadata.namespace
        val name = deployment.metadata.name
        val key = "$namespace/$name"
        
        val cronExpression = annotations[Annotations.CRON] ?: "0 */5 * * * ?"
        val strategy = parseStrategy(annotations[Annotations.STRATEGY] ?: "version")
        
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
            scope.launch {
                checkAndUpdateDeployment(watchedDeployment)
            }
        }
        
        scope.launch {
            webhookService.sendWebhook(WebhookEvent(
                eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                timestamp = java.time.Instant.now().toString(),
                deployment = DeploymentInfo(namespace, name, currentImage),
                details = mapOf(
                    "cronExpression" to cronExpression,
                    "updateStrategy" to strategy.toString()
                )
            ))
        }
        
        logger.info { "Watching deployment: $key with cron: $cronExpression and strategy: $strategy" }
    }

    private fun parseStrategy(strategyStr: String): UpdateStrategy {
        return when {
            strategyStr.lowercase() == "latest" -> UpdateStrategy.Latest
            strategyStr.contains("lock-major") || strategyStr.contains("lockmajor") -> 
                UpdateStrategy.Version(lockMajorVersion = true)
            else -> UpdateStrategy.Version()
        }
    }

    private suspend fun checkAndUpdateDeployment(deployment: WatchedDeployment) {
        try {
            logger.info { "Checking for updates: ${deployment.namespace}/${deployment.name}" }
            
            val updateResult = imageChecker.checkForUpdate(
                deployment.currentImage,
                deployment.updateStrategy,
                deployment.namespace,
                deployment.imagePullSecrets
            )
            
            if (updateResult.hasUpdate) {
                logger.info { "Found update for ${deployment.namespace}/${deployment.name}: ${updateResult.newImage}" }
                deploymentUpdater.updateDeployment(
                    deployment.namespace,
                    deployment.name,
                    updateResult.newImage!!
                )
            } else {
                logger.debug { "No update available for ${deployment.namespace}/${deployment.name}" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking deployment ${deployment.namespace}/${deployment.name}" }
        }
    }

    fun stop() {
        cronScheduler.shutdown()
        scope.cancel()
    }
}