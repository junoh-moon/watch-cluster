package com.watchcluster.model

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MinimumReleaseAgeTest {
    @Test
    fun `parses hours days weeks and disabled settings`() {
        assertEquals(Duration.ZERO, MinimumReleaseAge.parse(null))
        assertEquals(Duration.ZERO, MinimumReleaseAge.parse("0"))
        assertEquals(Duration.ofHours(12), MinimumReleaseAge.parse("12h"))
        assertEquals(Duration.ofDays(3), MinimumReleaseAge.parse("3d"))
        assertEquals(Duration.ofDays(21), MinimumReleaseAge.parse("3w"))
    }

    @Test
    fun `rejects invalid and overflowing settings instead of truncating them`() {
        listOf("", "-1d", "1.5h", "1d12h", "3", "3D", " 3d ", "0h", "99999999999999999999w", "9223372036854775807h")
            .forEach { assertNull(MinimumReleaseAge.parse(it), it) }
    }
}
