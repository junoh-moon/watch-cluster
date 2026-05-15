package com.watchcluster.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.watchcluster.client.K8sClient
import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.EventType
import com.watchcluster.client.domain.K8sWatchEvent
import com.watchcluster.model.DeploymentEventData
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.model.WatchedDeployment
import com.watchcluster.model.WebhookConfig
import com.watchcluster.model.WebhookEvent
import com.watchcluster.model.WebhookEventType
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.CronScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}
private val objectMapper = ObjectMapper()

internal enum class DeploymentCheckStatus {
    UPDATED,
    NO_UPDATE,
    FAILED,
    SKIPPED,
}

internal data class DeploymentCheckResult(
    val status: DeploymentCheckStatus,
    val message: String,
)

class WatchController(
    private val k8sClient: K8sClient,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    imageChecker: ImageChecker? = null,
    deploymentUpdater: DeploymentUpdater? = null,
    cronScheduler: CronScheduler? = null,
) {
    private val webhookConfig = WebhookConfig.fromEnvironment()
    private val webhookService = WebhookService(webhookConfig)
    private val imageChecker = imageChecker ?: ImageChecker(k8sClient)
    private val deploymentUpdater = deploymentUpdater ?: DeploymentUpdater(k8sClient, webhookService)
    private val cronScheduler = cronScheduler ?: CronScheduler()
    internal val watchedDeployments = ConcurrentHashMap<String, WatchedDeployment>()
    internal val deploymentMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun start() {
        logger.info { "Starting deployment watcher..." }

        k8sClient.watchDeployments(
            object : K8sWatcher<DeploymentInfo> {
                override suspend fun eventReceived(event: K8sWatchEvent<DeploymentInfo>) {
                    val deployment = event.resource
                    when (event.type) {
                        EventType.ADDED, EventType.MODIFIED -> {
                            handleDeployment(deployment)
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

                override suspend fun onClose(exception: Exception?) {
                    logger.warn { "Deployment watch closed: ${exception?.message}" }
                }
            },
        )
    }

    private suspend fun handleDeployment(deployment: DeploymentInfo) {
        val annotations = deployment.annotations
        val enabled = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false
        val checkNowRequested = annotations.containsKey(WatchClusterAnnotations.CHECK_NOW)

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

        val watchedDeployment =
            WatchedDeployment(
                namespace = namespace,
                name = name,
                cronExpression = cronExpression,
                updateStrategy = strategy,
                currentImage = currentImage,
                imagePullSecrets = imagePullSecrets,
            )

        val mutex = deploymentMutexes.computeIfAbsent(key) { Mutex() }

        mutex.withLock {
            cronScheduler.cancelAndJoinJob(key)
            watchedDeployments[key] = watchedDeployment
            cronScheduler.scheduleJob(key, cronExpression) {
                checkAndUpdateDeployment(key)
            }
        }

        webhookService.sendWebhook(
            WebhookEvent(
                eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                timestamp =
                    java.time.Instant
                        .now()
                        .toString(),
                deployment = DeploymentEventData(namespace, name, currentImage),
                details =
                    mapOf(
                        "cronExpression" to cronExpression,
                        "updateStrategy" to strategy.displayName,
                    ),
            ),
        )

        logger.info { "Watching deployment: $key with cron: $cronExpression and strategy: $strategy" }

        if (checkNowRequested) {
            triggerManualCheck(key, namespace, name)
        }
    }

    private fun triggerManualCheck(
        key: String,
        namespace: String,
        name: String,
    ) {
        coroutineScope.launch {
            val removed = removeCheckNowAnnotation(namespace, name)
            if (!removed) {
                recordDeploymentEvent(
                    namespace = namespace,
                    deploymentName = name,
                    reason = "ManualCheckFailed",
                    message = "Manual check for $key was not run because ${WatchClusterAnnotations.CHECK_NOW} could not be removed",
                    type = "Warning",
                )
                return@launch
            }

            recordDeploymentEvent(
                namespace = namespace,
                deploymentName = name,
                reason = "ManualCheckRequested",
                message = "Manual check requested for $key by ${WatchClusterAnnotations.CHECK_NOW}",
                type = "Normal",
            )

            val result = checkAndUpdateDeployment(key)
            recordManualCheckResult(namespace, name, result)
        }
    }

    private suspend fun removeCheckNowAnnotation(
        namespace: String,
        name: String,
    ): Boolean {
        val annotationPatch: Map<String, String?> = mapOf(WatchClusterAnnotations.CHECK_NOW to null)
        val patchJson =
            objectMapper.writeValueAsString(
                mapOf(
                    "metadata" to
                        mapOf(
                            "annotations" to annotationPatch,
                        ),
                ),
            )

        return k8sClient.patchDeployment(namespace, name, patchJson) != null
    }

    private suspend fun recordManualCheckResult(
        namespace: String,
        name: String,
        result: DeploymentCheckResult,
    ) {
        val (reason, type) =
            when (result.status) {
                DeploymentCheckStatus.UPDATED -> "ManualCheckUpdated" to "Normal"
                DeploymentCheckStatus.NO_UPDATE -> "ManualCheckNoUpdate" to "Normal"
                DeploymentCheckStatus.FAILED -> "ManualCheckFailed" to "Warning"
                DeploymentCheckStatus.SKIPPED -> "ManualCheckSkipped" to "Warning"
            }

        recordDeploymentEvent(
            namespace = namespace,
            deploymentName = name,
            reason = reason,
            message = result.message,
            type = type,
        )
    }

    private suspend fun recordDeploymentEvent(
        namespace: String,
        deploymentName: String,
        reason: String,
        message: String,
        type: String,
    ) {
        runCatching {
            k8sClient.recordDeploymentEvent(namespace, deploymentName, reason, message, type)
        }.onFailure { e ->
            logger.warn(e) { "Failed to audit event $reason for deployment $namespace/$deploymentName" }
        }
    }

    internal suspend fun checkAndUpdateDeployment(key: String): DeploymentCheckResult {
        val mutex =
            deploymentMutexes[key] ?: run {
                val message = "Mutex not found for deployment $key"
                logger.warn { message }
                return DeploymentCheckResult(DeploymentCheckStatus.SKIPPED, message)
            }

        return mutex.withLock {
            // Retrieve latest deployment info inside mutex
            val deployment =
                watchedDeployments[key] ?: run {
                    val message = "Deployment not found: $key"
                    logger.warn { message }
                    return@withLock DeploymentCheckResult(DeploymentCheckStatus.SKIPPED, message)
                }

            runCatching {
                logger.info { "Current image: ${deployment.currentImage}" }
                logger.info { "Checking for updates: ${deployment.namespace}/${deployment.name}" }

                val updateResult =
                    imageChecker.checkForUpdate(
                        deployment.currentImage,
                        deployment.updateStrategy,
                        deployment.namespace,
                        deployment.imagePullSecrets,
                        deployment.name,
                    )

                when {
                    // Has update
                    updateResult.newImage != null -> {
                        logger.info {
                            buildString {
                                append("Found update for ${deployment.namespace}/${deployment.name}: ${updateResult.newImage}")
                                updateResult.reason?.let { append(" $it") }
                            }
                        }
                        deploymentUpdater.updateDeployment(
                            deployment.namespace,
                            deployment.name,
                            updateResult.newImage,
                            updateResult.currentImage,
                            deployment.updateStrategy,
                            updateResult.newDigest,
                        )

                        // Update cache to reflect the new image
                        val latest = watchedDeployments[key] ?: deployment
                        watchedDeployments[key] =
                            latest.copy(
                                currentImage = updateResult.newImage,
                            )

                        DeploymentCheckResult(
                            DeploymentCheckStatus.UPDATED,
                            "Updated ${deployment.namespace}/${deployment.name} to ${updateResult.newImage}",
                        )
                    }

                    else -> {
                        val message =
                            updateResult.reason
                                ?: "No update available for ${deployment.namespace}/${deployment.name}"
                        logger.debug {
                            buildString {
                                append("No update available for ${deployment.namespace}/${deployment.name}.")
                                updateResult.reason?.let { append(" $it") }
                            }
                        }
                        DeploymentCheckResult(DeploymentCheckStatus.NO_UPDATE, message)
                    }
                }
            }.getOrElse { e ->
                val message = "Error checking deployment ${deployment.namespace}/${deployment.name}: ${e.message ?: "unknown error"}"
                logger.error(e) { "Error checking deployment ${deployment.namespace}/${deployment.name}" }
                DeploymentCheckResult(DeploymentCheckStatus.FAILED, message)
            }
        }
    }
}
