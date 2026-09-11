package com.watchcluster.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImageParserTest {

    @Test
    fun `parseImageString handles all image format cases`() {
        // No registry cases
        assertEquals(
            ImageComponents(null, "nginx", "latest"),
            ImageParser.parseImageString("nginx"),
        )
        assertEquals(
            ImageComponents(null, "library/nginx", "latest"),
            ImageParser.parseImageString("library/nginx"),
        )

        // With tag
        assertEquals(
            ImageComponents(null, "nginx", "1.20.0"),
            ImageParser.parseImageString("nginx:1.20.0"),
        )

        // Registry cases
        assertEquals(
            ImageComponents("docker.io", "nginx", "1.20.0"),
            ImageParser.parseImageString("docker.io/nginx:1.20.0"),
        )
        assertEquals(
            ImageComponents("localhost", "myapp", "latest"),
            ImageParser.parseImageString("localhost/myapp"),
        )
        assertEquals(
            ImageComponents("registry:5000", "myapp", "latest"),
            ImageParser.parseImageString("registry:5000/myapp"),
        )

        // With digest
        assertEquals(
            ImageComponents("ghcr.io", "owner/repo", "latest"),
            ImageParser.parseImageString("ghcr.io/owner/repo:latest@sha256:aaa"),
        )
    }

    @Test
    fun `buildImageString methods work correctly`() {
        // Direct method
        assertEquals("nginx:latest", ImageParser.buildImageString(null, "nginx", "latest"))
        assertEquals("docker.io/nginx:1.20.0", ImageParser.buildImageString("docker.io", "nginx", "1.20.0"))

        // Components overload
        val components = ImageComponents("myregistry.com", "myapp", "v1.0.0")
        assertEquals("myregistry.com/myapp:v1.0.0", ImageParser.buildImageString(components))
    }

    @Test
    fun `isVersionTag and parseVersion handle edge cases`() {
        // Version tags
        assertTrue(ImageParser.isVersionTag("1.0.0"))
        assertTrue(ImageParser.isVersionTag("v1.0.0"))
        assertTrue(ImageParser.isVersionTag("1.2"))
        assertTrue(ImageParser.isVersionTag("1.0.0-rc1"))
        assertFalse(ImageParser.isVersionTag("latest"))

        // Version parsing
        assertEquals(listOf(1, 2, 3), ImageParser.parseVersion("v1.2.3"))
        assertEquals(listOf(1, 2, 3), ImageParser.parseVersion("1.2.3-alpha"))
        // Non-numeric version parts
        assertEquals(listOf(1, 0, 0), ImageParser.parseVersion("1.x.y"))
    }

    @Test
    fun `isPrerelease flags rc alpha beta but not channel or variant suffixes`() {
        // Prereleases that must be blocked
        assertTrue(ImageParser.isPrerelease("2.1.2-rc.2"))
        assertTrue(ImageParser.isPrerelease("1.0.0-rc1"))
        assertTrue(ImageParser.isPrerelease("v1.2.0-rc1"))
        assertTrue(ImageParser.isPrerelease("1.1.0-beta"))
        assertTrue(ImageParser.isPrerelease("1.0.0-beta.3"))
        assertTrue(ImageParser.isPrerelease("v2.0.0-alpha"))
        assertTrue(ImageParser.isPrerelease("v2.0.0-beta.10"))

        // Stable tags are never prereleases
        assertFalse(ImageParser.isPrerelease("2.1.1"))
        assertFalse(ImageParser.isPrerelease("v1.0.0"))

        // Channel / variant suffixes must NOT be treated as prereleases.
        // "previous" must survive even though it starts with "pre".
        assertFalse(ImageParser.isPrerelease("31.0.14-previous"))
        assertFalse(ImageParser.isPrerelease("31.0.9-ls393"))
        assertFalse(ImageParser.isPrerelease("31.0.7-php8"))
        assertFalse(ImageParser.isPrerelease("31.0.5-develop"))
        assertFalse(ImageParser.isPrerelease("1.2.3-apache"))
        assertFalse(ImageParser.isPrerelease("1.2.3-fpm-alpine"))
        assertFalse(ImageParser.isPrerelease("1.2.3-alpine3.20"))
    }

    @Test
    fun `version comparison ignores suffixes starting with a letter`() {
        for ((tag, version) in listOf(
            "12.0ubu2604-ls48" to listOf(12, 0),
            "12.1ubu2604-ls49" to listOf(12, 1),
            "12.0.1ubu2604-ls49" to listOf(12, 0, 1),
            "v12.0.1UBU2604-ls49" to listOf(12, 0, 1),
        )) {
            assertTrue(ImageParser.isVersionTag(tag), tag)
            assertEquals(version, ImageParser.parseVersion(tag), tag)
            assertFalse(ImageParser.isPrerelease(tag), tag)
        }
        assertFalse(ImageParser.isVersionTag("amd64-12.0ubu2604-ls48"))
        assertFalse(ImageParser.isVersionTag("version-12.0ubu2604"))
        assertFalse(ImageParser.isVersionTag("12.0.1.2"))
    }

    @Test
    fun `attached prerelease suffixes remain prereleases`() {
        for (tag in listOf("12.0rc1", "12.0.1alpha2", "v12.0BETA3")) {
            assertTrue(ImageParser.isVersionTag(tag), tag)
            assertTrue(ImageParser.isPrerelease(tag), tag)
        }
    }

    @Test
    fun `compareVersions handles different length versions`() {
        assertTrue((listOf(2, 0, 0) > listOf(1, 0, 0)))
        assertTrue((listOf(1, 0, 0) < listOf(1, 1, 0)))
        assertEquals(0, (listOf(1, 0, 0).compareTo(listOf(1, 0, 0))))
        // Different lengths
        assertTrue((listOf(1, 0, 1) > listOf(1, 0)))
        assertEquals(0, (listOf(1, 0).compareTo(listOf(1, 0, 0))))
    }

    @Test
    fun `digest methods work correctly`() {
        assertEquals("nginx:latest", ImageParser.removeDigest("nginx:latest@sha256:abc"))
        assertEquals("nginx:latest", ImageParser.removeDigest("nginx:latest"))

        assertEquals("nginx:latest@sha256:abc", ImageParser.addDigest("nginx:latest", "sha256:abc"))
        assertEquals("nginx:latest@sha256:new", ImageParser.addDigest("nginx:latest@sha256:old", "sha256:new"))
    }
}
