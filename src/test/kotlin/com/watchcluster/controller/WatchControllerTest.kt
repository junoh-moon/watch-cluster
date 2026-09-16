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
import com.watchcluster.service.DeploymentUpdateOutcome
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageCheckOutcome
import com.watchcluster.service.ImageChecker
import com.watchcluster.util.CronTicker
import com.watchcluster.util.CronUtilsTicker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchControllerTest {
    private lateinit var mockK8sClient: K8sClient

    /**
     * Test ticker: fires one cron tick per [fire] call and records every
     * expression it was asked to await, so tests can observe ticker
     * (re)starts without real time.
     */
    private class ManualCronTicker : CronTicker {
        private val ticks = Channel<Unit>(Channel.UNLIMITED)
        val awaitedExpressions = mutableListOf<String>()

        override suspend fun awaitNextExecution(cronExpression: String) {
            awaitedExpressions += cronExpression
            ticks.receive()
        }

        fun fire() {
            ticks.trySend(Unit)
        }
    }

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
    }

    private fun TestScope.createController(
        cronTicker: CronTicker = ManualCronTicker(),
        imageChecker: ImageChecker? = null,
        deploymentUpdater: DeploymentUpdater? = null,
    ): WatchController =
        WatchController(
            mockK8sClient,
            coroutineScope = backgroundScope,
            imageChecker = imageChecker,
            deploymentUpdater = deploymentUpdater,
            cronTicker = cronTicker,
        )

    private suspend fun TestScope.startAndCaptureWatcher(controller: WatchController): K8sWatcher<DeploymentInfo> {
        val watcherSlot = slot<K8sWatcher<DeploymentInfo>>()
        coEvery { mockK8sClient.watchDeployments(capture(watcherSlot)) } returns Unit
        controller.start()
        return watcherSlot.captured
    }

    @Test
    fun `start() should call kubernetes client watchDeployments`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)

            coVerify { mockK8sClient.watchDeployments(any()) }
            assertNotNull(watcher)
        }

    @Test
    fun `handleDeployment processes deployment with watch-cluster annotations`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "*/10 * * * *",
                            WatchClusterAnnotations.STRATEGY to "version-lock-major",
                        ),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()

            val worker = controller.workers["test-ns/test-app"]
            assertNotNull(worker)
            assertEquals("nginx:1.20.0", worker.spec.currentImage)
            assertEquals("*/10 * * * *", worker.spec.cronExpression)
        }

    @Test
    fun `handleDeployment ignores deployment without watch-cluster enabled annotation`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)
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

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()

            assertTrue(controller.workers.isEmpty())
        }

    @Test
    fun `handleDeployment ignores deployment with no annotations`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()

            assertTrue(controller.workers.isEmpty())
        }

    @Test
    fun `onDelete removes deployment from watched list`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()
            assertTrue(controller.workers.containsKey("test-ns/test-app"))

            watcher.eventReceived(K8sWatchEvent(EventType.DELETED, deployment))
            runCurrent()
            assertTrue(controller.workers.isEmpty())
        }

    @Test
    fun `onClose should not throw`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)

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
                WatchClusterAnnotations.CRON to "*/10 * * * *",
                WatchClusterAnnotations.STRATEGY to "version-lock-major",
            )

        val enabled = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false
        val cronExpression = annotations[WatchClusterAnnotations.CRON] ?: WatchClusterAnnotations.DEFAULT_CRON
        val strategyStr = annotations[WatchClusterAnnotations.STRATEGY] ?: "version"
        val strategy = UpdateStrategy.fromString(strategyStr)

        assertTrue(enabled)
        assertEquals("*/10 * * * *", cronExpression)
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
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(),
                )

            // Should not throw
            watcher.eventReceived(K8sWatchEvent(EventType.ERROR, deployment))
            runCurrent()

            assertNotNull(watcher)
        }

    @Test
    fun `check-now annotation is consumed and triggers immediate check with audit events`() =
        runTest {
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)

            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "*/10 * * * *",
                            WatchClusterAnnotations.STRATEGY to "version",
                            WatchClusterAnnotations.CHECK_NOW to "true",
                        ),
                )

            coEvery {
                mockK8sClient.patchDeployment(
                    "test-ns",
                    "test-app",
                    match { it.contains("\"${WatchClusterAnnotations.CHECK_NOW}\":null") },
                )
            } returns deployment.copy(annotations = deployment.annotations - WatchClusterAnnotations.CHECK_NOW)
            coEvery { mockK8sClient.recordDeploymentEvent(any(), any(), any(), any(), any()) } returns Unit
            coEvery {
                mockImageChecker.checkForUpdateOutcome(
                    "nginx:1.20.0",
                    any(),
                    "test-ns",
                    emptyList(),
                    "test-app",
                )
            } returns
                ImageCheckOutcome.UpToDate(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = null,
                        reason = "No newer version available",
                    ),
                )

            val controller =
                createController(
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )
            val watcher = startAndCaptureWatcher(controller)

            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()

            coVerify(exactly = 1) {
                mockK8sClient.patchDeployment(
                    "test-ns",
                    "test-app",
                    match { it.contains("\"${WatchClusterAnnotations.CHECK_NOW}\":null") },
                )
            }
            coVerify(exactly = 1) {
                mockImageChecker.checkForUpdateOutcome(
                    "nginx:1.20.0",
                    any(),
                    "test-ns",
                    emptyList(),
                    "test-app",
                )
            }
            coVerify {
                mockK8sClient.recordDeploymentEvent(
                    "test-ns",
                    "test-app",
                    "ManualCheckRequested",
                    match { it.contains("test-ns/test-app") },
                    "Normal",
                )
            }
            coVerify {
                mockK8sClient.recordDeploymentEvent(
                    "test-ns",
                    "test-app",
                    "ManualCheckNoUpdate",
                    match { it.contains("No newer version available") },
                    "Normal",
                )
            }
            coVerify(exactly = 0) {
                mockDeploymentUpdater.updateDeployment(any(), any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `manual check records registry failures instead of no update`() =
        runTest {
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CHECK_NOW to "true",
                        ),
                )
            coEvery { mockK8sClient.patchDeployment("test-ns", "test-app", any()) } returns
                deployment.copy(annotations = deployment.annotations - WatchClusterAnnotations.CHECK_NOW)
            coEvery {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            } returns
                ImageCheckOutcome.Failed(
                    currentImage = "nginx:1.20.0",
                    message = "Registry unavailable",
                    cause = IllegalStateException("Registry unavailable"),
                )

            val controller = createController(imageChecker = mockImageChecker, deploymentUpdater = mockDeploymentUpdater)
            val watcher = startAndCaptureWatcher(controller)

            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()

            coVerify(exactly = 1) {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            }
            coVerify(exactly = 1) {
                mockK8sClient.recordDeploymentEvent(
                    "test-ns",
                    "test-app",
                    "ManualCheckFailed",
                    match { it.contains("Registry unavailable") },
                    "Warning",
                )
            }
            coVerify(exactly = 0) {
                mockK8sClient.recordDeploymentEvent(
                    any(),
                    any(),
                    "ManualCheckNoUpdate",
                    any(),
                    any(),
                )
            }
        }

    @Test
    fun `redelivered check-now requests coalesce into a single manual check`() =
        runTest {
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)

            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CHECK_NOW to "true",
                        ),
                )

            coEvery {
                mockK8sClient.patchDeployment("test-ns", "test-app", any())
            } returns deployment.copy(annotations = deployment.annotations - WatchClusterAnnotations.CHECK_NOW)
            coEvery {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            } returns
                ImageCheckOutcome.UpToDate(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = null,
                        reason = "No newer version available",
                    ),
                )

            val controller =
                createController(
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )
            val watcher = startAndCaptureWatcher(controller)

            // Both events are queued before the worker gets to run; the
            // duplicated manual check request must collapse into one.
            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()

            coVerify(exactly = 1) {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `check-now redelivered during a running manual check is absorbed`() =
        runTest {
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)

            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CHECK_NOW to "true",
                        ),
                )

            coEvery {
                mockK8sClient.patchDeployment("test-ns", "test-app", any())
            } returns deployment.copy(annotations = deployment.annotations - WatchClusterAnnotations.CHECK_NOW)
            coEvery {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            } coAnswers {
                // Slow registry lookup keeps the manual check in flight.
                delay(1000)
                ImageCheckOutcome.UpToDate(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = null,
                        reason = "No newer version available",
                    ),
                )
            }

            val controller =
                createController(
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )
            val watcher = startAndCaptureWatcher(controller)

            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()

            // Redelivered while the first manual check is still running.
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()

            advanceTimeBy(1000)
            runCurrent()

            coVerify(exactly = 1) {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            }

            // A fresh request after completion must run again.
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()
            advanceTimeBy(1000)
            runCurrent()

            coVerify(exactly = 2) {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `watch establishment failure propagates and starts no reconcile loop`() =
        runTest {
            coEvery { mockK8sClient.watchDeployments(any()) } throws IllegalStateException("watch forbidden")
            val controller = createController()

            assertFailsWith<IllegalStateException> {
                controller.start()
            }

            // No orphaned reconcile loop may run after the failed start.
            advanceTimeBy(61_000)
            runCurrent()
            coVerify(exactly = 0) { mockK8sClient.listDeployments() }
        }

    @Test
    fun `reconcile consumes check-now annotation without a watch event`() =
        runTest {
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "*/10 * * * *",
                            WatchClusterAnnotations.STRATEGY to "version",
                            WatchClusterAnnotations.CHECK_NOW to "true",
                        ),
                )

            coEvery { mockK8sClient.listDeployments() } returns listOf(deployment)
            coEvery {
                mockK8sClient.patchDeployment(
                    "test-ns",
                    "test-app",
                    match { it.contains("\"${WatchClusterAnnotations.CHECK_NOW}\":null") },
                )
            } returns deployment.copy(annotations = deployment.annotations - WatchClusterAnnotations.CHECK_NOW)
            coEvery { mockK8sClient.recordDeploymentEvent(any(), any(), any(), any(), any()) } returns Unit
            coEvery {
                mockImageChecker.checkForUpdateOutcome(
                    "nginx:1.20.0",
                    any(),
                    "test-ns",
                    emptyList(),
                    "test-app",
                )
            } returns
                ImageCheckOutcome.UpToDate(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = null,
                        reason = "No newer version available",
                    ),
                )

            val controller =
                createController(
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )

            controller.reconcileDeployments()
            runCurrent()

            coVerify(exactly = 1) {
                mockK8sClient.patchDeployment(
                    "test-ns",
                    "test-app",
                    match { it.contains("\"${WatchClusterAnnotations.CHECK_NOW}\":null") },
                )
            }
            coVerify(exactly = 1) {
                mockImageChecker.checkForUpdateOutcome(
                    "nginx:1.20.0",
                    any(),
                    "test-ns",
                    emptyList(),
                    "test-app",
                )
            }
            coVerify {
                mockK8sClient.recordDeploymentEvent(
                    "test-ns",
                    "test-app",
                    "ManualCheckNoUpdate",
                    match { it.contains("No newer version available") },
                    "Normal",
                )
            }
        }

    @Test
    fun `reconcile prunes workers whose deployment no longer exists`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()
            assertTrue(controller.workers.containsKey("test-ns/test-app"))

            // The deployment was deleted while the watch was down: the DELETED
            // event is lost, but reconcile must still prune the worker.
            coEvery { mockK8sClient.listDeployments() } returns emptyList()
            controller.reconcileDeployments()
            runCurrent()

            assertTrue(controller.workers.isEmpty())
        }

    @Test
    fun `reconcile keeps workers when listing deployments fails`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()
            assertTrue(controller.workers.containsKey("test-ns/test-app"))

            // A failed LIST is not an empty cluster; nothing may be pruned.
            coEvery { mockK8sClient.listDeployments() } throws RuntimeException("api server unavailable")
            controller.reconcileDeployments()
            runCurrent()

            assertTrue(controller.workers.containsKey("test-ns/test-app"))
        }

    @Test
    fun `unchanged deployment redelivery does not restart the cron ticker`() =
        runTest {
            val ticker = ManualCronTicker()
            val controller = createController(cronTicker = ticker)
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "*/10 * * * *",
                            WatchClusterAnnotations.STRATEGY to "version",
                        ),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()

            assertEquals(listOf("*/10 * * * *"), ticker.awaitedExpressions)
        }

    @Test
    fun `cron annotation change restarts the ticker with the new expression`() =
        runTest {
            val ticker = ManualCronTicker()
            val controller = createController(cronTicker = ticker)
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "*/10 * * * *",
                        ),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()

            val rescheduled =
                deployment.copy(
                    annotations = deployment.annotations + (WatchClusterAnnotations.CRON to "*/30 * * * *"),
                )
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, rescheduled))
            runCurrent()

            assertEquals(listOf("*/10 * * * *", "*/30 * * * *"), ticker.awaitedExpressions)
        }

    @Test
    fun `invalid cron disables periodic checks but manual check still works`() =
        runTest {
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "invalid cron",
                            WatchClusterAnnotations.CHECK_NOW to "true",
                        ),
                )

            coEvery {
                mockK8sClient.patchDeployment("test-ns", "test-app", any())
            } returns deployment.copy(annotations = deployment.annotations - WatchClusterAnnotations.CHECK_NOW)
            coEvery {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            } returns
                ImageCheckOutcome.UpToDate(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = null,
                        reason = "No newer version available",
                    ),
                )

            val controller =
                createController(
                    cronTicker = CronUtilsTicker(),
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )
            val watcher = startAndCaptureWatcher(controller)

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()

            coVerify(exactly = 1) {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            }
            assertTrue(controller.workers.containsKey("test-ns/test-app"))
        }

    @Test
    fun `test MODIFIED event updates deployment spec`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)

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
            runCurrent()

            assertEquals("nginx:1.21.0", controller.workers["test-ns/test-app"]?.spec?.currentImage)
        }

    @Test
    fun `test consecutive MODIFIED events apply the latest spec`() =
        runTest {
            val controller = createController()
            val watcher = startAndCaptureWatcher(controller)

            listOf("nginx:1.20.0", "nginx:1.21.0", "nginx:1.22.0").forEachIndexed { index, image ->
                val deployment =
                    createMockDeployment(
                        namespace = "test-ns",
                        name = "test-app",
                        image = image,
                        annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                    )
                val eventType = if (index == 0) EventType.ADDED else EventType.MODIFIED
                watcher.eventReceived(K8sWatchEvent(eventType, deployment))
            }
            runCurrent()

            assertEquals("nginx:1.22.0", controller.workers["test-ns/test-app"]?.spec?.currentImage)
        }

    @Test
    fun `disabling mid-check lets the in-flight check run to completion`() =
        runTest {
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)
            val ticker = ManualCronTicker()

            coEvery {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            } coAnswers {
                // Simulate a slow registry lookup / rollout.
                delay(1000)
                ImageCheckOutcome.UpdateAvailable(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = "nginx:1.21.0",
                        reason = "Found newer version: 1.21.0",
                    ),
                )
            }
            coEvery {
                mockDeploymentUpdater.updateDeployment(any(), any(), any(), any(), any(), any())
            } returns DeploymentUpdateOutcome.PatchAppliedAndCompleted("nginx:1.21.0")

            val controller =
                createController(
                    cronTicker = ticker,
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()

            // Kick off a scheduled check and let it suspend mid-flight.
            ticker.fire()
            runCurrent()

            // Disable while the check is in progress.
            val disabled =
                deployment.copy(
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "false"),
                )
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, disabled))
            runCurrent()

            // The in-flight check must complete (graceful drain), then the
            // worker terminates.
            advanceTimeBy(1000)
            runCurrent()

            coVerify(exactly = 1) {
                mockDeploymentUpdater.updateDeployment(
                    "test-ns",
                    "test-app",
                    "nginx:1.21.0",
                    any(),
                    any(),
                    any(),
                )
            }
            assertTrue(controller.workers.isEmpty())
        }

    @Test
    fun `stopAndJoin waits for an in-flight check`() =
        runTest {
            val checkStarted = CompletableDeferred<Unit>()
            val releaseCheck = CompletableDeferred<Unit>()
            val mockImageChecker = mockk<ImageChecker>()
            val ticker = ManualCronTicker()
            coEvery {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            } coAnswers {
                checkStarted.complete(Unit)
                releaseCheck.await()
                ImageCheckOutcome.UpToDate(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = null,
                        reason = "Already at latest version",
                    ),
                )
            }
            val controller = createController(cronTicker = ticker, imageChecker = mockImageChecker)
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()
            ticker.fire()
            checkStarted.await()

            val stop = backgroundScope.async { controller.stopAndJoin() }
            runCurrent()
            assertFalse(stop.isCompleted)

            releaseCheck.complete(Unit)
            stop.await()

            assertTrue(controller.workers.isEmpty())
        }

    @Test
    fun `re-enabled deployment gets a fresh worker after the old one stops`() =
        runTest {
            val ticker = ManualCronTicker()
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)
            coEvery {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            } returns
                ImageCheckOutcome.UpToDate(
                    ImageUpdateResult(
                        currentImage = "nginx:1.20.0",
                        newImage = null,
                        reason = "Already at latest version",
                    ),
                )

            val controller =
                createController(
                    cronTicker = ticker,
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "test-ns",
                    name = "test-app",
                    image = "nginx:1.20.0",
                    annotations = mapOf(WatchClusterAnnotations.ENABLED to "true"),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()
            val firstWorker = controller.workers["test-ns/test-app"]
            assertNotNull(firstWorker)

            val disabled = deployment.copy(annotations = mapOf(WatchClusterAnnotations.ENABLED to "false"))
            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, disabled))
            runCurrent()

            watcher.eventReceived(K8sWatchEvent(EventType.MODIFIED, deployment))
            runCurrent()

            val secondWorker = controller.workers["test-ns/test-app"]
            assertNotNull(secondWorker)
            assertFalse(secondWorker === firstWorker, "A stopped worker must be replaced, not reused")
            assertFalse(secondWorker.isStopRequested)

            // The fresh worker still performs scheduled checks.
            ticker.fire()
            runCurrent()
            coVerify(exactly = 1) {
                mockImageChecker.checkForUpdateOutcome(any(), any(), any(), any(), any())
            }
        }

    @Test
    fun `should not duplicate update when cache is properly updated after deployment update`() =
        runTest {
            // Given: Mock dependencies
            val mockImageChecker = mockk<ImageChecker>()
            val mockDeploymentUpdater = mockk<DeploymentUpdater>(relaxed = true)
            val ticker = ManualCronTicker()

            val currentImageCaptures = mutableListOf<String>()

            // Setup: ImageChecker returns update if current image is v2.2.2, otherwise no update
            coEvery {
                mockImageChecker.checkForUpdateOutcome(
                    capture(currentImageCaptures),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } answers {
                val currentImage = currentImageCaptures.last()
                if (currentImage.contains("v2.2.2")) {
                    ImageCheckOutcome.UpdateAvailable(
                        ImageUpdateResult(
                            currentImage = currentImage,
                            newImage = "ghcr.io/immich-app/immich-server:v2.2.3@sha256:new",
                            reason = "Found newer version: v2.2.3",
                        ),
                    )
                } else {
                    ImageCheckOutcome.UpToDate(
                        ImageUpdateResult(
                            currentImage = currentImage,
                            newImage = null,
                            reason = "Already at latest version",
                        ),
                    )
                }
            }
            coEvery {
                mockDeploymentUpdater.updateDeployment(any(), any(), any(), any(), any(), any())
            } answers {
                DeploymentUpdateOutcome.PatchAppliedAndCompleted(
                    thirdArg(),
                )
            }

            val controller =
                createController(
                    cronTicker = ticker,
                    imageChecker = mockImageChecker,
                    deploymentUpdater = mockDeploymentUpdater,
                )
            val watcher = startAndCaptureWatcher(controller)
            val deployment =
                createMockDeployment(
                    namespace = "immich",
                    name = "immich-server",
                    image = "ghcr.io/immich-app/immich-server:v2.2.2@sha256:old",
                    annotations =
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            WatchClusterAnnotations.CRON to "*/10 * * * *",
                        ),
                )

            watcher.eventReceived(K8sWatchEvent(EventType.ADDED, deployment))
            runCurrent()

            // When: two scheduled checks run back to back

            // First execution: should trigger update (v2.2.2 → v2.2.3)
            ticker.fire()
            runCurrent()

            // Second execution: should NOT trigger update — the worker's spec
            // must already reflect v2.2.3
            ticker.fire()
            runCurrent()

            // Then: DeploymentUpdater should be called only ONCE
            coVerify(exactly = 1) {
                mockDeploymentUpdater.updateDeployment(
                    "immich",
                    "immich-server",
                    "ghcr.io/immich-app/immich-server:v2.2.3@sha256:new",
                    any(),
                    any(),
                    any(),
                )
            }

            // Verify that the second check received the updated image
            assertEquals(2, currentImageCaptures.size)
            assertTrue(currentImageCaptures[0].contains("v2.2.2"), "First check should see v2.2.2")
            assertTrue(currentImageCaptures[1].contains("v2.2.3"), "Second check should see v2.2.3 after cache update")
        }

    @Test
    fun `minimum release age holds an update when its digest cannot be verified`() =
        runTest {
            val checker = io.mockk.spyk(ImageChecker(mockK8sClient))
            val updater = mockk<DeploymentUpdater>()
            val ticker = ManualCronTicker()
            coEvery { checker.checkForUpdateOutcome(any(), any(), any(), any(), any()) } returns
                ImageCheckOutcome.UpdateAvailable(ImageUpdateResult("nginx:1.0.0", "nginx:2.0.0"))
            coEvery { updater.updateDeployment(any(), any(), any(), any(), any(), any()) } returns
                DeploymentUpdateOutcome.PatchAppliedAndCompleted("nginx:2.0.0")
            val controller = createController(ticker, checker, updater)
            val watcher = startAndCaptureWatcher(controller)
            watcher.eventReceived(
                K8sWatchEvent(
                    EventType.ADDED,
                    createMockDeployment(
                        "default",
                        "age-test",
                        "nginx:1.0.0",
                        mapOf(
                            WatchClusterAnnotations.ENABLED to "true",
                            "watch-cluster.io/minimum-release-age" to "3d",
                        ),
                    ),
                ),
            )
            runCurrent()
            ticker.fire()
            runCurrent()

            val status = controller.workers.getValue("default/age-test").status()
            assertEquals("RELEASE_TIME_UNKNOWN", status.recentChecks.first().status.name)
            coVerify(exactly = 0) { updater.updateDeployment(any(), any(), any(), any(), any(), any()) }
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
