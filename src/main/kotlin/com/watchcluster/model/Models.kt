package com.watchcluster.model

import java.time.Instant

data class WatchedDeployment(
    val namespace: String,
    val name: String,
    val cronExpression: String,
    val updateStrategy: UpdateStrategy,
    val currentImage: String,
    val imagePullSecrets: List<String>? = null,
    val lastChecked: Instant? = null
)

sealed class UpdateStrategy {
    data class Version(
        val pattern: String = "semver",
        val lockMajorVersion: Boolean = false
    ) : UpdateStrategy()
    object Latest : UpdateStrategy()
}

data class ImageUpdateResult(
    val hasUpdate: Boolean,
    val currentImage: String,
    val newImage: String? = null,
    val reason: String? = null,
    val currentDigest: String? = null,
    val newDigest: String? = null
)

data class DockerAuth(
    val username: String,
    val password: String
)

data class WebhookConfig(
    val url: String?,
    val enableDeploymentDetected: Boolean = false,
    val enableImageRolloutStarted: Boolean = false,
    val enableImageRolloutCompleted: Boolean = false,
    val enableImageRolloutFailed: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val timeout: Long = 10000L,
    val retryCount: Int = 3
) {
    companion object {
        fun fromEnvironment(): WebhookConfig {
            val headers = System.getenv("WEBHOOK_HEADERS")?.let { headersStr ->
                headersStr.split(",")
                    .filter { it.isNotBlank() }
                    .mapNotNull { header ->
                        val parts = header.split("=", limit = 2)
                        if (parts.size == 2) {
                            parts[0].trim() to parts[1].trim()
                        } else null
                    }
                    .toMap()
            } ?: emptyMap()
            
            return WebhookConfig(
                url = System.getenv("WEBHOOK_URL"),
                enableDeploymentDetected = System.getenv("WEBHOOK_ENABLE_DEPLOYMENT_DETECTED")?.toBoolean() ?: false,
                enableImageRolloutStarted = System.getenv("WEBHOOK_ENABLE_IMAGE_ROLLOUT_STARTED")?.toBoolean() ?: false,
                enableImageRolloutCompleted = System.getenv("WEBHOOK_ENABLE_IMAGE_ROLLOUT_COMPLETED")?.toBoolean() ?: false,
                enableImageRolloutFailed = System.getenv("WEBHOOK_ENABLE_IMAGE_ROLLOUT_FAILED")?.toBoolean() ?: false,
                headers = headers,
                timeout = System.getenv("WEBHOOK_TIMEOUT")?.toLongOrNull()?.coerceAtLeast(0L) ?: 10000L,
                retryCount = System.getenv("WEBHOOK_RETRY_COUNT")?.toIntOrNull()?.coerceAtLeast(0) ?: 3
            )
        }
    }
}

data class WebhookEvent(
    val eventType: WebhookEventType,
    val timestamp: String,
    val deployment: DeploymentInfo,
    val details: Map<String, Any> = emptyMap()
)

data class DeploymentInfo(
    val namespace: String,
    val name: String,
    val image: String
)

enum class WebhookEventType {
    DEPLOYMENT_DETECTED,
    IMAGE_ROLLOUT_STARTED,
    IMAGE_ROLLOUT_COMPLETED,
    IMAGE_ROLLOUT_FAILED
}

object Annotations {
    const val ENABLED = "watch-cluster.io/enabled"
    const val CRON = "watch-cluster.io/cron"
    const val STRATEGY = "watch-cluster.io/strategy"
}