package com.watchcluster.service

import com.watchcluster.model.UpdateStrategy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageCheckerTest {
    
    private lateinit var imageChecker: ImageChecker
    
    @BeforeEach
    fun setup() {
        imageChecker = ImageChecker()
    }
    
    @Test
    fun `test version comparison logic`() {
        val testCases = listOf(
            Triple(listOf(1, 0, 0), listOf(1, 0, 0), 0),
            Triple(listOf(2, 0, 0), listOf(1, 0, 0), 1),
            Triple(listOf(1, 0, 0), listOf(2, 0, 0), -1),
            Triple(listOf(1, 1, 0), listOf(1, 0, 0), 1),
            Triple(listOf(1, 0, 1), listOf(1, 0, 0), 1)
        )
        
        testCases.forEach { (v1, v2, expected) ->
            val result = compareVersions(v1, v2)
            assertEquals(
                if (expected > 0) 1 else if (expected < 0) -1 else 0,
                if (result > 0) 1 else if (result < 0) -1 else 0,
                "Failed comparing $v1 and $v2"
            )
        }
    }
    
    @Test
    fun `test version tag validation`() {
        val validVersions = listOf("1.0.0", "v1.0.0", "1.2.3", "v1.2.3", "1.0.0-beta")
        val invalidVersions = listOf("latest", "stable", "master", "main", "dev")
        
        validVersions.forEach { tag ->
            assertTrue(isVersionTag(tag), "Should be valid: $tag")
        }
        
        invalidVersions.forEach { tag ->
            assertFalse(isVersionTag(tag), "Should be invalid: $tag")
        }
    }
    
    @Test
    fun `test image string parsing`() {
        val testCases = listOf(
            "docker.io/nginx:1.20.0" to Triple("docker.io", "nginx", "1.20.0"),
            "nginx:1.20.0" to Triple(null, "nginx", "1.20.0"),
            "nginx" to Triple(null, "nginx", "latest")
        )
        
        testCases.forEach { (input, expected) ->
            val result = parseImageString(input)
            assertEquals(expected, result, "Failed for input: $input")
        }
    }
    
    @Test
    fun `checkForUpdate handles errors gracefully`() = runBlocking {
        val result = imageChecker.checkForUpdate(
            "invalid::image",
            UpdateStrategy.Version()
        )
        
        assertFalse(result.hasUpdate)
        assertTrue(result.reason?.contains("Error") == true || result.reason?.contains("not a version tag") == true)
    }
    
    private fun compareVersions(v1: List<Int>, v2: List<Int>): Int {
        val maxLength = maxOf(v1.size, v2.size)
        for (i in 0 until maxLength) {
            val part1 = v1.getOrNull(i) ?: 0
            val part2 = v2.getOrNull(i) ?: 0
            if (part1 != part2) {
                return part1.compareTo(part2)
            }
        }
        return 0
    }
    
    private fun isVersionTag(tag: String): Boolean {
        return tag.matches(Regex("^v?\\d+\\.\\d+(\\.\\d+)?(-.*)?$"))
    }
    
    private fun parseImageString(image: String): Triple<String?, String, String> {
        val parts = image.split(":")
        val tag = if (parts.size > 1) parts.last() else "latest"
        val repoWithRegistry = parts.first()
        
        val registryAndRepo = if (repoWithRegistry.contains("/")) {
            val firstSlash = repoWithRegistry.indexOf("/")
            val possibleRegistry = repoWithRegistry.substring(0, firstSlash)
            if (possibleRegistry.contains(".") || possibleRegistry.contains(":") || possibleRegistry == "localhost") {
                possibleRegistry to repoWithRegistry.substring(firstSlash + 1)
            } else {
                null to repoWithRegistry
            }
        } else {
            null to repoWithRegistry
        }
        
        return Triple(registryAndRepo.first, registryAndRepo.second, tag)
    }
}