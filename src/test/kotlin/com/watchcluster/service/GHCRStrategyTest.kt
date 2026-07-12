package com.watchcluster.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
}
