package com.watchcluster.service

import com.watchcluster.model.ImagePlatform
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
import kotlin.test.assertFailsWith
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

            assertFailsWith<IllegalStateException> {
                registryClient.getTags(null, repository)
            }
            Unit
        }

    @Test
    fun `test generic getTags propagates API errors`() =
        runBlocking {
            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(401)
                    .message("Unauthorized")
                    .body("Unauthorized".toResponseBody("text/plain".toMediaType()))
                    .build()
            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returns response

            assertFailsWith<IllegalStateException> {
                registryClient.getTags("registry.example.com", "private/image")
            }
            Unit
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
    fun `test getImageDigest propagates API errors`() =
        runBlocking {
            val response =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("Service Unavailable")
                    .body("Unavailable".toResponseBody("text/plain".toMediaType()))
                    .build()
            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returns response

            assertFailsWith<IllegalStateException> {
                registryClient.getImageDigest(null, "nginx", "latest")
            }
            Unit
        }

    @Test
    fun `test getImageDigest from Docker Hub returns platform manifest digest when platform is specified`() =
        runBlocking {
            // Given
            val repository = "nginx"
            val tag = "alpine"
            val indexDigest = "sha256:index"
            val amd64Digest = "sha256:amd64"
            val arm64Digest = "sha256:arm64"
            val responseBody =
                """
                {
                    "digest": "$indexDigest",
                    "images": [
                        {
                            "architecture": "amd64",
                            "os": "linux",
                            "digest": "$amd64Digest"
                        },
                        {
                            "architecture": "arm64",
                            "os": "linux",
                            "variant": "v8",
                            "digest": "$arm64Digest"
                        }
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
            val digest =
                registryClient.getImageDigest(
                    null,
                    repository,
                    tag,
                    platform = ImagePlatform(os = "linux", architecture = "amd64"),
                )

            // Then
            assertEquals(amd64Digest, digest)
        }

    @Test
    fun `test getImageDigest from Docker Hub resolves digest reference to platform manifest digest`() =
        runBlocking {
            // Given
            val repository = "nginx"
            val indexDigest = "sha256:index"
            val amd64Digest = "sha256:amd64"
            val tokenResponse =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://token").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"token":"test-token"}""".toResponseBody("application/json".toMediaType()))
                    .build()
            val manifestResponse =
                Response
                    .Builder()
                    .request(Request.Builder().url("http://manifest").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Docker-Content-Digest", indexDigest)
                    .body(
                        """
                        {
                            "schemaVersion": 2,
                            "mediaType": "application/vnd.oci.image.index.v1+json",
                            "manifests": [
                                {
                                    "digest": "$amd64Digest",
                                    "platform": {
                                        "architecture": "amd64",
                                        "os": "linux"
                                    }
                                }
                            ]
                        }
                        """.trimIndent().toResponseBody("application/json".toMediaType()),
                    ).build()

            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } returnsMany listOf(tokenResponse, manifestResponse)

            // When
            val digest =
                registryClient.getImageDigest(
                    null,
                    repository,
                    indexDigest,
                    platform = ImagePlatform(os = "linux", architecture = "amd64"),
                )

            // Then
            assertEquals(amd64Digest, digest)
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

    @Test
    fun `push time lookup matches selected digest instead of borrowing another images date`() =
        runBlocking {
            val body =
                """
                {"digest":"sha256:index", "tag_last_pushed":"2026-09-16T07:52:32Z",
                 "last_updated":"2020-01-01T00:00:00Z", "images":[
                   {"digest":"sha256:arm", "last_pushed":"2020-01-01T00:00:00Z"},
                   {"digest":"sha256:amd", "last_pushed":"2026-09-16T01:50:42Z"}
                 ]}
                """.trimIndent()
            every { mockClient.newCall(any()) } returns mockCall
            coEvery { mockCall.await() } answers {
                Response.Builder().request(Request.Builder().url("https://hub.docker.com").build())
                    .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
            assertEquals(
                java.time.Instant.parse("2026-09-16T07:52:32Z"),
                registryClient.getImagePushedAt(null, "nginx", "alpine", "sha256:index"),
            )
            assertEquals(
                java.time.Instant.parse("2026-09-16T01:50:42Z"),
                registryClient.getImagePushedAt("docker.io", "library/nginx", "alpine", "sha256:amd"),
            )
            kotlin.test.assertNull(registryClient.getImagePushedAt(null, "nginx", "alpine", "sha256:old"))
        }

    @Test
    fun `push time lookup rejects null malformed or absent time without using created or last updated`() =
        runBlocking {
            every { mockClient.newCall(any()) } returns mockCall
            for (timestamp in listOf("null", "\"not-a-date\"", "\"\"")) {
                val body =
                    """
                    {"digest":"sha256:index", "tag_last_pushed":$timestamp,
                     "last_updated":"2020-01-01T00:00:00Z", "created":"2020-01-01T00:00:00Z",
                     "images":[{"digest":"sha256:amd"}]}
                    """.trimIndent()
                coEvery { mockCall.await() } answers {
                    Response.Builder().request(Request.Builder().url("https://hub.docker.com").build())
                        .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }
                kotlin.test.assertNull(registryClient.getImagePushedAt(null, "nginx", "latest", "sha256:index"))
                kotlin.test.assertNull(registryClient.getImagePushedAt(null, "nginx", "latest", "sha256:amd"))
            }
        }

    @Test
    fun `push time lookup propagates API failure and passes authentication`() =
        runBlocking {
            val request = io.mockk.slot<Request>()
            every { mockClient.newCall(capture(request)) } returns mockCall
            coEvery { mockCall.await() } returns
                Response.Builder()
                    .request(Request.Builder().url("https://hub.docker.com").build())
                    .protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                    .body("{}".toResponseBody("application/json".toMediaType())).build()
            assertFailsWith<IllegalStateException> {
                registryClient.getImagePushedAt(
                    null,
                    "nginx",
                    "latest",
                    "sha256:index",
                    com.watchcluster.model.DockerAuth("user", "password"),
                )
            }
            assertEquals("https://hub.docker.com/v2/namespaces/library/repositories/nginx/tags/latest", request.captured.url.toString())
            assertEquals(okhttp3.Credentials.basic("user", "password"), request.captured.header("Authorization"))
        }
}
