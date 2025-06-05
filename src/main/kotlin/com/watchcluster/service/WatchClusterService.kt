package com.watchcluster.service

import com.watchcluster.controller.WatchController
import com.watchcluster.model.WebhookConfig
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.SmartLifecycle
import org.springframework.context.event.EventListener
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ScheduledFuture
import kotlin.coroutines.CoroutineContext

private val logger = KotlinLogging.logger {}

@Service
class WatchClusterService(
    private val watchController: WatchController,
    private val webhookConfig: WebhookConfig,
    private val kubernetesClient: KubernetesClient,
    @Qualifier("watchClusterScheduler") private val taskScheduler: TaskScheduler,
    @Qualifier("watchClusterContext") private val watchClusterContext: CoroutineContext
) : SmartLifecycle {

    private var isRunning = false
    private var watchTask: ScheduledFuture<*>? = null

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        logConfiguration()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun start() {
        if (isRunning) return

        logger.info { "Starting watch-cluster..." }

        watchTask = taskScheduler.schedule({
            runBlocking(watchClusterContext) {
                runCatching {
                    watchController.start()
                }.onFailure { e ->
                    logger.error(e) { "Failed to start watch-cluster" }
                    throw e
                }
            }
        }, Instant.now())

        isRunning = true
    }

    override fun stop() {
        if (!isRunning) return

        logger.info { "Stopping watch-cluster..." }

        runCatching {
            watchTask?.cancel(true)
            watchController.stop()
        }.onFailure { e ->
            logger.error(e) { "Error during shutdown" }
        }

        isRunning = false
    }

    override fun isRunning(): Boolean = isRunning

    override fun getPhase(): Int = 1000

    private fun logConfiguration() {
        val podName = System.getenv("POD_NAME") ?: "unknown"
        val podNamespace = System.getenv("POD_NAMESPACE") ?: "unknown"

        logger.info { "=== watch-cluster Configuration ===" }
        logger.info { "Pod Name: $podName" }
        logger.info { "Pod Namespace: $podNamespace" }

        logger.info {
            "Connected to Kubernetes cluster: ${kubernetesClient.configuration.masterUrl}"
        }

        // Get current pod information
        if (podName != "unknown" && podNamespace != "unknown") {
            runCatching {
                runBlocking(watchClusterContext) {
                    withContext(Dispatchers.IO) {
                        kubernetesClient.pods()
                            .inNamespace(podNamespace)
                            .withName(podName)
                            .get()
                    }?.status
                        ?.containerStatuses
                        ?.forEach { containerStatus ->
                            logger.info { "Container: ${containerStatus.name}" }
                            logger.info { "  Image: ${containerStatus.image}" }
                            logger.info { "  Image ID: ${containerStatus.imageID}" }
                        }
                }
            }.onFailure { e ->
                logger.warn { "Failed to get pod information: ${e.message}" }
            }
        }

        logger.info { "Webhook URL: ${webhookConfig.url ?: "Not configured"}" }
        logger.info { "Webhook Timeout: ${webhookConfig.timeout}ms" }
        logger.info { "Webhook Retry Count: ${webhookConfig.retryCount}" }
        logger.info { "Webhook Events Enabled:" }
        logger.info { "  - Deployment Detected: ${webhookConfig.enableDeploymentDetected}" }
        logger.info { "  - Image Rollout Started: ${webhookConfig.enableImageRolloutStarted}" }
        logger.info { "  - Image Rollout Completed: ${webhookConfig.enableImageRolloutCompleted}" }
        logger.info { "  - Image Rollout Failed: ${webhookConfig.enableImageRolloutFailed}" }
        logger.info {
            "Webhook Headers: ${webhookConfig.headers.entries.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "None"}"
        }
        logger.info { "==================================" }
    }
}