package com.watchcluster.service

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GHCRStrategyTest {
    private val strategy = GHCRStrategy()

    @Test
    fun `uses tag separator for image tags`() {
        assertEquals(
            "docker://ghcr.io/immich-app/immich-server:v3.0.2",
            strategy.buildImageReference("immich-app/immich-server", "v3.0.2"),
        )
    }

    @Test
    fun `uses digest separator for image digests`() {
        assertEquals(
            "docker://ghcr.io/immich-app/immich-machine-learning@sha256:abc123",
            strategy.buildImageReference("immich-app/immich-machine-learning", "sha256:abc123"),
        )
    }

    @Test
    fun `propagates skopeo failures`() =
        runBlocking {
            val failingStrategy =
                GHCRStrategy(
                    commandRunner = {
                        SkopeoCommandResult(
                            exitCode = 1,
                            stdout = "",
                            stderr = "authentication required",
                        )
                    },
                )

            assertFailsWith<IllegalStateException> {
                failingStrategy.getTags("private/repository", null)
            }
            Unit
        }
}
