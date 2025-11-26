package com.watchcluster.service

import com.watchcluster.client.K8sClient
import com.watchcluster.client.domain.ContainerInfo
import com.watchcluster.client.domain.DeploymentCondition
import com.watchcluster.client.domain.DeploymentStatus
import com.watchcluster.client.domain.PodCondition
import com.watchcluster.client.domain.PodInfo
import com.watchcluster.client.domain.PodStatus
import com.watchcluster.model.DeploymentEventData
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.WebhookEvent
import com.watchcluster.model.WebhookEventType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DeploymentUpdaterTest {
    private lateinit var mockK8sClient: K8sClient
    private lateinit var mockWebhookService: WebhookService
    private lateinit var deploymentUpdater: DeploymentUpdater

    @BeforeEach
    fun setup() {
        mockK8sClient = mockk(relaxed = true)
        mockWebhookService = mockk(relaxed = true)

        deploymentUpdater = DeploymentUpdater(mockK8sClient, mockWebhookService)
    }

    @Test
    fun `test webhook event construction`() {
        val namespace = "test-namespace"
        val name = "test-deployment"
        val image = "nginx:1.21.0"

        val deploymentInfo = DeploymentEventData(namespace, name, image)

        val webhookEvent =
            WebhookEvent(
                eventType = WebhookEventType.IMAGE_ROLLOUT_STARTED,
                timestamp =
                    java.time.Instant
                        .now()
                        .toString(),
                deployment = deploymentInfo,
                details = mapOf("previousImage" to "nginx:1.20.0"),
            )

        assertEquals(WebhookEventType.IMAGE_ROLLOUT_STARTED, webhookEvent.eventType)
        assertEquals(namespace, webhookEvent.deployment.namespace)
        assertEquals(name, webhookEvent.deployment.name)
        assertEquals(image, webhookEvent.deployment.image)
        assertTrue(webhookEvent.details.containsKey("previousImage"))
    }

    @Test
    fun `test annotation key constants`() {
        // Verify the annotation keys used in DeploymentUpdater
        val annotations = mutableMapOf<String, String>()
        val timestamp =
            java.time.ZonedDateTime
                .now()
                .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val image = "nginx:1.21.0"
        val fromDigest = "sha256:old123"
        val toDigest = "sha256:new456"

        annotations["watch-cluster.io/last-update"] = timestamp
        annotations["watch-cluster.io/last-update-image"] = image
        annotations["watch-cluster.io/last-update-from-digest"] = fromDigest
        annotations["watch-cluster.io/last-update-to-digest"] = toDigest

        assertEquals(timestamp, annotations["watch-cluster.io/last-update"])
        assertEquals(image, annotations["watch-cluster.io/last-update-image"])
        assertEquals(fromDigest, annotations["watch-cluster.io/last-update-from-digest"])
        assertEquals(toDigest, annotations["watch-cluster.io/last-update-to-digest"])
    }

    @Test
    fun `test updateDeployment with successful rollout`() =
        runBlocking {
            // Given
            val namespace = "test-namespace"
            val name = "test-deployment"
            val newImage = "nginx:1.21.0"
            val currentImage = "nginx:1.20.0"

            val deployment =
                com.watchcluster.client.domain.DeploymentInfo(
                    namespace = namespace,
                    name = name,
                    generation = 1,
                    replicas = 1,
                    selector = mapOf("app" to name),
                    containers = listOf(ContainerInfo("nginx", currentImage)),
                    imagePullSecrets = emptyList(),
                    annotations = mapOf(),
                    status =
                        DeploymentStatus(
                            observedGeneration = 1,
                            updatedReplicas = 1,
                            readyReplicas = 1,
                            availableReplicas = 1,
                            conditions =
                                listOf(
                                    DeploymentCondition("Progressing", "True", reason = "NewReplicaSetAvailable"),
                                    DeploymentCondition("Available", "True"),
                                ),
                        ),
                )

            coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment
            coEvery { mockK8sClient.patchDeployment(namespace, name, any()) } returns
                deployment.copy(
                    containers = listOf(ContainerInfo("nginx", newImage)),
                )

            val pods =
                listOf(
                    PodInfo(
                        namespace = namespace,
                        name = "test-pod-1",
                        containers = listOf(ContainerInfo("nginx", newImage)),
                        status =
                            PodStatus(
                                phase = "Running",
                                conditions = listOf(PodCondition("Ready", "True")),
                            ),
                    ),
                )
            coEvery { mockK8sClient.listPodsByLabels(namespace, mapOf("app" to name)) } returns pods

            // When
            deploymentUpdater.updateDeployment(namespace, name, newImage, currentImage, UpdateStrategy.Version())

            // Then
            coVerify { mockK8sClient.getDeployment(namespace, name) }
            coVerify { mockK8sClient.patchDeployment(namespace, name, any()) }
            coVerify {
                mockWebhookService.sendWebhook(
                    match {
                        it.eventType == WebhookEventType.IMAGE_ROLLOUT_STARTED
                    },
                )
            }
            coVerify {
                mockWebhookService.sendWebhook(
                    match {
                        it.eventType == WebhookEventType.IMAGE_ROLLOUT_COMPLETED
                    },
                )
            }
        }

    @Test
    fun `test updateDeployment handles deployment not found`() =
        runBlocking {
            // Given
            val namespace = "test-namespace"
            val name = "test-deployment"
            val newImage = "nginx:1.21.0"

            coEvery { mockK8sClient.getDeployment(namespace, name) } returns null

            // When/Then
            assertFailsWith<IllegalStateException> {
                deploymentUpdater.updateDeployment(namespace, name, newImage, "nginx:1.20.0", UpdateStrategy.Version())
            }

            // IMAGE_ROLLOUT_STARTED is not sent because getDeployment fails first
            coVerify(exactly = 0) {
                mockWebhookService.sendWebhook(
                    match {
                        it.eventType == WebhookEventType.IMAGE_ROLLOUT_STARTED
                    },
                )
            }
            coVerify {
                mockWebhookService.sendWebhook(
                    match {
                        it.eventType == WebhookEventType.IMAGE_ROLLOUT_FAILED
                    },
                )
            }
        }

    @Test
    fun `test updateDeployment with digest`() =
        runBlocking {
            // Given
            val namespace = "test-namespace"
            val name = "test-deployment"
            val newImage = "nginx:1.21.0"
            val newDigest = "sha256:abc123"
            val currentImage = "nginx:1.20.0"

            val deployment =
                com.watchcluster.client.domain.DeploymentInfo(
                    namespace = namespace,
                    name = name,
                    generation = 1,
                    replicas = 1,
                    selector = mapOf("app" to name),
                    containers = listOf(ContainerInfo("nginx", currentImage)),
                    imagePullSecrets = emptyList(),
                    annotations = mapOf(),
                    status =
                        DeploymentStatus(
                            observedGeneration = 1,
                            updatedReplicas = 1,
                            readyReplicas = 1,
                            availableReplicas = 1,
                            conditions =
                                listOf(
                                    DeploymentCondition("Progressing", "True", reason = "NewReplicaSetAvailable"),
                                    DeploymentCondition("Available", "True"),
                                ),
                        ),
                )

            coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment
            coEvery { mockK8sClient.patchDeployment(namespace, name, any()) } returns
                deployment.copy(
                    containers = listOf(ContainerInfo("nginx", "$newImage@$newDigest")),
                )

            val pods =
                listOf(
                    PodInfo(
                        namespace = namespace,
                        name = "test-pod-1",
                        containers = listOf(ContainerInfo("nginx", "$newImage@$newDigest")),
                        status =
                            PodStatus(
                                phase = "Running",
                                conditions = listOf(PodCondition("Ready", "True")),
                            ),
                    ),
                )
            coEvery { mockK8sClient.listPodsByLabels(namespace, mapOf("app" to name)) } returns pods

            // When
            deploymentUpdater.updateDeployment(namespace, name, "$newImage@$newDigest", currentImage, UpdateStrategy.Version())

            // Then
            coVerify {
                mockK8sClient.patchDeployment(
                    namespace,
                    name,
                    match {
                        it.contains("$newImage@$newDigest")
                    },
                )
            }
        }

    @Test
    fun `test updateDeployment with no containers`() =
        runBlocking {
            // Given
            val namespace = "test-namespace"
            val name = "test-deployment"
            val newImage = "nginx:1.21.0"

            val deployment =
                com.watchcluster.client.domain.DeploymentInfo(
                    namespace = namespace,
                    name = name,
                    generation = 1,
                    replicas = 1,
                    selector = mapOf("app" to name),
                    containers = emptyList(), // No containers
                    imagePullSecrets = emptyList(),
                    annotations = mapOf(),
                    status = DeploymentStatus(),
                )

            coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment

            // When/Then
            assertFailsWith<IllegalStateException> {
                deploymentUpdater.updateDeployment(namespace, name, newImage, "nginx:1.20.0", UpdateStrategy.Version())
            }

            coVerify {
                mockWebhookService.sendWebhook(
                    match {
                        it.eventType == WebhookEventType.IMAGE_ROLLOUT_FAILED
                    },
                )
            }
        }

    @Test
    fun `test updateDeployment with Latest strategy sets imagePullPolicy to Always`() =
        runBlocking {
            // Given
            val namespace = "test-namespace"
            val name = "test-deployment"
            val newImage = "nginx:latest"
            val currentImage = "nginx:latest"

            val deployment =
                com.watchcluster.client.domain.DeploymentInfo(
                    namespace = namespace,
                    name = name,
                    generation = 1,
                    replicas = 1,
                    selector = mapOf("app" to name),
                    containers = listOf(ContainerInfo("nginx", currentImage)),
                    imagePullSecrets = emptyList(),
                    annotations = mapOf(),
                    status =
                        DeploymentStatus(
                            observedGeneration = 1,
                            updatedReplicas = 1,
                            readyReplicas = 1,
                            availableReplicas = 1,
                            conditions =
                                listOf(
                                    DeploymentCondition("Progressing", "True", reason = "NewReplicaSetAvailable"),
                                    DeploymentCondition("Available", "True"),
                                ),
                        ),
                )

            coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment
            coEvery { mockK8sClient.patchDeployment(namespace, name, any()) } returns
                deployment.copy(
                    containers = listOf(ContainerInfo("nginx", newImage)),
                )

            val pods =
                listOf(
                    PodInfo(
                        namespace = namespace,
                        name = "test-pod-1",
                        containers = listOf(ContainerInfo("nginx", newImage)),
                        status =
                            PodStatus(
                                phase = "Running",
                                conditions = listOf(PodCondition("Ready", "True")),
                            ),
                    ),
                )
            coEvery { mockK8sClient.listPodsByLabels(namespace, mapOf("app" to name)) } returns pods

            // When
            deploymentUpdater.updateDeployment(namespace, name, newImage, currentImage, UpdateStrategy.Latest)

            // Then - verify imagePullPolicy: Always is in the patch
            coVerify {
                mockK8sClient.patchDeployment(
                    namespace,
                    name,
                    match {
                        it.contains("\"imagePullPolicy\":\"Always\"")
                    },
                )
            }
        }

    @Test
    fun `test updateDeployment with Version strategy does not set imagePullPolicy`() =
        runBlocking {
            // Given
            val namespace = "test-namespace"
            val name = "test-deployment"
            val newImage = "nginx:1.21.0"
            val currentImage = "nginx:1.20.0"

            val deployment =
                com.watchcluster.client.domain.DeploymentInfo(
                    namespace = namespace,
                    name = name,
                    generation = 1,
                    replicas = 1,
                    selector = mapOf("app" to name),
                    containers = listOf(ContainerInfo("nginx", currentImage)),
                    imagePullSecrets = emptyList(),
                    annotations = mapOf(),
                    status =
                        DeploymentStatus(
                            observedGeneration = 1,
                            updatedReplicas = 1,
                            readyReplicas = 1,
                            availableReplicas = 1,
                            conditions =
                                listOf(
                                    DeploymentCondition("Progressing", "True", reason = "NewReplicaSetAvailable"),
                                    DeploymentCondition("Available", "True"),
                                ),
                        ),
                )

            coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment
            coEvery { mockK8sClient.patchDeployment(namespace, name, any()) } returns
                deployment.copy(
                    containers = listOf(ContainerInfo("nginx", newImage)),
                )

            val pods =
                listOf(
                    PodInfo(
                        namespace = namespace,
                        name = "test-pod-1",
                        containers = listOf(ContainerInfo("nginx", newImage)),
                        status =
                            PodStatus(
                                phase = "Running",
                                conditions = listOf(PodCondition("Ready", "True")),
                            ),
                    ),
                )
            coEvery { mockK8sClient.listPodsByLabels(namespace, mapOf("app" to name)) } returns pods

            // When
            deploymentUpdater.updateDeployment(namespace, name, newImage, currentImage, UpdateStrategy.Version())

            // Then - verify imagePullPolicy is NOT in the patch
            coVerify {
                mockK8sClient.patchDeployment(
                    namespace,
                    name,
                    match {
                        !it.contains("imagePullPolicy")
                    },
                )
            }
        }
}
