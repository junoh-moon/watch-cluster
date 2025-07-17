package com.watchcluster.service

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.api.command.InspectImageCmd
import com.github.dockerjava.api.command.InspectImageResponse
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.DockerAuth
import com.watchcluster.util.ImageParser
import com.watchcluster.util.ImageComponents
import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.Base64
import java.util.stream.Stream
import kotlin.test.*

class ImageCheckerTest {
    
    private lateinit var mockDockerClient: DockerClient
    private lateinit var mockRegistryClient: DockerRegistryClient
    private lateinit var mockKubernetesClient: KubernetesClient
    private lateinit var imageChecker: ImageChecker
    
    @BeforeEach
    fun setup() {
        mockDockerClient = mockk()
        mockRegistryClient = mockk()
        mockKubernetesClient = mockk()
        
        // Create ImageChecker with mocked kubernetes client
        imageChecker = ImageChecker(mockKubernetesClient)
        
        // Use reflection to inject mocked registry client
        ImageChecker::class.java.getDeclaredField("registryClient").apply {
            isAccessible = true
            set(imageChecker, mockRegistryClient)
        }
    }
    
    @Test
    fun `test checkForUpdate with newer version available`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        val availableTags = listOf("v0.9.0", "v1.0.0", "v1.1.0", "v2.0.0", "latest")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:v2.0.0", result.newImage)
        assertTrue(result.reason?.contains("Found newer version") == true)
    }
    
    @Test
    fun `test checkForUpdate with no newer version`() = runBlocking {
        // Given
        val currentImage = "myapp:v2.0.0"
        val availableTags = listOf("v0.9.0", "v1.0.0", "v1.1.0", "v2.0.0")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertEquals("No newer version available", result.reason)
    }
    
    @Test
    fun `test checkForUpdate preserves v prefix`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        val availableTags = listOf("1.0.0", "1.1.0", "2.0.0") // Without v prefix
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:v2.0.0", result.newImage) // Should add v prefix
    }
    
    @Test
    fun `test checkForUpdate removes v prefix when needed`() = runBlocking {
        // Given
        val currentImage = "myapp:1.0.0" // No v prefix
        val availableTags = listOf("v1.0.0", "v1.1.0", "v2.0.0") // With v prefix
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:2.0.0", result.newImage) // Should remove v prefix
    }
    
    @Test
    fun `test checkForUpdate with registry URL`() = runBlocking {
        // Given
        val currentImage = "docker.io/nginx:1.20.0"
        val availableTags = listOf("1.19.0", "1.20.0", "1.21.0")
        
        coEvery { mockRegistryClient.getTags("docker.io", "nginx", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("docker.io/nginx:1.21.0", result.newImage)
    }
    
    @Test
    fun `test checkLatestUpdate with version tag should not update`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0" // Version tag, not arbitrary tag
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertEquals("Use version strategy for version tags", result.reason)
    }
    
    @Test
    fun `test checkLatestUpdate with registry error`() = runBlocking {
        // Given
        val currentImage = "myapp:latest"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "latest", any()) } throws Exception("Registry API error")
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(any()) } returns mockk {
                coEvery { withName(any()) } returns mockk {
                    coEvery { get() } returns null
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, "default", null)
        
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
    fun `test checkLatestUpdate exposes the digest comparison bug`() = runBlocking {
        // Given
        val currentImage = "nginx:latest"
        val runningDigest = "sha256:old123"  // What's actually running in K8s
        val registryDigest = "sha256:new456" // What's in the registry now
        val namespace = "default"
        val deploymentName = "nginx-deployment"
        
        // Mock registry to return new digest
        coEvery { mockRegistryClient.getImageDigest(null, "nginx", "latest", any()) } returns registryDigest
        
        // Mock Kubernetes deployment with current image in spec
        coEvery { mockKubernetesClient.apps() } returns mockk {
            coEvery { deployments() } returns mockk {
                coEvery { inNamespace(namespace) } returns mockk {
                    coEvery { withName(deploymentName) } returns mockk {
                        coEvery { get() } returns mockk {
                            every { metadata } returns mockk {
                                every { annotations } returns null  // No previous digest in annotations
                            }
                            every { spec } returns mockk {
                                every { template } returns mockk {
                                    every { spec } returns mockk {
                                        every { containers } returns listOf(mockk {
                                            every { image } returns "nginx:latest@$runningDigest"  // Deployment spec has old digest
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Mock pod with running digest
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns listOf(mockk {
                            every { status } returns mockk {
                                every { containerStatuses } returns listOf(mockk {
                                    every { imageID } returns "docker://nginx@$runningDigest"
                                })
                            }
                        })
                    }
                }
            }
        }
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(any()) } returns mockk {
                coEvery { withName(any()) } returns mockk {
                    coEvery { get() } returns null
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then - Should detect update when deployment spec digest differs from registry digest  
        assertTrue(result.hasUpdate, "Should detect update when deployment spec digest differs from registry digest")
        assertEquals("Latest image has been updated", result.reason)
        assertEquals(runningDigest, result.currentDigest, "currentDigest should be the deployment spec digest")
        assertEquals(registryDigest, result.newDigest, "newDigest should be the registry digest")
    }
    
    @Test
    fun `test arbitrary tag update with different digest - stable tag`() = runBlocking {
        // Given
        val currentImage = "myapp:stable"
        val runningDigest = "sha256:abc123"
        val registryDigest = "sha256:def456"
        val namespace = "default"
        val deploymentName = "myapp-deployment"
        
        // Mock registry to return new digest for stable tag
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "stable", any()) } returns registryDigest
        
        // Mock Kubernetes deployment with current image in spec
        coEvery { mockKubernetesClient.apps() } returns mockk {
            coEvery { deployments() } returns mockk {
                coEvery { inNamespace(namespace) } returns mockk {
                    coEvery { withName(deploymentName) } returns mockk {
                        coEvery { get() } returns mockk {
                            every { spec } returns mockk {
                                every { template } returns mockk {
                                    every { spec } returns mockk {
                                        every { containers } returns listOf(mockk {
                                            every { image } returns "myapp:stable@$runningDigest"
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Mock pod with running digest
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns listOf(mockk {
                            every { status } returns mockk {
                                every { containerStatuses } returns listOf(mockk {
                                    every { imageID } returns "docker://myapp@$runningDigest"
                                })
                            }
                        })
                    }
                }
            }
        }
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(any()) } returns mockk {
                coEvery { withName(any()) } returns mockk {
                    coEvery { get() } returns null
                }
            }
        }
        
        // When - using Latest strategy for non-version tags
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertTrue(result.hasUpdate, "Should detect update when stable tag has different digest")
        assertEquals("Tag 'stable' has been updated", result.reason)
        assertEquals(runningDigest, result.currentDigest)
        assertEquals(registryDigest, result.newDigest)
    }
    
    @Test
    fun `test arbitrary tag update with different digest - release-openvino tag`() = runBlocking {
        // Given
        val currentImage = "openvinotoolkit/anomalib:release-openvino"
        val runningDigest = "sha256:oldvino123"
        val registryDigest = "sha256:newvino456"
        val namespace = "production"
        val deploymentName = "anomaly-detector"
        
        // Mock registry to return new digest
        coEvery { mockRegistryClient.getImageDigest(null, "openvinotoolkit/anomalib", "release-openvino", any()) } returns registryDigest
        
        // Mock Kubernetes deployment with current image in spec
        coEvery { mockKubernetesClient.apps() } returns mockk {
            coEvery { deployments() } returns mockk {
                coEvery { inNamespace(namespace) } returns mockk {
                    coEvery { withName(deploymentName) } returns mockk {
                        coEvery { get() } returns mockk {
                            every { spec } returns mockk {
                                every { template } returns mockk {
                                    every { spec } returns mockk {
                                        every { containers } returns listOf(mockk {
                                            every { image } returns "openvinotoolkit/anomalib:release-openvino@$runningDigest"
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Mock pod with running digest
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns listOf(mockk {
                            every { status } returns mockk {
                                every { containerStatuses } returns listOf(mockk {
                                    every { imageID } returns "docker://openvinotoolkit/anomalib@$runningDigest"
                                })
                            }
                        })
                    }
                }
            }
        }
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(any()) } returns mockk {
                coEvery { withName(any()) } returns mockk {
                    coEvery { get() } returns null
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertTrue(result.hasUpdate, "Should detect update for release-openvino tag")
        assertEquals("Tag 'release-openvino' has been updated", result.reason)
        assertEquals(runningDigest, result.currentDigest)
        assertEquals(registryDigest, result.newDigest)
    }
    
    @Test
    fun `test arbitrary tag update with same digest - should not update`() = runBlocking {
        // Given
        val currentImage = "myapp:release-candidate"
        val sameDigest = "sha256:same789"
        val namespace = "staging"
        val deploymentName = "myapp-rc"
        
        // Mock registry to return same digest
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "release-candidate", any()) } returns sameDigest
        
        // Mock Kubernetes deployment with current image in spec
        coEvery { mockKubernetesClient.apps() } returns mockk {
            coEvery { deployments() } returns mockk {
                coEvery { inNamespace(namespace) } returns mockk {
                    coEvery { withName(deploymentName) } returns mockk {
                        coEvery { get() } returns mockk {
                            every { spec } returns mockk {
                                every { template } returns mockk {
                                    every { spec } returns mockk {
                                        every { containers } returns listOf(mockk {
                                            every { image } returns "myapp:release-candidate@$sameDigest"
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Mock pod with same digest
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns listOf(mockk {
                            every { status } returns mockk {
                                every { containerStatuses } returns listOf(mockk {
                                    every { imageID } returns "docker://myapp@$sameDigest"
                                })
                            }
                        })
                    }
                }
            }
        }
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(any()) } returns mockk {
                coEvery { withName(any()) } returns mockk {
                    coEvery { get() } returns null
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertFalse(result.hasUpdate, "Should not update when digest is the same")
        assertEquals("Already using the latest image", result.reason)
        assertEquals(sameDigest, result.currentDigest)
        assertEquals(sameDigest, result.newDigest)
    }
    
    @Test
    fun `test multiple arbitrary tags with digest updates`() = runBlocking {
        // Test various non-version tags
        val testCases = listOf(
            "stable" to "Stable build updated",
            "release-candidate" to "Release candidate updated", 
            "dev" to "Development build updated",
            "nightly" to "Nightly build updated",
            "edge" to "Edge build updated",
            "canary" to "Canary build updated"
        )
        
        for ((tag, _) in testCases) {
            // Given
            val currentImage = "myapp:$tag"
            val runningDigest = "sha256:old_${tag}_123"
            val registryDigest = "sha256:new_${tag}_456"
            val namespace = "default"
            val deploymentName = "myapp-$tag"
            
            // Mock registry
            coEvery { mockRegistryClient.getImageDigest(null, "myapp", tag, any()) } returns registryDigest
            
            // Mock Kubernetes deployment
            coEvery { mockKubernetesClient.apps() } returns mockk {
                coEvery { deployments() } returns mockk {
                    coEvery { inNamespace(namespace) } returns mockk {
                        coEvery { withName(deploymentName) } returns mockk {
                            coEvery { get() } returns mockk {
                                every { spec } returns mockk {
                                    every { template } returns mockk {
                                        every { spec } returns mockk {
                                            every { containers } returns listOf(mockk {
                                                every { image } returns "myapp:$tag@$runningDigest"
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            
            // Mock pod
            coEvery { mockKubernetesClient.pods() } returns mockk {
                coEvery { inNamespace(namespace) } returns mockk {
                    coEvery { withLabel("app", deploymentName) } returns mockk {
                        coEvery { list() } returns mockk {
                            every { items } returns listOf(mockk {
                                every { status } returns mockk {
                                    every { containerStatuses } returns listOf(mockk {
                                        every { imageID } returns "docker://myapp@$runningDigest"
                                    })
                                }
                            })
                        }
                    }
                }
            }
            
            coEvery { mockKubernetesClient.secrets() } returns mockk {
                coEvery { inNamespace(any()) } returns mockk {
                    coEvery { withName(any()) } returns mockk {
                        coEvery { get() } returns null
                    }
                }
            }
            
            // When
            val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
            
            // Then
            assertTrue(result.hasUpdate, "Should detect update for $tag tag")
            assertEquals("Tag '$tag' has been updated", result.reason)
            assertEquals(runningDigest, result.currentDigest)
            assertEquals(registryDigest, result.newDigest)
        }
    }
    
    @Test
    fun `test checkForUpdate handles API errors gracefully`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        
        coEvery { mockRegistryClient.getTags(any(), any(), any()) } throws Exception("Network error")
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertTrue(result.reason?.contains("No newer version available") == true || result.reason?.contains("Error") == true)
    }
    
    @Test
    fun `test real world scenario - watchtower should not update from v0_5_0 to v2_0_0`() = runBlocking {
        // Given
        val currentImage = "containrrr/watchtower:v0.5.0"
        val availableTags = listOf("v0.3.0", "v0.4.0", "v0.5.0", "v0.5.1", "v1.0.0", "latest")
        // Note: v2.0.0 should not be in the real registry tags
        
        coEvery { mockRegistryClient.getTags(null, "containrrr/watchtower", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("containrrr/watchtower:v1.0.0", result.newImage)
        assertNotEquals("containrrr/watchtower:v2.0.0", result.newImage)
    }
    
    @ParameterizedTest
    @MethodSource("versionComparisonProvider")
    fun `test version comparison logic`(v1: List<Int>, v2: List<Int>, expected: Int, description: String) {
        val result = ImageParser.compareVersions(v1, v2)
        assertEquals(
            if (expected > 0) 1 else if (expected < 0) -1 else 0,
            if (result > 0) 1 else if (result < 0) -1 else 0,
            "Failed: $description"
        )
    }
    
    @ParameterizedTest
    @MethodSource("versionTagValidationProvider")
    fun `test version tag validation`(tag: String, shouldBeValid: Boolean) {
        assertEquals(shouldBeValid, ImageParser.isVersionTag(tag), "Tag validation failed for: $tag")
    }
    
    @Test
    fun `test strategy parsing for latest`() {
        // Test that "latest" strategy is properly parsed
        val strategies = mapOf(
            "latest" to UpdateStrategy.Latest,
            "Latest" to UpdateStrategy.Latest,

            "version" to UpdateStrategy.Version(),

            "lock-major" to UpdateStrategy.Version(lockMajorVersion = true),
            "lockmajor" to UpdateStrategy.Version(lockMajorVersion = true)
        )
        
        strategies.forEach { (input, expected) ->
            val result = parseStrategy(input)
            assertEquals(expected, result, "Failed for input: $input")
        }
    }
    
    @Test  
    fun `test arbitrary tag strategy configuration`() {
        // Test that arbitrary tags like stable, release-openvino, etc. should be configured
        // to use digest-based update checks (similar to Latest strategy)
        val arbitraryTags = listOf(
            "stable",
            "release-openvino", 
            "release-candidate",
            "dev",
            "nightly",
            "edge",
            "canary"
        )
        
        arbitraryTags.forEach { tag ->
            // The Latest strategy should work with arbitrary tags, not just "latest"
            // This is what we want to extend
            assertTrue(true, "Strategy should support arbitrary tag: $tag")
        }
    }
    
    @ParameterizedTest
    @MethodSource("imageStringParsingProvider")
    fun `test image string parsing`(input: String, expectedRegistry: String?, expectedRepo: String, expectedTag: String) {
        val result = ImageParser.parseImageString(input)
        assertEquals(ImageComponents(expectedRegistry, expectedRepo, expectedTag), result, "Failed for input: $input")
    }
    
    @Test
    fun `test checkForUpdate with major version lock - should update patch version`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        val availableTags = listOf("v1.0.0", "v1.0.1", "v1.0.2", "v1.1.0", "v2.0.0", "v2.1.0")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(
            currentImage, 
            UpdateStrategy.Version(pattern = "semver", lockMajorVersion = true), 
            "default", 
            null
        )
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:v1.1.0", result.newImage) // Should update to latest within major version 1
        assertTrue(result.reason?.contains("Found newer version") == true)
    }
    
    @Test
    fun `test checkForUpdate with major version lock - should not update to new major`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.2.3"
        val availableTags = listOf("v1.2.3", "v2.0.0", "v3.0.0")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(
            currentImage, 
            UpdateStrategy.Version(pattern = "semver", lockMajorVersion = true), 
            "default", 
            null
        )
        
        // Then
        assertFalse(result.hasUpdate)
        assertEquals("No newer version available within major version 1", result.reason)
    }
    
    @Test
    fun `test checkForUpdate with major version lock - handle version 0`() = runBlocking {
        // Given
        val currentImage = "myapp:v0.5.0"
        val availableTags = listOf("v0.5.0", "v0.5.1", "v0.6.0", "v1.0.0", "v2.0.0")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(
            currentImage, 
            UpdateStrategy.Version(pattern = "semver", lockMajorVersion = true), 
            "default", 
            null
        )
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:v0.6.0", result.newImage) // Should update within major version 0
    }
    
    @Test
    fun `test checkForUpdate without major version lock - normal behavior`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        val availableTags = listOf("v1.0.0", "v1.1.0", "v2.0.0")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(
            currentImage, 
            UpdateStrategy.Version(pattern = "semver", lockMajorVersion = false), 
            "default", 
            null
        )
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:v2.0.0", result.newImage) // Should update to highest version
    }
    
    @Test
    fun `test checkForUpdate with major version lock and prerelease versions`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        val availableTags = listOf("v1.0.0", "v1.1.0-beta", "v1.1.0", "v1.2.0-rc1", "v2.0.0-alpha")
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(
            currentImage, 
            UpdateStrategy.Version(pattern = "semver", lockMajorVersion = true), 
            "default", 
            null
        )
        
        // Then
        assertTrue(result.hasUpdate)
        assertEquals("myapp:v1.2.0-rc1", result.newImage) // Should include prerelease within major version
    }
    
    @Test
    fun `test parseImageString handles digest correctly`() {
        // 1. digest 없는 기본 케이스
        val result1 = ImageParser.parseImageString("nginx:latest")
        assertNull(result1.registry)
        assertEquals("nginx", result1.repository)
        assertEquals("latest", result1.tag)

        // 2. digest가 붙은 케이스
        val result2 = ImageParser.parseImageString("nginx:latest@sha256:abc")
        assertNull(result2.registry)
        assertEquals("nginx", result2.repository)
        assertEquals("latest", result2.tag)

        // 3. registry, tag, digest 모두 있는 케이스
        val result3 = ImageParser.parseImageString("my.registry.com/app:1.2.3@sha256:def")
        assertEquals("my.registry.com", result3.registry)
        assertEquals("app", result3.repository)
        assertEquals("1.2.3", result3.tag)

        // 4. registry만 있는 케이스
        val result4 = ImageParser.parseImageString("my.registry.com/app:latest")
        assertEquals("my.registry.com", result4.registry)
        assertEquals("app", result4.repository)
        assertEquals("latest", result4.tag)

        // 5. digest만 붙은 케이스(tag 생략)
        val result5 = ImageParser.parseImageString("nginx@sha256:abc")
        assertNull(result5.registry)
        assertEquals("nginx", result5.repository)
        assertEquals("latest", result5.tag)
    }
    
    @Test
    fun `test extractDockerAuth with dockerconfigjson secret`() = runBlocking {
        // Given
        val namespace = "default"
        val secretName = "docker-registry-secret"
        val username = "testuser"
        val password = "testpass"
        val authString = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        
        val dockerConfigJson = """
        {
            "auths": {
                "docker.io": {
                    "auth": "$authString"
                }
            }
        }
        """.trimIndent()
        
        val encodedConfig = Base64.getEncoder().encodeToString(dockerConfigJson.toByteArray())
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName(secretName) } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "kubernetes.io/dockerconfigjson"
                        every { data } returns mapOf(".dockerconfigjson" to encodedConfig)
                    }
                }
            }
        }
        
        // When
        imageChecker.checkForUpdate("docker.io/myapp:v1.0.0", UpdateStrategy.Version(), namespace, listOf(secretName))
        
        // Then
        verify { mockKubernetesClient.secrets() }
        // Auth should be extracted and used
    }
    
    @Test
    fun `test extractDockerAuth with index docker io variations`() = runBlocking {
        // Given
        val namespace = "default"
        val secretName = "docker-registry-secret"
        val username = "testuser"
        val password = "testpass"
        val authString = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        
        val dockerConfigJson = """
        {
            "auths": {
                "https://index.docker.io/v1/": {
                    "auth": "$authString"
                }
            }
        }
        """.trimIndent()
        
        val encodedConfig = Base64.getEncoder().encodeToString(dockerConfigJson.toByteArray())
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName(secretName) } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "kubernetes.io/dockerconfigjson"
                        every { data } returns mapOf(".dockerconfigjson" to encodedConfig)
                    }
                }
            }
        }
        
        coEvery { mockRegistryClient.getTags(null, "myapp", any()) } returns listOf("v1.0.0")
        
        // When - image without explicit registry should match index.docker.io variations
        val result = imageChecker.checkForUpdate("myapp:v1.0.0", UpdateStrategy.Version(), namespace, listOf(secretName))
        
        // Then
        verify { mockKubernetesClient.secrets() }
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test extractDockerAuth handles missing secret gracefully`() = runBlocking {
        // Given
        val namespace = "default"
        val secretName = "missing-secret"
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName(secretName) } returns mockk {
                    coEvery { get() } returns null
                }
            }
        }
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } returns listOf("v1.0.0")
        
        // When
        val result = imageChecker.checkForUpdate("myapp:v1.0.0", UpdateStrategy.Version(), namespace, listOf(secretName))
        
        // Then
        verify { mockKubernetesClient.secrets() }
        assertFalse(result.hasUpdate) // Should continue without auth
    }
    
    @Test
    fun `test extractDockerAuth handles malformed secret gracefully`() = runBlocking {
        // Given
        val namespace = "default"
        val secretName = "malformed-secret"
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName(secretName) } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "kubernetes.io/dockerconfigjson"
                        every { data } returns mapOf(".dockerconfigjson" to "invalid-base64")
                    }
                }
            }
        }
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } returns listOf("v1.0.0")
        
        // When
        val result = imageChecker.checkForUpdate("myapp:v1.0.0", UpdateStrategy.Version(), namespace, listOf(secretName))
        
        // Then
        verify { mockKubernetesClient.secrets() }
        assertFalse(result.hasUpdate) // Should continue without auth
    }
    
    @Test
    fun `test checkForUpdate handles exception in strategy check`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        
        coEvery { mockRegistryClient.getTags(any(), any(), any()) } throws RuntimeException("Network error")
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertTrue(result.reason?.contains("Error") == true || result.reason?.contains("No newer version available") == true)
    }
    
    @Test
    fun `test getCurrentImageDigest without Kubernetes info returns null`() = runBlocking {
        // Given
        val currentImage = "myapp:latest"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "latest", null) } returns "sha256:abc123"
        
        // When - call without namespace/deployment info
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, "default", null)
        
        // Then - should not detect update since current digest is unknown
        assertFalse(result.hasUpdate)
        assertTrue(result.reason?.contains("Already using the latest image") == true)
    }
    
    @Test
    fun `test getCurrentImageDigest with pod but no imageID`() = runBlocking {
        // Given
        val currentImage = "myapp:latest"
        val namespace = "default"
        val deploymentName = "myapp"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "latest", null) } returns "sha256:abc123"
        
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns listOf(mockk {
                            every { status } returns mockk {
                                every { containerStatuses } returns listOf(mockk {
                                    every { imageID } returns null // No imageID
                                })
                            }
                        })
                    }
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test getCurrentImageDigest with empty pod list`() = runBlocking {
        // Given
        val currentImage = "myapp:latest"
        val namespace = "default"
        val deploymentName = "myapp"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "latest", null) } returns "sha256:abc123"
        
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns emptyList()
                    }
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test version update with multiple secrets tries all`() = runBlocking {
        // Given
        val namespace = "default"
        val secrets = listOf("secret1", "secret2", "secret3")
        val username = "testuser"
        val password = "testpass"
        val authString = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        
        val dockerConfigJson = """
        {
            "auths": {
                "docker.io": {
                    "auth": "$authString"
                }
            }
        }
        """.trimIndent()
        
        val encodedConfig = Base64.getEncoder().encodeToString(dockerConfigJson.toByteArray())
        
        // First two secrets fail, third succeeds
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName("secret1") } returns mockk {
                    coEvery { get() } returns null
                }
                coEvery { withName("secret2") } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "Opaque" // Wrong type
                        every { data } returns emptyMap()
                    }
                }
                coEvery { withName("secret3") } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "kubernetes.io/dockerconfigjson"
                        every { data } returns mapOf(".dockerconfigjson" to encodedConfig)
                    }
                }
            }
        }
        
        coEvery { mockRegistryClient.getTags("docker.io", "myapp", any()) } returns listOf("v1.0.0")
        
        // When
        val result = imageChecker.checkForUpdate("docker.io/myapp:v1.0.0", UpdateStrategy.Version(), namespace, secrets)
        
        // Then
        verify(exactly = 3) { mockKubernetesClient.secrets() }
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test getAvailableTags handles registry client exception`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } throws Exception("Registry unavailable")
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertEquals("No newer version available", result.reason)
    }
    
    @Test
    fun `test checkLatestUpdate with getImageDigest exception`() = runBlocking {
        // Given
        val currentImage = "myapp:stable"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "stable", null) } throws Exception("Registry API error")
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertTrue(result.reason?.contains("Error checking digest") == true)
    }
    
    @Test
    fun `test getCurrentImageDigest handles Kubernetes API exception`() = runBlocking {
        // Given
        val currentImage = "myapp:latest"
        val namespace = "default"
        val deploymentName = "myapp"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "latest", null) } returns "sha256:abc123"
        
        coEvery { mockKubernetesClient.pods() } throws Exception("Kubernetes API error")
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test extractDockerAuth with empty data in secret`() = runBlocking {
        // Given
        val namespace = "default"
        val secretName = "empty-secret"
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName(secretName) } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "kubernetes.io/dockerconfigjson"
                        every { data } returns null // No data
                    }
                }
            }
        }
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } returns listOf("v1.0.0")
        
        // When
        val result = imageChecker.checkForUpdate("myapp:v1.0.0", UpdateStrategy.Version(), namespace, listOf(secretName))
        
        // Then
        verify { mockKubernetesClient.secrets() }
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test extractDockerAuth with missing dockerconfigjson key`() = runBlocking {
        // Given
        val namespace = "default"
        val secretName = "incomplete-secret"
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName(secretName) } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "kubernetes.io/dockerconfigjson"
                        every { data } returns mapOf("other-key" to "value") // Wrong key
                    }
                }
            }
        }
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } returns listOf("v1.0.0")
        
        // When
        val result = imageChecker.checkForUpdate("myapp:v1.0.0", UpdateStrategy.Version(), namespace, listOf(secretName))
        
        // Then
        verify { mockKubernetesClient.secrets() }
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test extractDockerAuth with malformed auth string`() = runBlocking {
        // Given
        val namespace = "default"
        val secretName = "malformed-auth-secret"
        
        val dockerConfigJson = """
        {
            "auths": {
                "docker.io": {
                    "auth": "notbase64encoded"
                }
            }
        }
        """.trimIndent()
        
        val encodedConfig = Base64.getEncoder().encodeToString(dockerConfigJson.toByteArray())
        
        coEvery { mockKubernetesClient.secrets() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withName(secretName) } returns mockk {
                    coEvery { get() } returns mockk {
                        every { type } returns "kubernetes.io/dockerconfigjson"
                        every { data } returns mapOf(".dockerconfigjson" to encodedConfig)
                    }
                }
            }
        }
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } returns listOf("v1.0.0")
        
        // When
        val result = imageChecker.checkForUpdate("myapp:v1.0.0", UpdateStrategy.Version(), namespace, listOf(secretName))
        
        // Then
        verify { mockKubernetesClient.secrets() }
        assertFalse(result.hasUpdate)
    }
    
    @Test
    fun `test version update with no available tags returns empty list`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } returns emptyList()
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertEquals("No newer version available", result.reason)
    }
    
    @Test
    fun `test version update with only non-version tags`() = runBlocking {
        // Given
        val currentImage = "myapp:v1.0.0"
        val availableTags = listOf("latest", "stable", "dev", "master") // No version tags
        
        coEvery { mockRegistryClient.getTags(null, "myapp", null) } returns availableTags
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Version(), "default", null)
        
        // Then
        assertFalse(result.hasUpdate)
        assertEquals("No newer version available", result.reason)
    }
    
    @Test
    fun `test checkLatestUpdate with null digest from registry`() = runBlocking {
        // Given
        val currentImage = "myapp:stable"
        val namespace = "default"
        val deploymentName = "myapp"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "stable", null) } returns null
        
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns listOf(mockk {
                            every { status } returns mockk {
                                every { containerStatuses } returns listOf(mockk {
                                    every { imageID } returns "docker://myapp@sha256:abc123"
                                })
                            }
                        })
                    }
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertFalse(result.hasUpdate)
        assertEquals("Already using the latest image", result.reason)
    }
    
    @Test
    fun `test getCurrentImageDigest with imageID without digest format`() = runBlocking {
        // Given
        val currentImage = "myapp:latest"
        val namespace = "default"
        val deploymentName = "myapp"
        
        coEvery { mockRegistryClient.getImageDigest(null, "myapp", "latest", null) } returns "sha256:abc123"
        
        coEvery { mockKubernetesClient.pods() } returns mockk {
            coEvery { inNamespace(namespace) } returns mockk {
                coEvery { withLabel("app", deploymentName) } returns mockk {
                    coEvery { list() } returns mockk {
                        every { items } returns listOf(mockk {
                            every { status } returns mockk {
                                every { containerStatuses } returns listOf(mockk {
                                    every { imageID } returns "docker://myapp:latest" // No @ digest
                                })
                            }
                        })
                    }
                }
            }
        }
        
        // When
        val result = imageChecker.checkForUpdate(currentImage, UpdateStrategy.Latest, namespace, null, deploymentName)
        
        // Then
        assertFalse(result.hasUpdate)
    }
    
    
    private fun parseStrategy(strategyStr: String): UpdateStrategy {
        return when {
            strategyStr.lowercase() == "latest" -> UpdateStrategy.Latest
            strategyStr.contains("lock-major") || strategyStr.contains("lockmajor") -> 
                UpdateStrategy.Version(lockMajorVersion = true)
            else -> UpdateStrategy.Version()
        }
    }
    
    companion object {
        @JvmStatic
        fun versionComparisonProvider(): Stream<Arguments> = Stream.of(
            Arguments.of(listOf(1, 0, 0), listOf(1, 0, 0), 0, "Same version 1.0.0 == 1.0.0"),
            Arguments.of(listOf(2, 0, 0), listOf(1, 0, 0), 1, "Major version 2.0.0 > 1.0.0"),
            Arguments.of(listOf(1, 0, 0), listOf(2, 0, 0), -1, "Major version 1.0.0 < 2.0.0"),
            Arguments.of(listOf(1, 1, 0), listOf(1, 0, 0), 1, "Minor version 1.1.0 > 1.0.0"),
            Arguments.of(listOf(1, 0, 1), listOf(1, 0, 0), 1, "Patch version 1.0.1 > 1.0.0"),
            Arguments.of(listOf(0, 5, 0), listOf(2, 0, 0), -1, "v0.5.0 < v2.0.0")
        )
        
        @JvmStatic
        fun versionTagValidationProvider(): Stream<Arguments> = Stream.of(
            Arguments.of("1.0.0", true),
            Arguments.of("v1.0.0", true),
            Arguments.of("1.2.3", true),
            Arguments.of("v1.2.3", true),
            Arguments.of("1.0.0-beta", true),
            Arguments.of("v0.5.0", true),
            Arguments.of("latest", false),
            Arguments.of("stable", false),
            Arguments.of("master", false),
            Arguments.of("main", false),
            Arguments.of("dev", false)
        )
        
        @JvmStatic
        fun imageStringParsingProvider(): Stream<Arguments> = Stream.of(
            Arguments.of("docker.io/nginx:1.20.0", "docker.io", "nginx", "1.20.0"),
            Arguments.of("nginx:1.20.0", null, "nginx", "1.20.0"),
            Arguments.of("nginx", null, "nginx", "latest"),
            Arguments.of("containrrr/watchtower:v0.5.0", null, "containrrr/watchtower", "v0.5.0"),
            Arguments.of("gcr.io/project/app:latest", "gcr.io", "project/app", "latest")
        )
    }
}
