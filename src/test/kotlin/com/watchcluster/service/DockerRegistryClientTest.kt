package com.watchcluster.service

import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

class DockerRegistryClientTest {
    
    private lateinit var mockClient: OkHttpClient
    private lateinit var mockCall: okhttp3.Call
    private lateinit var registryClient: DockerRegistryClient
    
    @BeforeEach
    fun setup() {
        mockClient = mockk()
        mockCall = mockk()
        
        // Create DockerRegistryClient with mocked OkHttpClient
        registryClient = DockerRegistryClient().also {
            val clientField = it::class.java.getDeclaredField("client")
            clientField.isAccessible = true
            clientField.set(it, mockClient)
        }
    }
    
    @Test
    fun `test getTags from Docker Hub`() = runBlocking {
        // Given
        val repository = "nginx"
        val responseBody = """
            {
                "results": [
                    {"name": "1.20.0"},
                    {"name": "1.21.0"},
                    {"name": "latest"}
                ]
            }
        """.trimIndent()
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val tags = registryClient.getTags(null, repository)
        
        // Then
        assertEquals(listOf("1.20.0", "1.21.0", "latest"), tags)
    }
    
    @Test
    fun `test getTags from generic registry`() = runBlocking {
        // Given
        val registry = "myregistry.com"
        val repository = "myapp"
        val responseBody = """
            {
                "tags": ["v1.0.1", "v1.1.0", "v22.0.0"]
            }
        """.trimIndent()
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val tags = registryClient.getTags(registry, repository)
        
        // Then
        assertEquals(listOf("v1.0.1", "v1.1.0", "v22.0.0"), tags)
    }
    
    @Test
    fun `test getTags handles API errors`() = runBlocking {
        // Given
        val repository = "nginx"
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(404)
            .message("Not Found")
            .body("Not Found".toResponseBody("text/plain".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val tags = registryClient.getTags(null, repository)
        
        // Then
        assertTrue(tags.isEmpty())
    }
    
    @Test
    fun `test getImageDigest from Docker Hub`() = runBlocking {
        // Given
        val repository = "nginx"
        val tag = "1.20.0"
        val expectedDigest = "sha256:abc123"
        val responseBody = """
            {
                "digest": "$expectedDigest"
            }
        """.trimIndent()
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val digest = registryClient.getImageDigest(null, repository, tag)
        
        // Then
        assertEquals(expectedDigest, digest)
    }
    
    @Test
    fun `test getImageDigest from generic registry using header`() = runBlocking {
        // Given
        val registry = "myregistry.com"
        val repository = "myapp"
        val tag = "v1.0.0"
        val expectedDigest = "sha256:def456"
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Docker-Content-Digest", expectedDigest)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val digest = registryClient.getImageDigest(registry, repository, tag)
        
        // Then
        assertEquals(expectedDigest, digest)
    }
    
    
    @Test
    fun `test getGitHubContainerRegistryTags with valid response`() = runBlocking {
        // Given
        val repository = "owner/repo"
        val responseBody = """
            {
                "tags": ["v1.0.0", "v1.1.0", "v2.0.0", "latest"]
            }
        """.trimIndent()
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val tags = registryClient.getTags("ghcr.io", repository)
        
        // Then
        assertEquals(listOf("v1.0.0", "v1.1.0", "v2.0.0", "latest"), tags)
    }
    
    @Test
    fun `test getGitHubContainerRegistryDigest with valid response`() = runBlocking {
        // Given
        val repository = "owner/repo"
        val tag = "v1.0.0"
        val expectedDigest = "sha256:abc123def456"
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Docker-Content-Digest", expectedDigest)
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val digest = registryClient.getImageDigest("ghcr.io", repository, tag)
        
        // Then
        assertEquals(expectedDigest, digest)
    }
    
    @Test
    fun `test getGitHubContainerRegistryTags handles authentication error`() = runBlocking {
        // Given
        val repository = "owner/repo"
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body("Unauthorized".toResponseBody("text/plain".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val tags = registryClient.getTags("ghcr.io", repository)
        
        // Then
        assertTrue(tags.isEmpty())
    }
    
    @Test
    fun `test real world scenario - should not return hardcoded v2_0_0`() = runBlocking {
        // Given
        val repository = "containrrr/watchtower"
        
        // Create a mock response that simulates real Docker Hub data
        val responseBody = """
            {
                "results": [
                    {"name": "latest"},
                    {"name": "v0.3.0"},
                    {"name": "v0.4.0"},
                    {"name": "v0.5.0"},
                    {"name": "v0.5.1"},
                    {"name": "v1.0.0"},
                    {"name": "v1.1.0"},
                    {"name": "v1.2.0"},
                    {"name": "v1.3.0"},
                    {"name": "v1.4.0"},
                    {"name": "v1.5.0"}
                ]
            }
        """.trimIndent()
        
        val response = Response.Builder()
            .request(Request.Builder().url("http://test").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
        
        every { mockClient.newCall(any()) } returns mockCall
        every { mockCall.execute() } returns response
        
        // When
        val tags = registryClient.getTags(null, repository)
        
        // Then
        assertFalse(tags.contains("v2.0.0"), "Should not contain hardcoded v2.0.0")
        assertFalse(tags.contains("2.0.0"), "Should not contain hardcoded 2.0.0")
        assertTrue(tags.contains("v1.5.0"), "Should contain realistic version tags")
    }
}
