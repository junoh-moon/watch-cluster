package com.watchcluster.model

import java.time.Instant

data class WatchedDeployment(
    val namespace: String,
    val name: String,
    val cronExpression: String,
    val updateStrategy: UpdateStrategy,
    val currentImage: String,
    val lastChecked: Instant? = null
)

sealed class UpdateStrategy {
    data class Version(val pattern: String = "semver") : UpdateStrategy()
    object Latest : UpdateStrategy()
}

data class ImageUpdateResult(
    val hasUpdate: Boolean,
    val currentImage: String,
    val newImage: String? = null,
    val reason: String? = null
)

object Annotations {
    const val ENABLED = "watch-cluster.io/enabled"
    const val CRON = "watch-cluster.io/cron"
    const val STRATEGY = "watch-cluster.io/strategy"
}