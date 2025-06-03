package com.watchcluster.service

import com.watchcluster.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder
import io.fabric8.kubernetes.api.model.ContainerBuilder
import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.api.model.PodListBuilder
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.RollableScalableResource
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeploymentUpdaterTest {
    private lateinit var kubernetesClient: KubernetesClient
    private lateinit var webhookService: WebhookService
    private lateinit var deploymentUpdater: DeploymentUpdater

    @BeforeEach
    fun setup() {
        kubernetesClient = mockk(relaxed = true)
        webhookService = mockk(relaxed = true)
        deploymentUpdater = DeploymentUpdater(kubernetesClient, webhookService)
        
        coEvery { webhookService.sendWebhook(any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        clearMocks(kubernetesClient, webhookService)
    }

    @Test
    fun `should handle latest strategy update with imagePullPolicy manipulation`() = runBlocking {
        val namespace = "test-namespace"
        val deploymentName = "test-deployment"
        val newImage = "myregistry/myapp:latest"
        val expectedDigest = "sha256:1234567890abcdef"
        
        val updateResult = ImageUpdateResult(
            hasUpdate = true,
            currentImage = "myregistry/myapp:latest",
            newImage = newImage,
            currentDigest = "sha256:olddigest",
            newDigest = expectedDigest,
            reason = "Latest image has been updated"
        )

        // Create a mutable deployment that will be modified by the code
        val deployment = DeploymentBuilder()
            .withNewMetadata()
                .withName(deploymentName)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withNewTemplate()
                    .withNewSpec()
                        .withContainers(
                            ContainerBuilder()
                                .withName("app")
                                .withImage("myregistry/myapp:latest")
                                .withImagePullPolicy("IfNotPresent")
                                .build()
                        )
                    .endSpec()
                .endTemplate()
            .endSpec()
            .withNewStatus()
                .withReplicas(1)
                .withUpdatedReplicas(1)
                .withReadyReplicas(1)
                .withAvailableReplicas(1)
            .endStatus()
            .build()

        val deploymentResource = mockk<RollableScalableResource<Deployment>>(relaxed = true)
        val patchedDeployments = mutableListOf<Deployment>()
        
        every { 
            kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName) 
        } returns deploymentResource
        
        every { deploymentResource.get() } answers { 
            // Return a fresh copy each time to avoid shared state issues
            DeploymentBuilder(deployment).build()
        }
        every { deploymentResource.patch(any<Deployment>()) } answers {
            val patched = firstArg<Deployment>()
            // Create a deep copy to capture the state at patch time
            val copy = DeploymentBuilder(patched).build()
            patchedDeployments.add(copy)
            copy
        }
        every { deploymentResource.rolling().restart() } returns deployment

        // Mock pod verification
        val pod = PodBuilder()
            .withNewMetadata()
                .withName("test-pod")
                .addToLabels("app", deploymentName)
            .endMetadata()
            .withNewStatus()
                .withPhase("Running")
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withImageID("docker://myregistry/myapp@$expectedDigest")
                        .build()
                )
            .endStatus()
            .build()

        val podList = PodListBuilder()
            .withItems(pod)
            .build()

        every {
            kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("app", deploymentName)
                .list()
        } returns podList

        // Execute the update with latest strategy
        deploymentUpdater.updateDeployment(namespace, deploymentName, newImage, updateResult, UpdateStrategy.Latest)

        // Verify key operations happened
        verify {
            // Rollout restart was triggered
            deploymentResource.rolling().restart()
        }
        
        // Check the captured patch calls
        assertEquals(3, patchedDeployments.size, "Expected 3 patch calls")
        
        // First patch should set imagePullPolicy to Always
        val firstPatch = patchedDeployments[0]
        assertEquals("Always", firstPatch.spec.template.spec.containers[0].imagePullPolicy)
        assertEquals(newImage, firstPatch.spec.template.spec.containers[0].image)
        
        // Second patch should restore imagePullPolicy to IfNotPresent  
        val secondPatch = patchedDeployments[1]
        assertEquals("IfNotPresent", secondPatch.spec.template.spec.containers[0].imagePullPolicy)
        
        // Third patch is for annotations
        val thirdPatch = patchedDeployments[2]
        assertTrue(thirdPatch.metadata.annotations.containsKey("watch-cluster.io/last-update"))

        // Verify webhook was called
        coVerify { 
            webhookService.sendWebhook(match { 
                it.eventType == WebhookEventType.IMAGE_ROLLOUT_STARTED 
            })
        }
    }

    @Test
    fun `should update normally for non-latest strategy`() = runBlocking {
        val namespace = "test-namespace"
        val deploymentName = "test-deployment"
        val newImage = "myregistry/myapp:v1.2.3"
        
        val deployment = DeploymentBuilder()
            .withNewMetadata()
                .withName(deploymentName)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withNewTemplate()
                    .withNewSpec()
                        .withContainers(
                            ContainerBuilder()
                                .withName("app")
                                .withImage("myregistry/myapp:v1.2.2")
                                .withImagePullPolicy("IfNotPresent")
                                .build()
                        )
                    .endSpec()
                .endTemplate()
            .endSpec()
            .withNewStatus()
                .withReplicas(1)
                .withUpdatedReplicas(1)
                .withReadyReplicas(1)
                .withAvailableReplicas(1)
            .endStatus()
            .build()

        val deploymentResource = mockk<RollableScalableResource<Deployment>>(relaxed = true)
        
        every { 
            kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName) 
        } returns deploymentResource
        
        every { deploymentResource.get() } returns deployment
        every { deploymentResource.patch(any<Deployment>()) } returns deployment

        // Execute the update with version strategy
        deploymentUpdater.updateDeployment(namespace, deploymentName, newImage, null, UpdateStrategy.Version())

        // Verify normal update flow (no special handling)
        verify(atLeast = 1) { 
            deploymentResource.patch(match<Deployment> { 
                it.spec.template.spec.containers[0].image == newImage &&
                it.spec.template.spec.containers[0].imagePullPolicy == "IfNotPresent"
            })
        }
        
        // Verify rollout restart was NOT called
        verify(exactly = 0) { deploymentResource.rolling() }
    }

    @Test
    fun `should throw exception when deployment not found`() = runBlocking {
        val namespace = "test-namespace"
        val deploymentName = "non-existent"
        val newImage = "myregistry/myapp:latest"
        
        val deploymentResource = mockk<RollableScalableResource<Deployment>>(relaxed = true)
        
        every { 
            kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName) 
        } returns deploymentResource
        
        every { deploymentResource.get() } returns null

        val exception = assertThrows<IllegalStateException> {
            deploymentUpdater.updateDeployment(namespace, deploymentName, newImage, null, UpdateStrategy.Version())
        }
        
        assertEquals("Deployment $namespace/$deploymentName not found", exception.message)
        
        // Verify failure webhook was sent
        coVerify { 
            webhookService.sendWebhook(match { 
                it.eventType == WebhookEventType.IMAGE_ROLLOUT_FAILED 
            })
        }
    }

    @Test
    fun `should restore imagePullPolicy on error during latest strategy update`() = runBlocking {
        val namespace = "test-namespace"
        val deploymentName = "test-deployment"
        val newImage = "myregistry/myapp:latest"
        val expectedDigest = "sha256:1234567890abcdef"
        
        val updateResult = ImageUpdateResult(
            hasUpdate = true,
            currentImage = "myregistry/myapp:latest",
            newImage = newImage,
            currentDigest = "sha256:olddigest",
            newDigest = expectedDigest,
            reason = "Latest image has been updated"
        )

        val deployment = DeploymentBuilder()
            .withNewMetadata()
                .withName(deploymentName)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withNewTemplate()
                    .withNewSpec()
                        .withContainers(
                            ContainerBuilder()
                                .withName("app")
                                .withImage("myregistry/myapp:latest")
                                .withImagePullPolicy("IfNotPresent")
                                .build()
                        )
                    .endSpec()
                .endTemplate()
            .endSpec()
            .build()

        val deploymentResource = mockk<RollableScalableResource<Deployment>>(relaxed = true)
        
        every { 
            kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName) 
        } returns deploymentResource
        
        every { deploymentResource.get() } returns deployment
        
        // First patch succeeds
        every { deploymentResource.patch(any<Deployment>()) } returns deployment andThenThrows RuntimeException("Rollout failed")
        
        every { deploymentResource.rolling().restart() } throws RuntimeException("Rollout failed")

        // Execute the update and expect failure
        assertThrows<RuntimeException> {
            deploymentUpdater.updateDeployment(namespace, deploymentName, newImage, updateResult, UpdateStrategy.Latest)
        }

        // Verify attempt to restore original pull policy
        verify(atLeast = 1) { 
            deploymentResource.patch(match<Deployment> { 
                it.spec.template.spec.containers[0].imagePullPolicy == "IfNotPresent"
            })
        }
    }

    @Test
    fun `should verify digest after latest strategy deployment`() = runBlocking {
        val namespace = "test-namespace"
        val deploymentName = "test-deployment"
        val newImage = "myregistry/myapp:latest"
        val expectedDigest = "sha256:1234567890abcdef"
        val wrongDigest = "sha256:wrongdigest"
        
        val updateResult = ImageUpdateResult(
            hasUpdate = true,
            currentImage = "myregistry/myapp:latest",
            newImage = newImage,
            currentDigest = "sha256:olddigest",
            newDigest = expectedDigest,
            reason = "Latest image has been updated"
        )

        val deployment = DeploymentBuilder()
            .withNewMetadata()
                .withName(deploymentName)
                .withNamespace(namespace)
            .endMetadata()
            .withNewSpec()
                .withReplicas(1)
                .withNewTemplate()
                    .withNewSpec()
                        .withContainers(
                            ContainerBuilder()
                                .withName("app")
                                .withImage("myregistry/myapp:latest")
                                .withImagePullPolicy("Always")
                                .build()
                        )
                    .endSpec()
                .endTemplate()
            .endSpec()
            .withNewStatus()
                .withReplicas(1)
                .withUpdatedReplicas(1)
                .withReadyReplicas(1)
                .withAvailableReplicas(1)
            .endStatus()
            .build()

        val deploymentResource = mockk<RollableScalableResource<Deployment>>(relaxed = true)
        
        every { 
            kubernetesClient.apps().deployments()
                .inNamespace(namespace)
                .withName(deploymentName) 
        } returns deploymentResource
        
        every { deploymentResource.get() } returns deployment
        every { deploymentResource.patch(any<Deployment>()) } returns deployment
        every { deploymentResource.rolling().restart() } returns deployment

        // Mock pod with wrong digest
        val pod = PodBuilder()
            .withNewMetadata()
                .withName("test-pod")
                .addToLabels("app", deploymentName)
            .endMetadata()
            .withNewStatus()
                .withPhase("Running")
                .withContainerStatuses(
                    ContainerStatusBuilder()
                        .withImageID("docker://myregistry/myapp@$wrongDigest")
                        .build()
                )
            .endStatus()
            .build()

        val podList = PodListBuilder()
            .withItems(pod)
            .build()

        every {
            kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabel("app", deploymentName)
                .list()
        } returns podList

        // Execute the update with latest strategy
        deploymentUpdater.updateDeployment(namespace, deploymentName, newImage, updateResult, UpdateStrategy.Latest)

        // Verify webhook was called for digest mismatch
        coVerify { 
            webhookService.sendWebhook(match { 
                it.eventType == WebhookEventType.IMAGE_ROLLOUT_COMPLETED &&
                it.details.get("warning") == "Digest mismatch after deployment"
            })
        }
    }
}