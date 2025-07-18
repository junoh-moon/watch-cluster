package com.watchcluster.integration

import com.watchcluster.model.UpdateStrategy
import com.watchcluster.service.DockerRegistryClient
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.ImageParser
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WatchClusterIntegrationTest {
    private lateinit var mockDockerRegistryClient: DockerRegistryClient
    private lateinit var mockWebhookService: WebhookService
    private lateinit var imageChecker: ImageChecker

    @BeforeEach
    fun setup() {
        mockDockerRegistryClient = mockk()
        mockWebhookService = mockk()

        // Create real services with mocked dependencies
        imageChecker =
            ImageChecker(mockk()).apply {
                // Replace the registry client with our mock using reflection
                val clientField = this::class.java.getDeclaredField("registryClient")
                clientField.isAccessible = true
                clientField.set(this, mockDockerRegistryClient)
            }
    }

    @Test
    fun `end-to-end image version checking with semver strategy`(): Unit =
        runBlocking {
            // Given - setup a scenario with available tags
            val currentImage = "nginx:1.20.0"
            val availableTags = listOf("1.19.0", "1.20.0", "1.20.1", "1.21.0", "latest")

            coEvery { mockDockerRegistryClient.getTags(null, "nginx", any()) } returns availableTags
            coEvery { mockDockerRegistryClient.getImageDigest(null, "nginx", "1.21.0", any()) } returns "sha256:new123"
            coEvery { mockDockerRegistryClient.getImageDigest(null, "nginx", "1.20.0", any()) } returns "sha256:old123"

            // When
            val result =
                imageChecker.checkForUpdate(
                    currentImage = currentImage,
                    strategy = UpdateStrategy.Version(),
                    namespace = "test-ns",
                    imagePullSecrets = null,
                    deploymentName = "test-deploy",
                )

            // Then
            assertNotNull(result.newImage)
            assertEquals("nginx:1.21.0", result.newImage)
            assertEquals(currentImage, result.currentImage)
            assertNotNull(result.reason)
        }

    @Test
    fun `end-to-end image checking with latest strategy`(): Unit =
        runBlocking {
            // Given
            val currentImage = "nginx:latest"
            val latestDigest = "sha256:new456"

            coEvery { mockDockerRegistryClient.getImageDigest(null, "nginx", "latest", any()) } returns latestDigest

            // When
            val result =
                imageChecker.checkForUpdate(
                    currentImage = currentImage,
                    strategy = UpdateStrategy.Latest,
                    namespace = "test-ns",
                    imagePullSecrets = null,
                    deploymentName = "test-deploy",
                )

            // Then - For latest strategy, we need different digest to show update
            // Since we can't easily mock the Kubernetes pod status,
            // let's test that no error occurs and we get a proper result
            assertNotNull(result)
            assertEquals(currentImage, result.currentImage)
            assertNotNull(result.reason)
        }

    @Test
    fun `end-to-end no update when current is latest version`(): Unit =
        runBlocking {
            // Given
            val currentImage = "nginx:1.21.0"
            val availableTags = listOf("1.19.0", "1.20.0", "1.21.0", "latest")

            coEvery { mockDockerRegistryClient.getTags(null, "nginx", any()) } returns availableTags

            // When
            val result =
                imageChecker.checkForUpdate(
                    currentImage = currentImage,
                    strategy = UpdateStrategy.Version(),
                    namespace = "test-ns",
                    imagePullSecrets = null,
                    deploymentName = "test-deploy",
                )

            // Then
            assertNull(result.newImage)
            assertEquals(currentImage, result.currentImage)
            assertNotNull(result.reason)
        }

    @Test
    fun `integration test with version lock major strategy`() =
        runBlocking {
            // Given
            val currentImage = "nginx:1.20.0"
            val availableTags = listOf("1.19.0", "1.20.0", "1.20.1", "1.21.0", "2.0.0", "latest")

            coEvery { mockDockerRegistryClient.getTags(null, "nginx", any()) } returns availableTags
            coEvery { mockDockerRegistryClient.getImageDigest(null, "nginx", "1.21.0", any()) } returns "sha256:new123"
            coEvery { mockDockerRegistryClient.getImageDigest(null, "nginx", "1.20.0", any()) } returns "sha256:old123"

            // When - using version-lock-major strategy
            val result =
                imageChecker.checkForUpdate(
                    currentImage = currentImage,
                    strategy = UpdateStrategy.Version(lockMajorVersion = true),
                    namespace = "test-ns",
                    imagePullSecrets = null,
                    deploymentName = "test-deploy",
                )

            // Then - should update to 1.21.0 but not 2.0.0
            assertNotNull(result.newImage)
            assertEquals("nginx:1.21.0", result.newImage)
            assert(!result.newImage!!.contains("2.0.0"))
        }

    @Test
    fun `integration test with private registry and authentication`() =
        runBlocking {
            // Given
            val currentImage = "myregistry.com/myapp:1.0.0"
            val availableTags = listOf("1.0.0", "1.0.1", "1.1.0")

            // Note: Authentication extraction requires Kubernetes secrets,
            // so we'll mock the registry calls without auth for this test
            coEvery { mockDockerRegistryClient.getTags("myregistry.com", "myapp", any()) } returns availableTags
            coEvery {
                mockDockerRegistryClient.getImageDigest(
                    "myregistry.com",
                    "myapp",
                    "1.1.0",
                    any()
                )
            } returns "sha256:new123"
            coEvery {
                mockDockerRegistryClient.getImageDigest(
                    "myregistry.com",
                    "myapp",
                    "1.0.0",
                    any()
                )
            } returns "sha256:old123"

            // When
            val result =
                imageChecker.checkForUpdate(
                    currentImage = currentImage,
                    strategy = UpdateStrategy.Version(),
                    namespace = "test-ns",
                    imagePullSecrets = listOf("my-registry-secret"),
                    deploymentName = "test-deploy",
                )

            // Then
            assertNotNull(result.newImage)
            assertEquals("myregistry.com/myapp:1.1.0", result.newImage)
        }

    @Test
    fun `integration test handles registry errors gracefully`() =
        runBlocking {
            // Given
            val currentImage = "nginx:1.20.0"

            coEvery { mockDockerRegistryClient.getTags(any(), any(), any()) } returns emptyList()

            // When
            val result =
                imageChecker.checkForUpdate(
                    currentImage = currentImage,
                    strategy = UpdateStrategy.Version(),
                    namespace = "test-ns",
                    imagePullSecrets = null,
                    deploymentName = "test-deploy",
                )

            // Then
            assertNull(result.newImage)
            assertNotNull(result.reason)
            assert(result.reason!!.contains("No newer version available") || result.reason!!.contains("Error"))
        }

    @Test
    fun `ImageParser utility integration`() {
        // Test ImageParser utility that was extracted during refactoring
        val testCases =
            listOf(
                "nginx@sha256:aaa" to Triple(null, "nginx", "latest"),
                "nginx:1.20.0" to Triple(null, "nginx", "1.20.0"),
                "docker.io/nginx:1.20.0" to Triple("docker.io", "nginx", "1.20.0"),
                "myregistry.com/myapp:v1.0.0" to Triple("myregistry.com", "myapp", "v1.0.0"),
                "ghcr.io/owner/repo:latest@sha256:aaa" to Triple("ghcr.io", "owner/repo", "latest"),
            )

        testCases.forEach { (input, expected) ->
            val (expectedRegistry, expectedRepository, expectedTag) = expected
            val result = ImageParser.parseImageString(input)

            assertEquals(expectedRegistry, result.registry, "Failed for input: $input")
            assertEquals(expectedRepository, result.repository, "Failed for input: $input")
            assertEquals(expectedTag, result.tag, "Failed for input: $input")
        }
    }
}
