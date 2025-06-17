package com.watchcluster

import com.watchcluster.controller.WatchController
import com.watchcluster.model.WebhookConfig
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.*
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

suspend fun main() = coroutineScope<Unit> {
    logger.info { "Starting watch-cluster..." }

    runCatching {
        // Load and log environment variables
        val podName = System.getenv("POD_NAME") ?: "unknown"
        val podNamespace = System.getenv("POD_NAMESPACE") ?: "unknown"

        logger.info { "=== watch-cluster Configuration ===" }
        logger.info { "Pod Name: $podName" }
        logger.info { "Pod Namespace: $podNamespace" }

        // Load webhook configuration
        val webhookConfig = WebhookConfig.fromEnvironment()
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

        val kubernetesClient = withContext(Dispatchers.IO) {
            KubernetesClientBuilder().build()
        }
        logger.info {
            "Connected to Kubernetes cluster: ${kubernetesClient.configuration.masterUrl}"
        }

        // Get current pod information
        if (podName != "unknown" && podNamespace != "unknown") {
            runCatching {
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
            }.onFailure { e ->
                logger.warn { "Failed to get pod information: ${e.message}" }
            }
        }

        logger.info { "==================================" }

        val controller = WatchController(kubernetesClient)
        controller.start()
    }.onFailure { e ->
        logger.error(e) { "Failed to start watch-cluster" }
        throw e
    }
}
