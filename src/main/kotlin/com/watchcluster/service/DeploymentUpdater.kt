package com.watchcluster.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.watchcluster.client.K8sClient
import com.watchcluster.client.domain.DeploymentConditionReason
import com.watchcluster.client.domain.DeploymentConditionType
import com.watchcluster.client.domain.K8sConditionStatus
import com.watchcluster.client.domain.PodConditionType
import com.watchcluster.model.DeploymentEventData
import com.watchcluster.model.ImagePlatform
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.model.WebhookEvent
import com.watchcluster.model.WebhookEventType
import com.watchcluster.util.ImageParser
import mu.KotlinLogging
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val logger = KotlinLogging.logger {}

class DeploymentUpdater(
    private val k8sClient: K8sClient,
    private val webhookService: WebhookService,
    private val registryClient: DockerRegistryClient = DockerRegistryClient(),
    private val rolloutTimeoutMs: Long = 300000L,
    private val rolloutPollIntervalMs: Long = 5000L,
) {
    suspend fun updateDeployment(
        namespace: String,
        name: String,
        newImageRef: String,
        previousImage: String,
        strategy: UpdateStrategy,
        expectedDigest: String? = null,
    ) {
        runCatching {
            // Fetch current deployment state first
            val deployment =
                k8sClient.getDeployment(namespace, name)
                    ?: throw IllegalStateException("Deployment $namespace/$name not found")

            val containers = deployment.containers
            if (containers.isEmpty()) {
                throw IllegalStateException("No containers found in deployment $namespace/$name")
            }

            val actualCurrentImage = containers[0].image

            // Latest strategy must always re-patch — rollout is triggered by annotation change, not image-string change.
            if (strategy !is UpdateStrategy.Latest && actualCurrentImage == newImageRef) {
                logger.info { "Deployment $namespace/$name already at $newImageRef, skipping update" }
                return
            }

            logger.info { "Updating deployment $namespace/$name with new image: $newImageRef" }

            webhookService.sendWebhook(
                WebhookEvent(
                    eventType = WebhookEventType.IMAGE_ROLLOUT_STARTED,
                    timestamp =
                        java.time.Instant
                            .now()
                            .toString(),
                    deployment = DeploymentEventData(namespace, name, newImageRef),
                    details = mapOf("previousImage" to actualCurrentImage),
                ),
            )

            // Get the first container's name (or find the appropriate container)
            val containerName = containers[0].name

            val timestamp = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val annotationMap =
                mapOf(
                    WatchClusterAnnotations.LAST_UPDATE to timestamp,
                    WatchClusterAnnotations.CHANGE to "$previousImage -> $newImageRef",
                )

            val patchJson = buildCombinedPatch(containerName to newImageRef, annotationMap, strategy)

            k8sClient.patchDeployment(namespace, name, patchJson)?.let {
                logger.info { "Patch successful - deployment $namespace/$name updated with new image: $newImageRef" }
            }
                ?: run {
                    logger.error { "Patch returned null response for deployment $namespace/$name" }
                    throw IllegalStateException("Patch operation returned null deployment")
                }

            waitForRollout(namespace, name, newImageRef, strategy, expectedDigest, PlatformCache())
        }.onFailure { e ->
            logger.error(e) { "Failed to update deployment $namespace/$name" }

            webhookService.sendWebhook(
                WebhookEvent(
                    eventType = WebhookEventType.IMAGE_ROLLOUT_FAILED,
                    timestamp =
                        java.time.Instant
                            .now()
                            .toString(),
                    deployment = DeploymentEventData(namespace, name, newImageRef),
                    details = mapOf("error" to (e.message ?: "Unknown error")),
                ),
            )

            throw e
        }
    }

    private fun buildCombinedPatch(
        containerImage: Pair<String, String>,
        annotations: Map<String, String>,
        strategy: UpdateStrategy,
    ): String {
        val (containerName, imageToSet) = containerImage

        val containerPatch =
            mutableMapOf<String, Any>(
                "name" to containerName,
                "image" to imageToSet,
            )

        if (strategy is UpdateStrategy.Latest) {
            containerPatch["imagePullPolicy"] = "Always"
        }

        val podTemplateAnnotations =
            mapOf(
                WatchClusterAnnotations.LAST_UPDATE to (annotations[WatchClusterAnnotations.LAST_UPDATE] ?: ""),
            )

        val patchData =
            mapOf(
                "metadata" to
                    mapOf(
                        "annotations" to annotations,
                    ),
                "spec" to
                    mapOf(
                        "template" to
                            mapOf(
                                "metadata" to
                                    mapOf(
                                        "annotations" to podTemplateAnnotations,
                                    ),
                                "spec" to
                                    mapOf(
                                        "containers" to listOf(containerPatch),
                                    ),
                            ),
                    ),
            )

        return objectMapper.writeValueAsString(patchData)
    }

    private suspend fun waitForRollout(
        namespace: String,
        name: String,
        newImage: String,
        strategy: UpdateStrategy,
        expectedDigest: String?,
        platformCache: PlatformCache,
    ) {
        runCatching {
            logger.info { "Waiting for rollout to complete..." }
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < rolloutTimeoutMs) {
                val deployment = k8sClient.getDeployment(namespace, name) ?: return
                val status = deployment.status

                // Check if controller has observed the latest generation
                val generationMatch = status.observedGeneration == deployment.generation
                if (!generationMatch) {
                    logger.debug {
                        "Waiting for controller to observe generation ${deployment.generation} (current: ${status.observedGeneration})"
                    }
                    kotlinx.coroutines.delay(rolloutPollIntervalMs)
                    continue
                }

                // Check deployment conditions
                val conditions = status.conditions
                val progressingCondition = conditions.find { it.type == DeploymentConditionType.PROGRESSING }
                val availableCondition = conditions.find { it.type == DeploymentConditionType.AVAILABLE }

                val isProgressing = progressingCondition?.status == K8sConditionStatus.TRUE
                val isAvailable = availableCondition?.status == K8sConditionStatus.TRUE
                val isComplete = progressingCondition?.reason == DeploymentConditionReason.NEW_REPLICA_SET_AVAILABLE

                // Check replica counts
                val replicas = deployment.replicas
                val updatedReplicas = status.updatedReplicas ?: 0
                val readyReplicas = status.readyReplicas ?: 0
                val availableReplicas = status.availableReplicas ?: 0

                // Check if all replicas are updated and ready
                val replicasReady =
                    updatedReplicas == replicas &&
                        readyReplicas == replicas &&
                        availableReplicas == replicas

                if (replicasReady && isAvailable && isComplete) {
                    // Verify actual pod images
                    val allPodsUpdated = verifyPodImages(deployment, namespace, newImage, strategy, expectedDigest, platformCache)

                    if (allPodsUpdated) {
                        logger.info { "Rollout completed successfully - all pods running image: $newImage" }

                        webhookService.sendWebhook(
                            WebhookEvent(
                                eventType = WebhookEventType.IMAGE_ROLLOUT_COMPLETED,
                                timestamp =
                                    java.time.Instant
                                        .now()
                                        .toString(),
                                deployment = DeploymentEventData(namespace, name, newImage),
                                details = mapOf("rolloutDuration" to "${System.currentTimeMillis() - startTime}ms"),
                            ),
                        )

                        return
                    } else {
                        logger.debug { "Waiting for all pods to update to new image" }
                    }
                }

                logger.debug {
                    listOf(
                        "Rollout progress - Generation: ${status.observedGeneration}/${deployment.generation}",
                        "Updated: $updatedReplicas/$replicas",
                        "Ready: $readyReplicas/$replicas",
                        "Available: $availableReplicas/$replicas",
                        "Progressing: $isProgressing (${progressingCondition?.reason})",
                        "Available: ${availableCondition?.status}",
                    ).joinToString(", ")
                }

                kotlinx.coroutines.delay(rolloutPollIntervalMs)
            }

            logger.warn { "Rollout timeout after ${rolloutTimeoutMs / 1000} seconds" }
        }.onFailure { e ->
            logger.warn(e) { "Error waiting for rollout" }
        }
    }

    private suspend fun verifyPodImages(
        deployment: com.watchcluster.client.domain.DeploymentInfo,
        namespace: String,
        expectedImage: String,
        strategy: UpdateStrategy,
        expectedDigest: String?,
        platformCache: PlatformCache,
    ): Boolean {
        return runCatching {
            val pods = k8sClient.listPodsByLabels(namespace, deployment.selector)

            if (pods.isEmpty()) {
                logger.warn { "No pods found for deployment $namespace/${deployment.name}" }
                return false
            }

            val targetContainerName = deployment.containers.firstOrNull()?.name

            val allPodsUpdated =
                pods.all { pod ->
                    val podReady =
                        pod.status.conditions
                            .find { it.type == PodConditionType.READY }
                            ?.status == K8sConditionStatus.TRUE

                    val hasCorrectImage =
                        if (strategy is UpdateStrategy.Latest && expectedDigest != null) {
                            // For Latest strategy, verify using imageID digest
                            val containerStatus =
                                pod.status.containerStatuses
                                    .find { it.name == targetContainerName }
                            val imageID = containerStatus?.imageID
                            val rawPodDigest = ImageParser.extractDigest(imageID)
                            val podDigest =
                                rawPodDigest?.let {
                                    resolvePodPlatformDigest(expectedImage, it, pod, platformCache)
                                }

                            val matches = podDigest == expectedDigest
                            if (!matches) {
                                logger.debug { "Pod ${pod.name} imageID digest: $podDigest, expected: $expectedDigest" }
                            }
                            matches
                        } else {
                            // For Version strategy or if no digest, verify using image string
                            val containerImages = pod.containers.map { it.image }
                            val matches = containerImages.any { image -> image == expectedImage }
                            if (!matches) {
                                logger.debug { "Pod ${pod.name} has images: $containerImages, expected: $expectedImage" }
                            }
                            matches
                        }

                    podReady && hasCorrectImage
                }

            logger.debug { "Pod image verification: ${pods.size} pods checked, all updated: $allPodsUpdated" }
            allPodsUpdated
        }.getOrElse { e ->
            logger.error(e) { "Failed to verify pod images" }
            false
        }
    }

    private suspend fun resolvePodPlatformDigest(
        image: String,
        digest: String,
        pod: com.watchcluster.client.domain.PodInfo,
        platformCache: PlatformCache,
    ): String {
        val nodeName = pod.nodeName ?: return digest
        val platform = platformCache.get(nodeName) { k8sClient.getNodePlatform(it) } ?: return digest
        val components = ImageParser.parseImageString(image)
        return registryClient.resolvePlatformDigest(components.registry, components.repository, digest, null, platform)
    }

    private class PlatformCache {
        private val cache = mutableMapOf<String, ImagePlatform>()

        suspend fun get(
            nodeName: String,
            fetch: suspend (String) -> ImagePlatform?,
        ): ImagePlatform? {
            cache[nodeName]?.let { return it }
            val platform = fetch(nodeName) ?: return null
            cache[nodeName] = platform
            return platform
        }
    }

    companion object {
        private val objectMapper = ObjectMapper()
    }
}
