package com.watchcluster.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.watchcluster.model.WebhookConfig
import com.watchcluster.model.WebhookEvent
import com.watchcluster.model.WebhookEventType
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.net.http.HttpTimeoutException

private val logger = KotlinLogging.logger {}

class WebhookService(
    private val webhookConfig: WebhookConfig
) {
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(webhookConfig.timeout))
        .build()
    
    private val objectMapper = ObjectMapper().registerKotlinModule()

    suspend fun sendWebhook(event: WebhookEvent) {
        if (webhookConfig.url.isNullOrBlank()) {
            logger.debug { "No webhook URL configured" }
            return
        }
        
        val isEventEnabled = when (event.eventType) {
            WebhookEventType.DEPLOYMENT_DETECTED -> webhookConfig.enableDeploymentDetected
            WebhookEventType.IMAGE_ROLLOUT_STARTED -> webhookConfig.enableImageRolloutStarted
            WebhookEventType.IMAGE_ROLLOUT_COMPLETED -> webhookConfig.enableImageRolloutCompleted
            WebhookEventType.IMAGE_ROLLOUT_FAILED -> webhookConfig.enableImageRolloutFailed
        }
        
        if (!isEventEnabled) {
            logger.debug { "Webhook event ${event.eventType} is disabled" }
            return
        }
        
        var lastException: Exception? = null
        var lastResponse: HttpResponse<String>? = null
        repeat(webhookConfig.retryCount) { attempt ->
            try {
                sendHttpRequestAsync(webhookConfig.url, event)
                logger.info { "Webhook sent successfully for ${event.eventType}: ${event.deployment.namespace}/${event.deployment.name}" }
                return
            } catch (e: Exception) {
                lastException = e
                if (e is HttpRequestException) {
                    lastResponse = e.response
                }
                logger.warn(e) { "Webhook attempt ${attempt + 1} failed for ${event.eventType}: ${event.deployment.namespace}/${event.deployment.name}" }
                
                if (attempt < webhookConfig.retryCount - 1) {
                    val delayMs = calculateRetryDelay(attempt, lastResponse)
                    logger.debug { "Waiting ${delayMs}ms before retry" }
                    delay(delayMs)
                }
            }
        }
        
        logger.error(lastException) { 
            "All webhook attempts failed for ${event.eventType}: ${event.deployment.namespace}/${event.deployment.name}" 
        }
    }
    
    private fun calculateRetryDelay(attempt: Int, response: HttpResponse<String>?): Long {
        // Check for 429 status code and Retry-After header
        return response?.takeIf { it.statusCode() == 429 }
            ?.headers()
            ?.firstValue("Retry-After")
            ?.orElse(null)
            ?.let { retryAfter ->
                try {
                    // Retry-After can be either seconds or HTTP-date
                    retryAfter.toLongOrNull()?.let { seconds ->
                        logger.debug { "429 Rate Limit: Retry-After header indicates ${seconds} seconds" }
                        seconds * 1000 // Convert to milliseconds
                    } ?: run {
                        // If it's not a number, it might be an HTTP-date
                        // For simplicity, we'll use exponential backoff in this case
                        logger.debug { "429 Rate Limit: Retry-After header is not numeric, using exponential backoff" }
                        1000L * (attempt + 1)
                    }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Retry-After header: $retryAfter" }
                    1000L * (attempt + 1)
                }
            } ?: run {
                // Default exponential backoff
                1000L * (attempt + 1)
            }
    }
    
    private suspend fun sendHttpRequestAsync(url: String, event: WebhookEvent): HttpResponse<String> {
        val requestBody = objectMapper.writeValueAsString(event)
        
        val requestBuilder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(webhookConfig.timeout))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        
        webhookConfig.headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }
        
        val request = requestBuilder.build()
        val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        
        if (response.statusCode() !in 200..299) {
            throw HttpRequestException("Webhook failed with status ${response.statusCode()}: ${response.body()}", response)
        }
        
        logger.debug { "Webhook response: ${response.statusCode()}" }
        return response
    }
    
    class HttpRequestException(message: String, val response: HttpResponse<String>) : RuntimeException(message)
}