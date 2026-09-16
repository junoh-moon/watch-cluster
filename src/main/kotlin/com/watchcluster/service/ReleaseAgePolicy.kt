package com.watchcluster.service

import com.watchcluster.model.DockerAuth
import com.watchcluster.model.ImageUpdateResult
import com.watchcluster.util.ImageParser
import kotlinx.coroutines.CancellationException
import mu.KotlinLogging
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

sealed interface ReleaseAgeDecision {
    data class Allowed(val image: String) : ReleaseAgeDecision

    data class Waiting(val pushedAt: Instant, val eligibleAt: Instant) : ReleaseAgeDecision

    data class Unavailable(val reason: String) : ReleaseAgeDecision
}

/** Stateless: every check reselects the candidate and reads its registry time. */
class ReleaseAgePolicy(
    private val registryClient: DockerRegistryClient,
    private val clock: Clock = Clock.systemUTC(),
    private val excludedRegistries: Set<String> = excludedRegistriesFromEnvironment(),
) {
    suspend fun evaluate(
        candidate: ImageUpdateResult,
        minimumAge: Duration,
        dockerAuth: DockerAuth? = null,
    ): ReleaseAgeDecision {
        val image = requireNotNull(candidate.newImage)
        val (registry, repository, tag) = ImageParser.parseImageString(image)
        if (minimumAge.isZero || (registry ?: "docker.io") in excludedRegistries) {
            return ReleaseAgeDecision.Allowed(image)
        }
        if (registry != null && registry != "docker.io") {
            return ReleaseAgeDecision.Unavailable("Push time lookup is not supported for registry $registry")
        }
        val digest =
            candidate.newDigest?.takeIf { it.isNotBlank() }
                ?: return ReleaseAgeDecision.Unavailable("Candidate digest is unavailable")

        return try {
            val pushedAt =
                registryClient.getImagePushedAt(registry, repository, tag, digest, dockerAuth)
                    ?: return ReleaseAgeDecision.Unavailable("No push time matching the candidate digest")
            val eligibleAt = pushedAt.plus(minimumAge)
            if (clock.instant().isBefore(eligibleAt)) {
                ReleaseAgeDecision.Waiting(pushedAt, eligibleAt)
            } else {
                // A mutable tag can move between the check and the kubelet pull.
                // Only the exact image whose age was verified may be deployed.
                ReleaseAgeDecision.Allowed(ImageParser.addDigest(image, digest))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Could not verify minimum release age for $image" }
            ReleaseAgeDecision.Unavailable("Push time lookup failed; will retry at the next check")
        }
    }

    companion object {
        fun excludedRegistriesFromEnvironment(): Set<String> =
            System.getenv("MINIMUM_RELEASE_AGE_EXCLUDED_REGISTRIES")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
    }
}
