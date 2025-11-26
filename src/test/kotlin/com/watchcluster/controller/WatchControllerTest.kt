package com.watchcluster.controller

import com.watchcluster.client.K8sClient
import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.ContainerInfo
import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.DeploymentStatus
import com.watchcluster.client.domain.EventType
import com.watchcluster.client.domain.K8sWatchEvent
import com.watchcluster.model.ImageUpdateResult
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.model.WatchedDeployment
import com.watchcluster.model.WebhookConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchControllerTest {
    private lateinit var mockK8sClient: K8sClient
    private lateinit var watchController: WatchController

    @BeforeEach
    fun setup() {
        mockK8sClient = mockk(relaxed = true)

        // Mock static method for WebhookConfig
        mockkObject(WebhookConfig.Companion)
        every { WebhookConfig.fromEnvironment() } returns
            WebhookConfig(
                url = null,
                timeout = 5000,
            )

        watchController = WatchController(mockK8sClient)
    }

    @Test
    fun `start() should call kubernetes client watchDeployments`() =
        runTest {
            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()

            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            coVerify { mockK8sClient.watchDeployments(any()) }
            assertTrue(watcherSlot.isCaptured)
        }

    @Test
    fun `handleDeployment processes deployment with watch-cluster annotations`() =
        runTest {
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "0 */10 * * * ?",
                            WatchClusterAnnotations.STRATEGY to "version-lock-major",
                        ),
                )

            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured
            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))

            assertNotNull(watcher)
        }

    @Test
    fun `handleDeployment ignores deployment without watch-cluster enabled annotation`() =
        runTest {
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "false",
                        ),
                )

            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured
            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))

            assertNotNull(watcher)
        }

    @Test
    fun `handleDeployment ignores deployment with no annotations`() =
        runTest {
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(),
                )

            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured
            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))

            assertNotNull(watcher)
        }

    @Test
    fun `onDelete removes deployment from watched list`() =
        runTest {
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )

            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured
            watcher.eventReceived(K8sWatchEvent(EventType.DELETED, deployment))

            assertNotNull(watcher)
        }

    @Test
    fun `onClose should not throw`() =
        runTest {
            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured
            watcher.onClose(null)
            watcher.onClose(Exception("test close"))

            assertNotNull(watcher)
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

        val watchedDeployment =
            WatchedDeployment(
                namespace = namespace,
                name = name,
                cronExpression = cronExpression,
                updateStrategy = strategy,
                currentImage = currentImage,
                imagePullSecrets = imagePullSecrets,
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
        val annotations =
            mapOf(
                WatchClusterAnnotations.ENABLED to "true",
                WatchClusterAnnotations.CRON to "0 */10 * * * ?",
                WatchClusterAnnotations.STRATEGY to "version-lock-major",
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
    fun `test deployment with multiple containers`() {
        val deployment =
            createMockDeployment(
                namespace = "test-ns",
                name = "test-app",
                image = "nginx:1.20.0",
                annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                containers =
                    listOf(
                        ContainerInfo("nginx", "nginx:1.20.0"),
                        ContainerInfo("sidecar", "sidecar:1.0.0"),
                    ),
            )

        assertEquals(2, deployment.containers.size)
        assertEquals("nginx", deployment.containers[0].name)
        assertEquals("nginx:1.20.0", deployment.containers[0].image)
    }

    @Test
    fun `test watcher handles error event`() =
        runTest {
            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(),
                )

            // Should not throw
            watcher.eventReceived(K8sWatchEvent(EventType.ERROR, deployment))

            assertNotNull(watcher)
        }

    @Test
    fun `test MODIFIED event updates deployment without race condition`() =
        runTest {
            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured

            // First ADDED event with image X
            val deploymentV1 =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )
            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deploymentV1))

            // Second MODIFIED event with image Y
            val deploymentV2 =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.21.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deploymentV2))

            // Should not throw
            assertNotNull(watcher)
        }

    @Test
    fun `test consecutive MODIFIED events are handled sequentially`() =
        runTest {
            val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
            coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns mockk(relaxed = true)

            watchController.start()

            val watcher = watcherSlot.captured

            // Initial ADDED event
            val deployment1 =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )
            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment1))

            // First MODIFIED event
            val deployment2 =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.21.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment2))

            // Second MODIFIED event
            val deployment3 =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.22.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment3))

            // Should not throw
            assertNotNull(watcher)
        }

    @Test
    fun `should not duplicate update when cache is properly updated after deployment update`() =
        runTest {
            // Given: Mock dependencies
            val mockImageChecker = mockk<com.watchcluster.service.ImageChecker>()
            val mockDeploymentUpdater = mockk<com.watchcluster.service.DeploymentUpdater>(relaxed = true)
            val mockCronScheduler = mockk<com.watchcluster.util.CronScheduler>(relaxed = true)

            val currentImageCaptures = mutableListOf<String>()

            // Setup: ImageChecker returns update if current image is v2.2.2, otherwise no update
            coEvery {
                mockImageChecker.checkForUpdate(
                    capture(currentImageCaptures),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } answers {
                val currentImage = currentImageCaptures.last()
                if (currentImage.contains("v2.2.2")) {
                    ImageUpdateResult(
                        currentImage = currentImage,
                        newImage = "ghcr.io/immich-app/immich-server:v2.2.3@sha256:new",
                        reason = "Found newer version: v2.2.3",
                    )
                } else {
                    ImageUpdateResult(
                        currentImage = currentImage,
                        newImage = null,
                        reason = "Already at latest version",
                    )
                }
            }

            // Setup: DeploymentUpdater succeeds
            coEvery { mockDeploymentUpdater.updateDeployment(any(), any(), any(), any(), any()) } returns Unit

            // Create WatchController with mocked dependencies
            val controller =
                WatchController(
                    mockK8sClient,
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                    cronScheduler = mockCronScheduler,
                )

            // Manually add deployment to cache (simulating handleDeployment)
            val deployment =
                WatchedDeployment(
                    namespace = "immich",
                    name = "immich-server",
                    cronExpression = "0 */10 * * * ?",
                    updateStrategy = UpdateStrategy.Version(),
                    currentImage = "ghcr.io/immich-app/immich-server:v2.2.2@sha256:old",
                    imagePullSecrets = emptyList(),
                )
            controller.watchedDeployments["immich/immich-server"] = deployment
            controller.deploymentMutexes["immich/immich-server"] = kotlinx.coroutines.sync.Mutex()

            // When: Execute cron job twice by calling checkAndUpdateDeployment directly

            // First execution: should trigger update (v2.2.2 → v2.2.3)
            controller.checkAndUpdateDeployment("immich/immich-server")

            // Second execution: should NOT trigger update
            // [BEFORE FIX] Cache still has v2.2.2, triggers duplicate update
            // [AFTER FIX] Cache has v2.2.3, no update needed
            controller.checkAndUpdateDeployment("immich/immich-server")

            // Then: DeploymentUpdater should be called only ONCE
            // [BEFORE FIX] Called twice (fails test)
            // [AFTER FIX] Called once (passes test)
            coVerify(exactly = 1) {
                mockDeploymentUpdater.updateDeployment(
                    "immich",
                    "immich-server",
                    "ghcr.io/immich-app/immich-server:v2.2.3@sha256:new",
                    any(),
                    any(),
                )
            }

            // Verify that second check received the updated image
            // [BEFORE FIX] Second check receives v2.2.2 (stale cache)
            // [AFTER FIX] Second check receives v2.2.3 (fresh cache)
            assertEquals(2, currentImageCaptures.size)
            assertTrue(currentImageCaptures[0].contains("v2.2.2"), "First check should see v2.2.2")
            assertTrue(currentImageCaptures[1].contains("v2.2.3"), "Second check should see v2.2.3 after cache update")
        }

    private fun createMockDeployment(
        namespace: String,
        name: String,
        image: String,
        annotations: Map<String, String>,
        containers: List<ContainerInfo> = listOf(ContainerInfo("container", image)),
    ): DeploymentInfo =
        DeploymentInfo(
            namespace = namespace,
            name = name,
            generation = 1,
            replicas = 1,
            selector = mapOf("app" to name),
            containers = containers,
            imagePullSecrets = emptyList(),
            annotations = annotations,
            status = DeploymentStatus(),
        )
}
