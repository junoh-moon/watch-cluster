package com.watchcluster.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.watchcluster.model.WebhookConfig
import com.watchcluster.model.WebhookEvent
import com.watchcluster.model.WebhookEventType
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

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
        repeat(webhookConfig.retryCount) { attempt ->
            try {
                sendHttpRequest(webhookConfig.url, event)
                logger.info { "Webhook sent successfully for ${event.eventType}: ${event.deployment.namespace}/${event.deployment.name}" }
                return
            } catch (e: Exception) {
                lastException = e
                logger.warn(e) { "Webhook attempt ${attempt + 1} failed for ${event.eventType}: ${event.deployment.namespace}/${event.deployment.name}" }
                
                if (attempt < webhookConfig.retryCount - 1) {
                    delay(1000L * (attempt + 1))
                }
            }
        }
        
        logger.error(lastException) { 
            "All webhook attempts failed for ${event.eventType}: ${event.deployment.namespace}/${event.deployment.name}" 
        }
    }
    
    private fun sendHttpRequest(url: String, event: WebhookEvent) {
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
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Webhook failed with status ${response.statusCode()}: ${response.body()}")
        }
        
        logger.debug { "Webhook response: ${response.statusCode()}" }
    }
}