package com.watchcluster.controller

import com.watchcluster.client.K8sClient
import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.ContainerInfo
import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.DeploymentStatus
import com.watchcluster.client.domain.EventType
import com.watchcluster.client.domain.K8sWatchEvent
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.service.DeploymentUpdateOutcome
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.DockerRegistryClient
import com.watchcluster.service.ImageChecker
import com.watchcluster.util.CronTicker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MinimumReleaseAgeControllerTest {
    private val cluster = mockk<K8sClient>(relaxed = true)
    private val registry = mockk<DockerRegistryClient>()
    private val updater = mockk<DeploymentUpdater>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)
    private val ticker =
        object : CronTicker {
            override suspend fun awaitNextExecution(cronExpression: String) {
                ticks.receive()
            }
        }
    private val checker =
        ImageChecker(cluster).also {
            ImageChecker::class.java.getDeclaredField("registryClient").apply {
                isAccessible = true
                set(it, registry)
            }
        }
    private val deployment =
        DeploymentInfo(
            namespace = "default",
            name = "app",
            generation = 1,
            replicas = 1,
            selector = mapOf("app" to "app"),
            containers = listOf(ContainerInfo("app", "nginx:1.0.0")),
            imagePullSecrets = emptyList(),
            annotations = mapOf(WatchClusterAnnotations.ENABLED to "true", WatchClusterAnnotations.MINIMUM_RELEASE_AGE to "3d"),
            status = DeploymentStatus(),
        )

    private suspend fun TestScope.start(spec: DeploymentInfo = deployment): Pair<WatchController, K8sWatcher<DeploymentInfo>> {
        val watcher = slot<K8sWatcher<DeploymentInfo>>()
        coEvery { cluster.watchDeployments(capture(watcher)) } returns Unit
        coEvery { cluster.patchDeployment(any(), any(), any()) } returns spec
        coEvery { cluster.getDeployment(any(), any()) } returns spec
        coEvery { registry.getImageDigest(null, "nginx", any(), null, null) } answers { "sha256:${thirdArg<String>()}" }
        coEvery { updater.updateDeployment(any(), any(), any(), any(), any(), any()) } answers {
            DeploymentUpdateOutcome.PatchAppliedAndCompleted(thirdArg())
        }
        val controller = WatchController(cluster, backgroundScope, checker, updater, ticker)
        controller.start()
        watcher.captured.eventReceived(K8sWatchEvent(EventType.ADDED, spec))
        runCurrent()
        return controller to watcher.captured
    }

    @Test
    fun `cron holds latest candidate instead of falling back and reevaluates newer candidate`() =
        runTest {
            coEvery {
                registry.getTags(
                    null,
                    "nginx",
                    null,
                )
            } returnsMany listOf(listOf("1.1.0", "1.2.0"), listOf("1.1.0", "1.2.0", "1.3.0"))
            coEvery { registry.getImagePushedAt(null, "nginx", "1.1.0", any(), null) } returns Instant.now().minusSeconds(7 * 86400)
            coEvery { registry.getImagePushedAt(null, "nginx", "1.2.0", any(), null) } returns Instant.now()
            coEvery { registry.getImagePushedAt(null, "nginx", "1.3.0", any(), null) } returns Instant.now()
            val (controller, _) = start()
            repeat(2) {
                ticks.send(Unit)
                runCurrent()
            }
            val checks = controller.workers.getValue("default/app").status().recentChecks
            assertEquals(
                listOf(DeploymentCheckStatus.RELEASE_AGE_WAITING, DeploymentCheckStatus.RELEASE_AGE_WAITING),
                checks.map { it.status },
            )
            assertTrue(checks[0].message.contains("1.3.0"))
            assertTrue(checks[1].message.contains("1.2.0"))
            coVerify(exactly = 0) { registry.getImagePushedAt(null, "nginx", "1.1.0", any(), null) }
            coVerify(exactly = 0) { updater.updateDeployment(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `already old first discovery updates on cron with the verified digest`() =
        runTest {
            coEvery { registry.getTags(null, "nginx", null) } returns listOf("1.2.0")
            coEvery { registry.getImagePushedAt(any(), any(), any(), any(), any()) } returns Instant.now().minusSeconds(7 * 86400)
            val (controller, _) = start()
            coVerify(exactly = 0) { updater.updateDeployment(any(), any(), any(), any(), any(), any()) }
            ticks.send(Unit)
            runCurrent()
            assertEquals(DeploymentCheckStatus.UPDATED, controller.workers.getValue("default/app").status().recentChecks.first().status)
            coVerify(exactly = 1) { updater.updateDeployment("default", "app", "nginx:1.2.0@sha256:1.2.0", any(), any(), "sha256:1.2.0") }
        }

    @Test
    fun `manual check respects age and records a waiting event`() =
        runTest {
            coEvery { registry.getTags(null, "nginx", null) } returns listOf("1.2.0")
            coEvery { registry.getImagePushedAt(any(), any(), any(), any(), any()) } returns Instant.now()
            val (controller, _) =
                start(
                    deployment.copy(annotations = deployment.annotations + (WatchClusterAnnotations.CHECK_NOW to "true")),
                )
            val check = controller.workers.getValue("default/app").status().recentChecks.first()
            assertEquals(CheckTrigger.MANUAL, check.trigger)
            assertEquals(DeploymentCheckStatus.RELEASE_AGE_WAITING, check.status)
            coVerify(exactly = 0) { updater.updateDeployment(any(), any(), any(), any(), any(), any()) }
            coVerify { cluster.recordDeploymentEvent("default", "app", "ManualCheckReleaseAgeWaiting", any(), "Normal") }
        }

    @Test
    fun `unavailable time holds update and a later cron retries successfully`() =
        runTest {
            coEvery { registry.getTags(null, "nginx", null) } returns listOf("1.2.0")
            coEvery {
                registry.getImagePushedAt(
                    any(),
                    any(),
                    any(),
                    any(),
                    any(),
                )
            } returnsMany listOf(null, Instant.now().minusSeconds(7 * 86400))
            val (controller, _) = start()
            ticks.send(Unit)
            runCurrent()
            assertEquals(
                DeploymentCheckStatus.RELEASE_TIME_UNKNOWN,
                controller.workers.getValue("default/app").status().recentChecks.first().status,
            )
            coVerify(exactly = 0) { updater.updateDeployment(any(), any(), any(), any(), any(), any()) }
            ticks.send(Unit)
            runCurrent()
            assertEquals(DeploymentCheckStatus.UPDATED, controller.workers.getValue("default/app").status().recentChecks.first().status)
        }

    @Test
    fun `invalid or zero annotation bypasses age lookup`() =
        runTest {
            coEvery { registry.getTags(null, "nginx", null) } returns listOf("1.2.0")
            val (controller, watcher) =
                start(
                    deployment.copy(annotations = deployment.annotations + (WatchClusterAnnotations.MINIMUM_RELEASE_AGE to "invalid")),
                )
            ticks.send(Unit)
            runCurrent()
            assertEquals(DeploymentCheckStatus.UPDATED, controller.workers.getValue("default/app").status().recentChecks.first().status)
            watcher.eventReceived(
                K8sWatchEvent(
                    EventType.MODIFIED,
                    deployment.copy(annotations = deployment.annotations + (WatchClusterAnnotations.MINIMUM_RELEASE_AGE to "0")),
                ),
            )
            runCurrent()
            ticks.send(Unit)
            runCurrent()
            coVerify(exactly = 0) { registry.getImagePushedAt(any(), any(), any(), any(), any()) }
            coVerify(exactly = 2) { updater.updateDeployment(any(), any(), any(), any(), any(), any()) }
        }

    @Test
    fun `latest tracks each new digest and identifies the pending candidate`() =
        runTest {
            val latest =
                deployment.copy(
                    containers = listOf(ContainerInfo("app", "nginx:latest@sha256:old")),
                    annotations = deployment.annotations + (WatchClusterAnnotations.STRATEGY to "latest"),
                )
            val (controller, _) = start(latest)
            coEvery { registry.getImageDigest(null, "nginx", "latest", null, null) } returnsMany
                listOf("sha256:fresh1", "sha256:fresh2")
            coEvery { registry.getImagePushedAt(null, "nginx", "latest", any(), null) } returns Instant.now()
            repeat(2) {
                ticks.send(Unit)
                runCurrent()
            }
            val checks = controller.workers.getValue("default/app").status().recentChecks
            assertEquals(2, checks.size)
            assertTrue(checks.all { it.status == DeploymentCheckStatus.RELEASE_AGE_WAITING })
            assertTrue(checks[0].message.contains("sha256:fresh2"))
            assertTrue(checks[1].message.contains("sha256:fresh1"))
            coVerify(exactly = 0) { updater.updateDeployment(any(), any(), any(), any(), any(), any()) }
        }
}
