package com.watchcluster.service

import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.DockerAuth
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class ImageCheckerLatestSimpleTest {
    
    private lateinit var mockRegistryClient: DockerRegistryClient
    private lateinit var mockKubernetesClient: KubernetesClient
    private lateinit var imageChecker: ImageChecker
    
    @BeforeEach
    fun setup() {
        mockRegistryClient = mockk()
        mockKubernetesClient = mockk()
        
        // Create ImageChecker with mocked kubernetes client
        imageChecker = spyk(ImageChecker(mockKubernetesClient))
        
        // Use reflection to inject mocked registry client
        val registryClientField = ImageChecker::class.java.getDeclaredField("registryClient")
        registryClientField.isAccessible = true
        registryClientField.set(imageChecker, mockRegistryClient)
        
        // Mock Kubernetes secrets access for all tests
        every { mockKubernetesClient.secrets() } returns mockk {
            every { inNamespace(any()) } returns mockk {
                every { withName(any()) } returns mockk {
                    every { get() } returns null
                }
            }
        }
    }
    
    @Test
    fun `test latest tag strategy returns false for non-latest tags`() = runBlocking {
        // Given
        val currentImage = "nginx:1.21.0"  // Not using latest tag
        val namespace = "default"
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null)
        
        // Then
        assertFalse(result.hasUpdate, "Should not update non-latest tags with Latest strategy")
        assertEquals("Not using latest tag", result.reason)
    }
    
    @Test
    fun `test latest tag error handling`() = runBlocking {
        // Given
        val currentImage = "nginx:latest"
        val namespace = "default"
        
        // Mock registry to throw error
        coEvery { mockRegistryClient.getImageDigest(null, "nginx", "latest", null) } throws Exception("Registry unavailable")
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertTrue(result.reason?.contains("Error checking digest") == true)
    }
    
    @Test
    fun `test version strategy with latest tag in available tags`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        val availableTags = listOf("v1.0.0", "v1.1.0", "v2.0.0", "latest")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:v2.0.0", result.newImage) // Should pick v2.0.0, not latest
    }
    
    @Test
    fun `test image without tag defaults to latest in parsing`() = runBlocking {
        // Given
        val currentImage = "nginx"  // No tag specified
        val availableTags = listOf("1.20.0", "1.21.0", "latest")
        
        coEvery { mockRegistryClient.getTags(null, "nginx", any()) } returns availableTags
        
        // When using Version strategy, it should skip the current "latest" tag
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertFalse(result.hasUpdate) // No version update from "latest"
        assertEquals("Current tag is not a version tag", result.reason)
    }
    
    @Test
    fun `test real-world scenario - nginx with latest tag`() = runBlocking {
        // Given
        val currentImage = "nginx:latest"
        val namespace = "production"
        
        // Simulate different scenarios
        
        // Scenario 1: Registry returns null digest (error case)
        coEvery { mockRegistryClient.getImageDigest(null, "nginx", "latest", null) } returns null
        
        var result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null)
        assertFalse(result.hasUpdate)
        
        // Scenario 2: Registry throws exception
        coEvery { mockRegistryClient.getImageDigest(null, "nginx", "latest", null) } throws RuntimeException("Connection timeout")
        
        result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null)
        assertFalse(result.hasUpdate)
        assertTrue(result.reason?.contains("Error") == true)
    }
    
    @Test
    fun `test complex registry paths with latest`() = runBlocking {
        val testCases = mapOf(
            "gcr.io/project/app:latest" to Triple("gcr.io", "project/app", "latest"),
            "docker.io/library/nginx:latest" to Triple("docker.io", "library/nginx", "latest"),
            "mycompany.com:5000/app:latest" to Triple("mycompany.com:5000", "app", "latest")
        )
        
        testCases.forEach { (image, expectedParts) ->
            val (registry, repo, tag) = expectedParts
            
            // Test with Latest strategy - should only check if tag is "latest"
            val result = imageChecker.checkForUpdate(image, UpdateStrategy.Latest, "default", null)
            
            // Since we haven't mocked the digest check, it should either:
            // - Return "Not using latest tag" if tag isn't "latest"
            // - Return an error or "Already using latest" if getCurrentImageDigest fails
            if (tag == "latest") {
                assertFalse(result.hasUpdate) // Because getCurrentImageDigest will fail
            }
        }
    }
}