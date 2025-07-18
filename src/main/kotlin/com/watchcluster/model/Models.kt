package com.watchcluster.model

import java.time.Instant

data class WatchedDeployment(
    val namespace: String,
    val name: String,
    val cronExpression: String,
    val updateStrategy: UpdateStrategy,
    val currentImage: String,
    val imagePullSecrets: List<String>? = null,
    val lastChecked: Instant? = null,
)

sealed class UpdateStrategy {
    abstract val displayName: String

    data class Version(
        val pattern: String = "semver",
        val lockMajorVersion: Boolean = false,
    ) : UpdateStrategy() {
        override val displayName: String = if (lockMajorVersion) "version-lock-major" else "version"
    }

    object Latest : UpdateStrategy() {
        override val displayName: String = "latest"
    }

    companion object {
        fun fromString(value: String): UpdateStrategy =
            when (value.lowercase()) {
                "latest" -> Latest
                "version-lock-major" -> Version(lockMajorVersion = true)
                "version", "semver" -> Version()
                else -> Version() // default
            }
    }
}

data class ImageUpdateResult(
    val hasUpdate: Boolean,  // TODO: replaced by `newImage != null`
    val currentImage: String,
    val newImage: String? = null,
    val reason: String? = null,
    val currentDigest: String? = null,
    val newDigest: String? = null,
)

data class DockerAuth(
    val username: String,
    val password: String,
)

data class WebhookConfig(
    val url: String?,
    val enableDeploymentDetected: Boolean = false,
    val enableImageRolloutStarted: Boolean = false,
    val enableImageRolloutCompleted: Boolean = false,
    val enableImageRolloutFailed: Boolean = false,
    val headers: Map<String, String> = emptyMap(),
    val timeout: Long = 10000L,
    val retryCount: Int = 3,
) {
    companion object {
        fun fromEnvironment(): WebhookConfig {
            val headers =
                System
                    .getenv("WEBHOOK_HEADERS")
                    ?.split(",")
                    ?.filter { it.isNotBlank() }
                    ?.mapNotNull { header ->
                        header
                            .split("=", limit = 2)
                            .takeIf { it.size == 2 }
                            ?.let { (key, value) -> key.trim() to value.trim() }
                    }?.toMap()
                    ?: emptyMap()

            return WebhookConfig(
                url = System.getenv("WEBHOOK_URL"),
                enableDeploymentDetected = System.getenv("WEBHOOK_ENABLE_DEPLOYMENT_DETECTED")?.toBoolean() ?: false,
                enableImageRolloutStarted = System.getenv("WEBHOOK_ENABLE_IMAGE_ROLLOUT_STARTED")?.toBoolean() ?: false,
                enableImageRolloutCompleted = System.getenv("WEBHOOK_ENABLE_IMAGE_ROLLOUT_COMPLETED")?.toBoolean()
                    ?: false,
                enableImageRolloutFailed = System.getenv("WEBHOOK_ENABLE_IMAGE_ROLLOUT_FAILED")?.toBoolean() ?: false,
                headers = headers,
                timeout = System.getenv("WEBHOOK_TIMEOUT")?.toLongOrNull()?.coerceAtLeast(0L) ?: 10000L,
                retryCount = System.getenv("WEBHOOK_RETRY_COUNT")?.toIntOrNull()?.coerceAtLeast(0) ?: 3,
            )
        }
    }
}

data class WebhookEvent(
    val eventType: WebhookEventType,
    val timestamp: String,
    val deployment: DeploymentEventData,
    val details: Map<String, Any> = emptyMap(),
)

data class DeploymentEventData(
    val namespace: String,
    val name: String,
    val image: String,
)

enum class WebhookEventType {
    DEPLOYMENT_DETECTED,
    IMAGE_ROLLOUT_STARTED,
    IMAGE_ROLLOUT_COMPLETED,
    IMAGE_ROLLOUT_FAILED,
}

// UpdateStrategyType enum removed - use UpdateStrategy sealed class directly

object WatchClusterAnnotations {
    const val ENABLED = "watch-cluster.io/enabled"
    const val CRON = "watch-cluster.io/cron"
    const val STRATEGY = "watch-cluster.io/strategy"
    const val VERSION_PATTERN = "watch-cluster.io/version-pattern"
    const val LOCK_MAJOR_VERSION = "watch-cluster.io/lock-major-version"
}
