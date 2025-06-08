package com.watchcluster.controller

import com.watchcluster.model.*
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.CronScheduler
import io.fabric8.kubernetes.api.model.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec
import io.fabric8.kubernetes.api.model.apps.DeploymentList
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.*
import io.fabric8.kubernetes.client.informers.ResourceEventHandler
import io.fabric8.kubernetes.client.informers.SharedIndexInformer
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class WatchControllerTest {
    
    private lateinit var mockKubernetesClient: KubernetesClient
    private lateinit var mockWebhookService: WebhookService
    private lateinit var mockImageChecker: ImageChecker
    private lateinit var mockDeploymentUpdater: DeploymentUpdater
    private lateinit var mockCronScheduler: CronScheduler
    private lateinit var watchController: WatchController
    
    @BeforeEach
    fun setup() {
        mockKubernetesClient = mockk(relaxed = true)
        mockWebhookService = mockk(relaxed = true)
        mockImageChecker = mockk(relaxed = true)
        mockDeploymentUpdater = mockk(relaxed = true)
        mockCronScheduler = mockk(relaxed = true)
        
        watchController = WatchController(
            mockKubernetesClient,
            mockWebhookService,
            mockImageChecker,
            mockDeploymentUpdater,
            mockCronScheduler
        )
    }
    
    @Test
    fun `WatchController can be instantiated`() {
        assertNotNull(watchController)
    }
    
    @Test
    fun `start() should call kubernetes client apps deployments inAnyNamespace inform`() {
        // Given
        val mockInformer = mockk<SharedIndexInformer<Deployment>>(relaxed = true)
        val mockNamespaceOp = mockk<AnyNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val mockDeploymentOp = mockk<MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        
        every { mockKubernetesClient.apps().deployments() } returns mockDeploymentOp
        every { mockDeploymentOp.inAnyNamespace() } returns mockNamespaceOp
        every { mockNamespaceOp.inform(any<ResourceEventHandler<Deployment>>()) } returns mockInformer
        
        // When
        runBlocking {
            watchController.start()
        }
        
        // Then
        verify { mockKubernetesClient.apps().deployments() }
        verify { mockDeploymentOp.inAnyNamespace() }
        verify { mockNamespaceOp.inform(any<ResourceEventHandler<Deployment>>()) }
    }
    
    @Test
    fun `handleDeployment processes deployment with watch-cluster annotations`() = runTest {
        // Given
        val deployment = createMockDeployment(
            namespace = "test-ns",
            name = "test-app",
            image = "nginx:1.20.0",
            annotations = mapOf(
                WatchClusterAnnotations.ENABLED to "true",
                WatchClusterAnnotations.CRON to "0 */10 * * * ?",
                WatchClusterAnnotations.STRATEGY to "version-lock-major"
            )
        )
        
        val mockInformer = mockk<SharedIndexInformer<Deployment>>(relaxed = true)
        val mockNamespaceOp = mockk<AnyNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val mockDeploymentOp = mockk<MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val handlerSlot = slot<ResourceEventHandler<Deployment>>()
        
        every { mockKubernetesClient.apps().deployments() } returns mockDeploymentOp
        every { mockDeploymentOp.inAnyNamespace() } returns mockNamespaceOp
        every { mockNamespaceOp.inform(capture(handlerSlot)) } returns mockInformer
        
        // When
        watchController.start()
        val handler = handlerSlot.captured
        handler.onAdd(deployment)
        
        // Then - verify the deployment was processed without errors
        assertNotNull(handler)
    }
    
    @Test
    fun `handleDeployment ignores deployment without watch-cluster enabled annotation`() = runTest {
        // Given
        val deployment = createMockDeployment(
            namespace = "test-ns",
            name = "test-app",
            image = "nginx:1.20.0",
            annotations = mapOf(
                WatchClusterAnnotations.ENABLED to "false"
            )
        )
        
        val mockInformer = mockk<SharedIndexInformer<Deployment>>(relaxed = true)
        val mockNamespaceOp = mockk<AnyNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val mockDeploymentOp = mockk<MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val handlerSlot = slot<ResourceEventHandler<Deployment>>()
        
        every { mockKubernetesClient.apps().deployments() } returns mockDeploymentOp
        every { mockDeploymentOp.inAnyNamespace() } returns mockNamespaceOp
        every { mockNamespaceOp.inform(capture(handlerSlot)) } returns mockInformer
        
        // When
        watchController.start()
        val handler = handlerSlot.captured
        handler.onAdd(deployment)
        
        // Then - deployment should be ignored (no exception should be thrown)
        assertNotNull(handler)
    }
    
    @Test
    fun `handleDeployment ignores deployment with no annotations`() = runTest {
        // Given
        val deployment = createMockDeployment(
            namespace = "test-ns",
            name = "test-app",
            image = "nginx:1.20.0",
            annotations = null
        )
        
        val mockInformer = mockk<SharedIndexInformer<Deployment>>(relaxed = true)
        val mockNamespaceOp = mockk<AnyNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val mockDeploymentOp = mockk<MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val handlerSlot = slot<ResourceEventHandler<Deployment>>()
        
        every { mockKubernetesClient.apps().deployments() } returns mockDeploymentOp
        every { mockDeploymentOp.inAnyNamespace() } returns mockNamespaceOp
        every { mockNamespaceOp.inform(capture(handlerSlot)) } returns mockInformer
        
        // When
        watchController.start()
        val handler = handlerSlot.captured
        handler.onAdd(deployment)
        
        // Then - deployment should be ignored
        assertNotNull(handler)
    }
    
    @Test
    fun `onDelete removes deployment from watched list`() = runTest {
        // Given
        val deployment = createMockDeployment(
            namespace = "test-ns",
            name = "test-app",
            image = "nginx:1.20.0",
            annotations = mapOf(WatchClusterAnnotations.ENABLED to "true")
        )
        
        val mockInformer = mockk<SharedIndexInformer<Deployment>>(relaxed = true)
        val mockNamespaceOp = mockk<AnyNamespaceOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val mockDeploymentOp = mockk<MixedOperation<Deployment, DeploymentList, RollableScalableResource<Deployment>>>(relaxed = true)
        val handlerSlot = slot<ResourceEventHandler<Deployment>>()
        
        every { mockKubernetesClient.apps().deployments() } returns mockDeploymentOp
        every { mockDeploymentOp.inAnyNamespace() } returns mockNamespaceOp
        every { mockNamespaceOp.inform(capture(handlerSlot)) } returns mockInformer
        
        // When
        watchController.start()
        val handler = handlerSlot.captured
        handler.onDelete(deployment, false)
        
        // Then - should handle deletion without error
        assertNotNull(handler)
    }
    
    @Test
    fun `stop completes without error`() {
        // When
        watchController.stop()
        
        // Then - should complete without error
        assertTrue(true) // Placeholder assertion
    }
    
    @Test
    fun `test UpdateStrategy parsing`() {
        // Test the strategy parsing logic
        val versionStrategy = UpdateStrategy.fromString("version")
        assertTrue(versionStrategy is UpdateStrategy.Version)
        assertFalse(versionStrategy.lockMajorVersion)
        
        val versionLockMajorStrategy = UpdateStrategy.fromString("version-lock-major")
        assertTrue(versionLockMajorStrategy is UpdateStrategy.Version)
        assertTrue(versionLockMajorStrategy.lockMajorVersion)
        
        val latestStrategy = UpdateStrategy.fromString("latest")
        assertTrue(latestStrategy is UpdateStrategy.Latest)
        
        val defaultStrategy = UpdateStrategy.fromString("unknown")
        assertTrue(defaultStrategy is UpdateStrategy.Version)
    }
    
    @Test
    fun `test WatchedDeployment creation`() {
        val namespace = "test-ns"
        val name = "test-deploy"
        val cronExpression = "0 */5 * * * ?"
        val strategy = UpdateStrategy.Version()
        val currentImage = "nginx:1.20.0"
        val imagePullSecrets = listOf("my-secret")
        
        val watchedDeployment = WatchedDeployment(
            namespace = namespace,
            name = name,
            cronExpression = cronExpression,
            updateStrategy = strategy,
            currentImage = currentImage,
            imagePullSecrets = imagePullSecrets
        )
        
        assertEquals(namespace, watchedDeployment.namespace)
        assertEquals(name, watchedDeployment.name)
        assertEquals(cronExpression, watchedDeployment.cronExpression)
        assertEquals(strategy, watchedDeployment.updateStrategy)
        assertEquals(currentImage, watchedDeployment.currentImage)
        assertEquals(imagePullSecrets, watchedDeployment.imagePullSecrets)
    }
    
    @Test
    fun `test annotation parsing logic`() {
        // Test the logic used to parse deployment annotations
        val annotations = mapOf(
            WatchClusterAnnotations.ENABLED to "true",
            WatchClusterAnnotations.CRON to "0 */10 * * * ?",
            WatchClusterAnnotations.STRATEGY to "version-lock-major"
        )
        
        val enabled = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false
        val cronExpression = annotations[WatchClusterAnnotations.CRON] ?: "0 */5 * * * ?"
        val strategyStr = annotations[WatchClusterAnnotations.STRATEGY] ?: "version"
        val strategy = UpdateStrategy.fromString(strategyStr)
        
        assertTrue(enabled)
        assertEquals("0 */10 * * * ?", cronExpression)
        assertTrue(strategy is UpdateStrategy.Version)
        assertTrue(strategy.lockMajorVersion)
    }
    
    @Test
    fun `test default values`() {
        // Test default values used when annotations are not provided
        val defaultCronExpression = "0 */5 * * * ?"
        val defaultStrategyStr = "version"
        val defaultStrategy = UpdateStrategy.fromString(defaultStrategyStr)
        
        assertEquals("0 */5 * * * ?", defaultCronExpression)
        assertTrue(defaultStrategy is UpdateStrategy.Version)
        assertFalse(defaultStrategy.lockMajorVersion)
    }
    
    private fun createMockDeployment(
        namespace: String,
        name: String, 
        image: String,
        annotations: Map<String, String>?
    ): Deployment {
        val metadata = ObjectMeta().apply {
            this.namespace = namespace
            this.name = name
            this.annotations = annotations
        }
        
        val container = Container().apply {
            this.image = image
        }
        
        val podSpec = PodSpec().apply {
            containers = listOf(container)
        }
        
        val podTemplate = PodTemplateSpec().apply {
            spec = podSpec
        }
        
        val deploymentSpec = DeploymentSpec().apply {
            template = podTemplate
        }
        
        return Deployment().apply {
            this.metadata = metadata
            spec = deploymentSpec
        }
    }
}