package com.watchcluster

import com.watchcluster.api.AdminService
import com.watchcluster.api.HttpServer
import com.watchcluster.client.impl.Fabric8K8sClient
import com.watchcluster.controller.WatchController
import com.watchcluster.model.WebhookConfig
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

fun main(): Unit =
    runBlocking {
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
                "Webhook Headers: ${webhookConfig.headers.entries
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(", ") ?: "None"}"
            }

            val fabric8Client = KubernetesClientBuilder().build()
            val k8sClient = Fabric8K8sClient(fabric8Client)
            val config = k8sClient.getConfiguration()
            logger.info {
                "Connected to Kubernetes cluster: ${config.masterUrl}"
            }

            // Get current pod information
            if (podName != "unknown" && podNamespace != "unknown") {
                runCatching {
                    val pod = k8sClient.getPod(podNamespace, podName)
                    pod
                        ?.status
                        ?.containerStatuses
                        ?.forEach { containerStatus ->
                            logger.info { "Container: ${containerStatus.name}" }
                            logger.info { "  Image: ${containerStatus.image}" }
                            logger.info { "  Image ID: ${containerStatus.imageID}" }
                            logger.info { "  Ready: ${containerStatus.ready}" }
                        }
                }.onFailure { e ->
                    logger.warn { "Failed to get pod information: ${e.message}" }
                }
            }

            logger.info { "==================================" }

            val controller = WatchController(k8sClient)
            val httpServer = HttpServer.fromEnvironment(AdminService(k8sClient, controller))
            val shutdownStarted = AtomicBoolean(false)
            val shutdown: suspend () -> Unit = {
                if (shutdownStarted.compareAndSet(false, true)) {
                    logger.info { "Stopping watch-cluster..." }
                    // Stop accepting admin requests before the controller
                    // goes away, so no request observes a half-torn-down
                    // controller.
                    runCatching { httpServer.stop() }
                        .onFailure { e -> logger.warn(e) { "Failed to stop admin server cleanly" } }
                    controller.stopAndJoin()
                    k8sClient.close()
                }
            }
            val shutdownHook =
                Thread(
                    {
                        runBlocking { shutdown() }
                    },
                    "watch-cluster-shutdown",
                )
            var hookRegistered = false

            try {
                controller.start()
                httpServer.start()
                Runtime.getRuntime().addShutdownHook(shutdownHook)
                hookRegistered = true

                // main thread가 종료되지 않도록 block
                Thread.currentThread().join()
            } finally {
                shutdown()
                if (hookRegistered) {
                    runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
                }
            }
        }.onFailure { e ->
            logger.error(e) { "Failed to start watch-cluster" }
            throw e
        }
    }
