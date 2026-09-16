package com.watchcluster.service

import com.watchcluster.model.ImageUpdateResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ReleaseAgePolicyTest {
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val registry = mockk<DockerRegistryClient>()
    private val policy = ReleaseAgePolicy(registry, Clock.fixed(now, ZoneOffset.UTC), setOf("hub.sixtyfive.me"))
    private val candidate = ImageUpdateResult("nginx:latest", "nginx:latest", newDigest = "sha256:new")

    @Test
    fun `fresh digest waits until its push time plus age`() =
        runBlocking {
            coEvery { registry.getImagePushedAt(null, "nginx", "latest", "sha256:new", null) } returns now.minusSeconds(3600)
            val result = assertIs<ReleaseAgeDecision.Waiting>(policy.evaluate(candidate, Duration.ofDays(3)))
            assertEquals(now.minusSeconds(3600), result.pushedAt)
            assertEquals(now.plusSeconds(3 * 86400 - 3600), result.eligibleAt)
        }

    @Test
    fun `first discovery at exact eligibility boundary is immediately allowed and pinned`() =
        runBlocking {
            coEvery { registry.getImagePushedAt(null, "nginx", "latest", "sha256:new", null) } returns now.minusSeconds(3 * 86400)
            val result = assertIs<ReleaseAgeDecision.Allowed>(policy.evaluate(candidate, Duration.ofDays(3)))
            assertEquals("nginx:latest@sha256:new", result.image)
        }

    @Test
    fun `already old image has no new discovery delay even after policy recreation`() =
        runBlocking {
            coEvery { registry.getImagePushedAt(null, "nginx", "latest", "sha256:new", null) } returns now.minusSeconds(7 * 86400)
            repeat(2) {
                val recreated = ReleaseAgePolicy(registry, Clock.fixed(now, ZoneOffset.UTC), emptySet())
                assertIs<ReleaseAgeDecision.Allowed>(recreated.evaluate(candidate, Duration.ofDays(3)))
            }
        }

    @Test
    fun `disabled and excluded registry do not perform a time lookup or pin the tag`() =
        runBlocking {
            assertEquals(ReleaseAgeDecision.Allowed("nginx:latest"), policy.evaluate(candidate, Duration.ZERO))
            val privateImage = candidate.copy(newImage = "hub.sixtyfive.me/app:latest", newDigest = null)
            assertEquals(ReleaseAgeDecision.Allowed(privateImage.newImage!!), policy.evaluate(privateImage, Duration.ofDays(3)))
            coVerify(exactly = 0) { registry.getImagePushedAt(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `missing digest unsupported registry and lookalike excluded hosts are held`() =
        runBlocking {
            assertIs<ReleaseAgeDecision.Unavailable>(policy.evaluate(candidate.copy(newDigest = null), Duration.ofDays(3)))
            assertIs<ReleaseAgeDecision.Unavailable>(
                policy.evaluate(candidate.copy(newImage = "ghcr.io/org/app:latest"), Duration.ofDays(3)),
            )
            assertIs<ReleaseAgeDecision.Unavailable>(
                policy.evaluate(candidate.copy(newImage = "hub.sixtyfive.me.example/app:latest"), Duration.ofDays(3)),
            )
            coVerify(exactly = 0) { registry.getImagePushedAt(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `missing timestamp and lookup failure hold the update without a discovery fallback`() =
        runBlocking {
            coEvery { registry.getImagePushedAt(any(), any(), any(), any(), any()) } returns null
            assertIs<ReleaseAgeDecision.Unavailable>(policy.evaluate(candidate, Duration.ofDays(3)))
            coEvery { registry.getImagePushedAt(any(), any(), any(), any(), any()) } throws IllegalStateException("HTTP 429")
            assertIs<ReleaseAgeDecision.Unavailable>(policy.evaluate(candidate, Duration.ofDays(3)))
        }

    @Test
    fun `lookup cancellation propagates`() =
        runBlocking {
            coEvery { registry.getImagePushedAt(any(), any(), any(), any(), any()) } throws CancellationException("stopped")
            assertFailsWith<CancellationException> { policy.evaluate(candidate, Duration.ofDays(3)) }
            Unit
        }

    @Test
    fun `future push time extends waiting rather than being considered old`() =
        runBlocking {
            coEvery { registry.getImagePushedAt(any(), any(), any(), any(), any()) } returns now.plusSeconds(3600)
            val result = assertIs<ReleaseAgeDecision.Waiting>(policy.evaluate(candidate, Duration.ofHours(12)))
            assertTrue(result.eligibleAt > now.plusSeconds(12 * 3600))
        }
}
