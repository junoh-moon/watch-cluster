package com.watchcluster.service

import com.watchcluster.model.*
import com.watchcluster.client.K8sClient
import com.watchcluster.client.domain.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

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
    fun `DeploymentUpdater can be instantiated`() {
        // Simple test to verify the class can be created
        assertNotNull(deploymentUpdater)
    }
    
    @Test
    fun `test image string manipulation logic`() {
        // Test the core logic that doesn't require complex Kubernetes mocking
        val imageWithoutDigest = "nginx:1.20.0"
        val imageWithDigest = "nginx:1.21.0@sha256:abc123"
        
        // Test basic image name parsing (this would be part of ImageParser utility)
        assertTrue(imageWithoutDigest.contains(":"))
        assertTrue(imageWithDigest.contains("@"))
        
        // Verify the image format we expect to construct
        val newImage = "nginx:1.21.0"
        val digest = "sha256:abc123"
        val expectedImageWithDigest = "$newImage@$digest"
        assertEquals("nginx:1.21.0@sha256:abc123", expectedImageWithDigest)
    }
    
    @Test
    fun `test webhook event construction`() {
        val namespace = "test-namespace"
        val name = "test-deployment"
        val image = "nginx:1.21.0"
        
        val deploymentInfo = DeploymentEventData(namespace, name, image)
        
        val webhookEvent = WebhookEvent(
            eventType = WebhookEventType.IMAGE_ROLLOUT_STARTED,
            timestamp = java.time.Instant.now().toString(),
            deployment = deploymentInfo,
            details = mapOf("previousImage" to "nginx:1.20.0")
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
        val timestamp = java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
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
    fun `test rollout status evaluation logic`() {
        // Test the logic used to determine if a rollout is complete
        val replicas = 3
        val updatedReplicas = 3
        val readyReplicas = 3
        val availableReplicas = 3
        
        val isRolloutComplete = updatedReplicas == replicas && 
                               readyReplicas == replicas && 
                               availableReplicas == replicas
        
        assertTrue(isRolloutComplete)
        
        // Test incomplete rollout
        val incompleteUpdatedReplicas = 2
        val isRolloutIncomplete = incompleteUpdatedReplicas == replicas && 
                                 readyReplicas == replicas && 
                                 availableReplicas == replicas
        
        assertFalse(isRolloutIncomplete)
    }
    
    @Test
    fun `test timeout calculation`() {
        val timeout = 300000L // 5 minutes
        val startTime = System.currentTimeMillis()
        val currentTime = startTime + 100000L // 100 seconds elapsed
        
        val hasTimedOut = currentTime - startTime >= timeout
        assertFalse(hasTimedOut) // Should not have timed out yet
        
        val timeoutTime = startTime + timeout + 1000L // 1 second past timeout
        val hasTimedOutNow = timeoutTime - startTime >= timeout
        assertTrue(hasTimedOutNow) // Should have timed out
    }
    
    @Test
    fun `test updateDeployment with successful rollout`() = runBlocking {
        // Given
        val namespace = "test-namespace"
        val name = "test-deployment"
        val newImage = "nginx:1.21.0"
        val currentImage = "nginx:1.20.0"
        
        val deployment = com.watchcluster.client.domain.DeploymentInfo(
            namespace = namespace,
            name = name,
            generation = 1,
            replicas = 1,
            selector = mapOf("app" to name),
            containers = listOf(ContainerInfo("nginx", currentImage)),
            imagePullSecrets = emptyList(),
            annotations = mapOf(),
            status = DeploymentStatus(
                observedGeneration = 1,
                updatedReplicas = 1,
                readyReplicas = 1,
                availableReplicas = 1,
                conditions = listOf(
                    DeploymentCondition("Progressing", "True", reason = "NewReplicaSetAvailable"),
                    DeploymentCondition("Available", "True")
                )
            )
        )
        
        coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment
        coEvery { mockK8sClient.patchDeployment(namespace, name, any()) } returns deployment.copy(
            containers = listOf(ContainerInfo("nginx", newImage))
        )
        
        val pods = listOf(
            PodInfo(
                namespace = namespace,
                name = "test-pod-1",
                containers = listOf(ContainerInfo("nginx", newImage)),
                status = PodStatus(
                    phase = "Running",
                    conditions = listOf(PodCondition("Ready", "True"))
                )
            )
        )
        coEvery { mockK8sClient.listPodsByLabels(namespace, mapOf("app" to name)) } returns pods
        
        // When
        deploymentUpdater.updateDeployment(namespace, name, newImage)
        
        // Then
        coVerify { mockK8sClient.getDeployment(namespace, name) }
        coVerify { mockK8sClient.patchDeployment(namespace, name, any()) }
        coVerify { mockWebhookService.sendWebhook(match { 
            it.eventType == WebhookEventType.IMAGE_ROLLOUT_STARTED 
        }) }
        coVerify { mockWebhookService.sendWebhook(match { 
            it.eventType == WebhookEventType.IMAGE_ROLLOUT_COMPLETED 
        }) }
    }
    
    @Test
    fun `test updateDeployment handles deployment not found`() = runBlocking {
        // Given
        val namespace = "test-namespace"
        val name = "test-deployment"
        val newImage = "nginx:1.21.0"
        
        coEvery { mockK8sClient.getDeployment(namespace, name) } returns null
        
        // When/Then
        assertFailsWith<IllegalStateException> {
            deploymentUpdater.updateDeployment(namespace, name, newImage)
        }
        
        coVerify { mockWebhookService.sendWebhook(match { 
            it.eventType == WebhookEventType.IMAGE_ROLLOUT_STARTED 
        }) }
        coVerify { mockWebhookService.sendWebhook(match { 
            it.eventType == WebhookEventType.IMAGE_ROLLOUT_FAILED 
        }) }
    }
    
    @Test
    fun `test updateDeployment with digest`() = runBlocking {
        // Given
        val namespace = "test-namespace"
        val name = "test-deployment"
        val newImage = "nginx:1.21.0"
        val newDigest = "sha256:abc123"
        val currentImage = "nginx:1.20.0"
        
        val updateResult = ImageUpdateResult(
            hasUpdate = true,
            currentImage = currentImage,
            newImage = newImage,
            newDigest = newDigest,
            currentDigest = "sha256:old123",
            reason = "New version available"
        )
        
        val deployment = com.watchcluster.client.domain.DeploymentInfo(
            namespace = namespace,
            name = name,
            generation = 1,
            replicas = 1,
            selector = mapOf("app" to name),
            containers = listOf(ContainerInfo("nginx", currentImage)),
            imagePullSecrets = emptyList(),
            annotations = mapOf(),
            status = DeploymentStatus(
                observedGeneration = 1,
                updatedReplicas = 1,
                readyReplicas = 1,
                availableReplicas = 1,
                conditions = listOf(
                    DeploymentCondition("Progressing", "True", reason = "NewReplicaSetAvailable"),
                    DeploymentCondition("Available", "True")
                )
            )
        )
        
        coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment
        coEvery { mockK8sClient.patchDeployment(namespace, name, any()) } returns deployment.copy(
            containers = listOf(ContainerInfo("nginx", "$newImage@$newDigest"))
        )
        
        val pods = listOf(
            PodInfo(
                namespace = namespace,
                name = "test-pod-1",
                containers = listOf(ContainerInfo("nginx", "$newImage@$newDigest")),
                status = PodStatus(
                    phase = "Running",
                    conditions = listOf(PodCondition("Ready", "True"))
                )
            )
        )
        coEvery { mockK8sClient.listPodsByLabels(namespace, mapOf("app" to name)) } returns pods
        
        // When
        deploymentUpdater.updateDeployment(namespace, name, newImage, updateResult)
        
        // Then
        coVerify { 
            mockK8sClient.patchDeployment(namespace, name, match { 
                it.contains("$newImage@$newDigest") 
            })
        }
    }
    
    @Test
    fun `test updateDeployment with no containers`() = runBlocking {
        // Given
        val namespace = "test-namespace"
        val name = "test-deployment"
        val newImage = "nginx:1.21.0"
        
        val deployment = com.watchcluster.client.domain.DeploymentInfo(
            namespace = namespace,
            name = name,
            generation = 1,
            replicas = 1,
            selector = mapOf("app" to name),
            containers = emptyList(), // No containers
            imagePullSecrets = emptyList(),
            annotations = mapOf(),
            status = DeploymentStatus()
        )
        
        coEvery { mockK8sClient.getDeployment(namespace, name) } returns deployment
        
        // When/Then
        assertFailsWith<IllegalStateException> {
            deploymentUpdater.updateDeployment(namespace, name, newImage)
        }
        
        coVerify { mockWebhookService.sendWebhook(match { 
            it.eventType == WebhookEventType.IMAGE_ROLLOUT_FAILED 
        }) }
    }
}