package com.watchcluster

import com.watchcluster.controller.WatchController
import com.watchcluster.model.WebhookConfig
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.stereotype.Component

private val logger = KotlinLogging.logger {}

@SpringBootApplication
class WatchClusterApplication

fun main(args: Array<String>) {
    runApplication<WatchClusterApplication>(*args)
}

@Component
class WatchClusterRunner(
    private val watchController: WatchController
) : CommandLineRunner {
    
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun run(vararg args: String?) {
        newSingleThreadContext("WatchClusterThread").use { singleThreadContext ->
            runBlocking(singleThreadContext) {
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

                    logger.info { "==================================" }

                    watchController.start()
                }.onFailure { e ->
                    logger.error(e) { "Failed to start watch-cluster" }
                    throw e
                }
            }
        }
    }
}
