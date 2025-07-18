package com.watchcluster.service

import com.watchcluster.model.DeploymentEventData
import com.watchcluster.model.WebhookConfig
import com.watchcluster.model.WebhookEvent
import com.watchcluster.model.WebhookEventType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebhookServiceTest {
    private lateinit var webhookService: WebhookService
    private lateinit var mockHttpClient: HttpClient
    private lateinit var mockHttpResponse: HttpResponse<String>
    private lateinit var mockBuilder: HttpClient.Builder

    @BeforeEach
    fun setup() {
        mockHttpClient = mockk()
        mockHttpResponse = mockk()
        mockBuilder = mockk()

        mockkStatic(HttpClient::class)
        every { HttpClient.newBuilder() } returns mockBuilder
        every { mockBuilder.connectTimeout(any()) } returns mockBuilder
        every { mockBuilder.build() } returns mockHttpClient
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `should send webhook when URL is configured and event type is enabled`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = "https://example.com/webhook",
                    enableDeploymentDetected = true,
                )
            webhookService = WebhookService(config)

            every { mockHttpResponse.statusCode() } returns 200
            every { mockHttpResponse.body() } returns """{"status": "ok"}"""
            every { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
                CompletableFuture.supplyAsync {
                    Thread.sleep(25) // 25ms delay to simulate network latency
                    mockHttpResponse
                }

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "default",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                )

            webhookService.sendWebhook(event)

            verify(exactly = 1) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }
        }

    @Test
    fun `should not send webhook when URL is not configured`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = null,
                    enableDeploymentDetected = true,
                )
            webhookService = WebhookService(config)

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "default",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                )

            webhookService.sendWebhook(event)

            verify(exactly = 0) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }
        }

    @Test
    fun `should not send webhook when event type is not enabled`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = "https://example.com/webhook",
                    enableDeploymentDetected = false,
                    enableImageRolloutStarted = true,
                )
            webhookService = WebhookService(config)

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "default",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                )

            webhookService.sendWebhook(event)

            verify(exactly = 0) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }
        }

    @Test
    fun `should retry on failure with exponential backoff`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = "https://example.com/webhook",
                    enableImageRolloutFailed = true,
                    retryCount = 3,
                )
            webhookService = WebhookService(config)

            var callCount = 0
            every { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } answers {
                callCount++
                CompletableFuture.supplyAsync {
                    Thread.sleep(30) // 30ms delay to simulate network latency
                    when (callCount) {
                        1, 2 -> {
                            every { mockHttpResponse.statusCode() } returns 500
                            every { mockHttpResponse.body() } returns "Server Error"
                            mockHttpResponse
                        }
                        else -> {
                            every { mockHttpResponse.statusCode() } returns 200
                            every { mockHttpResponse.body() } returns """{"status": "ok"}"""
                            mockHttpResponse
                        }
                    }
                }
            }

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.IMAGE_ROLLOUT_FAILED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "default",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                )

            webhookService.sendWebhook(event)

            verify(exactly = 3) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }
        }

    @Test
    fun `should handle timeout and network errors gracefully`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = "https://example.com/webhook",
                    enableImageRolloutCompleted = true,
                    retryCount = 3,
                )
            webhookService = WebhookService(config)

            every { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } returns
                CompletableFuture.supplyAsync {
                    Thread.sleep(40) // 40ms delay to simulate network latency
                    throw RuntimeException("Network error")
                }

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.IMAGE_ROLLOUT_COMPLETED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "default",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                    details = mapOf("error" to "Connection timeout"),
                )

            webhookService.sendWebhook(event)

            verify(exactly = 3) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }
        }

    @Test
    fun `should include custom headers in webhook request`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = "https://example.com/webhook",
                    enableImageRolloutStarted = true,
                    headers =
                        mapOf(
                            "X-Custom-Header" to "custom-value",
                            "Authorization" to "Bearer token123",
                        ),
                )
            webhookService = WebhookService(config)

            every { mockHttpResponse.statusCode() } returns 200
            every { mockHttpResponse.body() } returns """{"status": "ok"}"""

            val capturedRequest = slot<HttpRequest>()
            every { mockHttpClient.sendAsync(capture(capturedRequest), any<HttpResponse.BodyHandler<String>>()) } returns
                CompletableFuture.supplyAsync {
                    Thread.sleep(20) // 20ms delay to simulate network latency
                    mockHttpResponse
                }

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.IMAGE_ROLLOUT_STARTED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "production",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                )

            webhookService.sendWebhook(event)

            verify(exactly = 1) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }

            val request = capturedRequest.captured
            assertTrue(request.headers().firstValue("Content-Type").isPresent)
            assertEquals("application/json", request.headers().firstValue("Content-Type").get())
        }

    @Test
    fun `should respect 429 rate limit with Retry-After header`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = "https://example.com/webhook",
                    enableDeploymentDetected = true,
                    retryCount = 3,
                )
            webhookService = WebhookService(config)

            val mockHeaders = mockk<HttpHeaders>()
            every { mockHeaders.firstValue("Retry-After") } returns Optional.of("5") // 5 seconds

            var callCount = 0
            every { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } answers {
                callCount++
                CompletableFuture.supplyAsync {
                    Thread.sleep(15) // 15ms delay to simulate network latency
                    when (callCount) {
                        1 -> {
                            every { mockHttpResponse.statusCode() } returns 429
                            every { mockHttpResponse.body() } returns "Rate limit exceeded"
                            every { mockHttpResponse.headers() } returns mockHeaders
                            mockHttpResponse
                        }
                        else -> {
                            every { mockHttpResponse.statusCode() } returns 200
                            every { mockHttpResponse.body() } returns """{"status": "ok"}"""
                            mockHttpResponse
                        }
                    }
                }
            }

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "default",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                )

            val startTime = System.currentTimeMillis()
            webhookService.sendWebhook(event)
            val elapsedTime = System.currentTimeMillis() - startTime

            // Should wait at least 5 seconds (5000ms) as specified in Retry-After header
            assertTrue(elapsedTime >= 5000, "Should have waited at least 5 seconds for Retry-After")
            verify(exactly = 2) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }
        }

    @Test
    fun `should handle 429 without Retry-After header using exponential backoff`() =
        runBlocking {
            val config =
                WebhookConfig(
                    url = "https://example.com/webhook",
                    enableDeploymentDetected = true,
                    retryCount = 3,
                )
            webhookService = WebhookService(config)

            val mockHeaders = mockk<HttpHeaders>()
            every { mockHeaders.firstValue("Retry-After") } returns Optional.empty()

            var callCount = 0
            every { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } answers {
                callCount++
                CompletableFuture.supplyAsync {
                    Thread.sleep(45) // 45ms delay to simulate network latency
                    when (callCount) {
                        1 -> {
                            every { mockHttpResponse.statusCode() } returns 429
                            every { mockHttpResponse.body() } returns "Rate limit exceeded"
                            every { mockHttpResponse.headers() } returns mockHeaders
                            mockHttpResponse
                        }
                        else -> {
                            every { mockHttpResponse.statusCode() } returns 200
                            every { mockHttpResponse.body() } returns """{"status": "ok"}"""
                            mockHttpResponse
                        }
                    }
                }
            }

            val event =
                WebhookEvent(
                    eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                    timestamp = Instant.now().toString(),
                    deployment =
                        DeploymentEventData(
                            namespace = "default",
                            name = "test-deployment",
                            image = "nginx:1.21",
                        ),
                )

            val startTime = System.currentTimeMillis()
            webhookService.sendWebhook(event)
            val elapsedTime = System.currentTimeMillis() - startTime

            // Should use exponential backoff (1 second for first retry)
            assertTrue(elapsedTime >= 1000, "Should have waited at least 1 second with exponential backoff")
            assertTrue(elapsedTime < 3000, "Should not have waited more than 3 seconds")
            verify(exactly = 2) { mockHttpClient.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) }
        }
}
