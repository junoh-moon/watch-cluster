package com.watchcluster.service

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerRegistryClientTest {
    private lateinit var mockClient: OkHttpClient
    private lateinit var mockCall: okhttp3.Call
    private lateinit var mockGHCRStrategy: GHCRStrategy
    private lateinit var registryClient: DockerRegistryClient

    @BeforeEach
    fun setup() {
        mockClient = mockk()
        mockCall = mockk()
        mockGHCRStrategy = mockk()

        // Mock the extension function
        mockkStatic("com.watchcluster.service.DockerRegistryClientKt")

        // Create DockerRegistryClient with mocked OkHttpClient and GHCRStrategy
        registryClient =
            DockerRegistryClient().also {
                val clientField = it::class.java.getDeclaredField("client")
                clientField.isAccessible = true
                clientField.set(it, mockClient)

                val ghcrStrategyField = it::class.java.getDeclaredField("ghcrStrategy")
                ghcrStrategyField.isAccessible = true
                ghcrStrategyField.set(it, mockGHCRStrategy)
            }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test getTags from Docker Hub`() =
        runBlocking {
            // Given
            val repository = "nginx"
            val responseBody =
                """
                {
                    "results": [
                        {"name": "1.20.0"},
                        {"name": "1.21.0"},
                        {"name": "latest"}
                    ]
                }
                """.trimIndent()

            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()

            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returns response

            // When
            val tags = registryClient.getTags(null, repository)

            // Then
            assertEquals(listOf("1.20.0", "1.21.0", "latest"), tags)
        }

    @Test
    fun `test getTags from generic registry`() =
        runBlocking {
            // Given
            val registry = "myregistry.com"
            val repository = "myapp"
            val responseBody =
                """
                {
                    "tags": ["v1.0.1", "v1.1.0", "v22.0.0"]
                }
                """.trimIndent()

            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()

            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returns response

            // When
            val tags = registryClient.getTags(registry, repository)

            // Then
            assertEquals(listOf("v1.0.1", "v1.1.0", "v22.0.0"), tags)
        }

    @Test
    fun `test getTags handles API errors`() =
        runBlocking {
            // Given
            val repository = "nginx"

            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("Not Found".toResponseBody("text/plain".toMediaType()))
                    .build()

            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returns response

            // When
            val tags = registryClient.getTags(null, repository)

            // Then
            assertTrue(tags.isEmpty())
        }

    @Test
    fun `test getImageDigest from Docker Hub`() =
        runBlocking {
            // Given
            val repository = "nginx"
            val tag = "1.20.0"
            val expectedDigest = "sha256:abc123"
            val responseBody =
                """
                {
                    "digest": "$expectedDigest"
                }
                """.trimIndent()

            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()

            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returns response

            // When
            val digest = registryClient.getImageDigest(null, repository, tag)

            // Then
            assertEquals(expectedDigest, digest)
        }

    @Test
    fun `test getImageDigest from generic registry using header`() =
        runBlocking {
            // Given
            val registry = "myregistry.com"
            val repository = "myapp"
            val tag = "v1.0.0"
            val expectedDigest = "sha256:def456"

            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Docker-Content-Digest", expectedDigest)
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()

            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returns response

            // When
            val digest = registryClient.getImageDigest(registry, repository, tag)

            // Then
            assertEquals(expectedDigest, digest)
        }

    @Test
    fun `test getGitHubContainerRegistryTags with valid response`() =
        runBlocking {
            // Given
            val repository = "owner/repo"
            val expectedTags = listOf("v1.0.0", "v1.1.0", "v2.0.0", "latest")

            // Mock GHCRStrategy to return expected tags
            coEvery { mockGHCRStrategy.getTags(repository, null) } returns expectedTags

            // When
            val tags = registryClient.getTags("ghcr.io", repository)

            // Then
            assertEquals(expectedTags, tags)
            coVerify { mockGHCRStrategy.getTags(repository, null) }
        }

    @Test
    fun `test getGitHubContainerRegistryDigest with valid response`() =
        runBlocking {
            // Given
            val repository = "owner/repo"
            val tag = "v1.0.0"
            val expectedDigest = "sha256:abc123def456"

            // Mock GHCRStrategy to return expected digest
            coEvery { mockGHCRStrategy.getImageDigest(repository, tag, null) } returns expectedDigest

            // When
            val digest = registryClient.getImageDigest("ghcr.io", repository, tag)

            // Then
            assertEquals(expectedDigest, digest)
            coVerify { mockGHCRStrategy.getImageDigest(repository, tag, null) }
        }

    @Test
    fun `test getGitHubContainerRegistryTags handles authentication error`() =
        runBlocking {
            // Given
            val repository = "owner/repo"

            // Mock GHCRStrategy to return empty list on authentication error
            coEvery { mockGHCRStrategy.getTags(repository, null) } returns emptyList()

            // When
            val tags = registryClient.getTags("ghcr.io", repository)

            // Then
            assertTrue(tags.isEmpty())
            coVerify { mockGHCRStrategy.getTags(repository, null) }
        }

}
