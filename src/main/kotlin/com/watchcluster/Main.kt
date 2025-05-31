package com.watchcluster

import com.watchcluster.controller.WatchController
import com.watchcluster.model.WebhookConfig
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    logger.info { "Starting watch-cluster..." }

    try {
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
            val headers = webhookConfig.headers.entries.takeIf { it.isNotEmpty() }?.joinToString(", ")
                            ?: "None"
            "Webhook Headers: ${headers}"
        }

        val kubernetesClient = KubernetesClientBuilder().build()
        logger.info {
            "Connected to Kubernetes cluster: ${kubernetesClient.configuration.masterUrl}"
        }

        // Get current pod information
        if (podName != "unknown" && podNamespace != "unknown") {
            try {
                val pod = kubernetesClient.pods()
                    .inNamespace(podNamespace)
                    .withName(podName)
                    .get()

                if (pod != null) {
                    val containerStatuses = pod.status?.containerStatuses ?: emptyList()
                    containerStatuses.forEach { containerStatus ->
                        logger.info { "Container: ${containerStatus.name}" }
                        logger.info { "  Image: ${containerStatus.image}" }
                        logger.info { "  Image ID: ${containerStatus.imageID}" }
                    }
                }
            } catch (e: Exception) {
                logger.warn { "Failed to get pod information: ${e.message}" }
            }
        }

        logger.info { "==================================" }

        val controller = WatchController(kubernetesClient)
        controller.start()
    } catch (e: Exception) {
        logger.error(e) { "Failed to start watch-cluster" }
        throw e
    }
}
