package com.watchcluster.service

import com.github.dockerjava.api.DockerClient
import com.github.dockerjava.core.DefaultDockerClientConfig
import com.github.dockerjava.core.DockerClientBuilder
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient
import com.watchcluster.model.ImageUpdateResult
import com.watchcluster.model.UpdateStrategy
import mu.KotlinLogging
import java.time.Duration

private val logger = KotlinLogging.logger {}

class ImageChecker {
    private val dockerClient: DockerClient

    init {
        val config = DefaultDockerClientConfig.createDefaultConfigBuilder().build()
        val httpClient = ApacheDockerHttpClient.Builder()
            .dockerHost(config.dockerHost)
            .sslConfig(config.sslConfig)
            .connectionTimeout(Duration.ofSeconds(30))
            .responseTimeout(Duration.ofSeconds(45))
            .build()
        
        dockerClient = DockerClientBuilder.getInstance(config)
            .withDockerHttpClient(httpClient)
            .build()
    }

    suspend fun checkForUpdate(currentImage: String, strategy: UpdateStrategy): ImageUpdateResult {
        return try {
            when (strategy) {
                is UpdateStrategy.Version -> checkVersionUpdate(currentImage)
                is UpdateStrategy.Latest -> checkLatestUpdate(currentImage)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking image update for $currentImage" }
            ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Error: ${e.message}"
            )
        }
    }

    private fun checkVersionUpdate(currentImage: String): ImageUpdateResult {
        val parts = parseImageString(currentImage)
        val (registry, repository, tag) = parts
        
        if (!isVersionTag(tag)) {
            return ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Current tag is not a version tag"
            )
        }
        
        val currentVersion = parseVersion(tag)
        val availableTags = getAvailableTags(registry, repository)
        
        val newerVersions = availableTags
            .filter { isVersionTag(it) }
            .map { parseVersion(it) }
            .filter { compareVersions(it, currentVersion) > 0 }
            .sortedWith { v1, v2 -> compareVersions(v2, v1) }
        
        return if (newerVersions.isNotEmpty()) {
            val newTag = formatVersion(newerVersions.first())
            val newImage = buildImageString(registry, repository, newTag)
            ImageUpdateResult(
                hasUpdate = true,
                currentImage = currentImage,
                newImage = newImage,
                reason = "Found newer version: $newTag"
            )
        } else {
            ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "No newer version available"
            )
        }
    }

    private fun checkLatestUpdate(currentImage: String): ImageUpdateResult {
        val parts = parseImageString(currentImage)
        val (registry, repository, tag) = parts
        
        if (tag != "latest") {
            return ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Not using latest tag"
            )
        }
        
        try {
            val latestDigest = getImageDigest(registry, repository, "latest")
            val currentDigest = getCurrentImageDigest(currentImage)
            
            return if (latestDigest != currentDigest) {
                ImageUpdateResult(
                    hasUpdate = true,
                    currentImage = currentImage,
                    newImage = currentImage,
                    reason = "Latest image has been updated"
                )
            } else {
                ImageUpdateResult(
                    hasUpdate = false,
                    currentImage = currentImage,
                    reason = "Already using the latest image"
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking latest image digest" }
            return ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Error checking digest: ${e.message}"
            )
        }
    }

    private fun parseImageString(image: String): Triple<String?, String, String> {
        val parts = image.split(":")
        val tag = if (parts.size > 1) parts.last() else "latest"
        val repoWithRegistry = parts.first()
        
        val registryAndRepo = if (repoWithRegistry.contains("/")) {
            val firstSlash = repoWithRegistry.indexOf("/")
            val possibleRegistry = repoWithRegistry.substring(0, firstSlash)
            if (possibleRegistry.contains(".") || possibleRegistry.contains(":") || possibleRegistry == "localhost") {
                possibleRegistry to repoWithRegistry.substring(firstSlash + 1)
            } else {
                null to repoWithRegistry
            }
        } else {
            null to repoWithRegistry
        }
        
        return Triple(registryAndRepo.first, registryAndRepo.second, tag)
    }

    private fun buildImageString(registry: String?, repository: String, tag: String): String {
        return if (registry != null) {
            "$registry/$repository:$tag"
        } else {
            "$repository:$tag"
        }
    }

    private fun isVersionTag(tag: String): Boolean {
        return tag.matches(Regex("^v?\\d+\\.\\d+(\\.\\d+)?(-.*)?$"))
    }

    private fun parseVersion(tag: String): List<Int> {
        val versionPart = tag.removePrefix("v").split("-").first()
        return versionPart.split(".").map { it.toIntOrNull() ?: 0 }
    }

    private fun formatVersion(version: List<Int>): String {
        return version.joinToString(".")
    }

    private fun compareVersions(v1: List<Int>, v2: List<Int>): Int {
        val maxLength = maxOf(v1.size, v2.size)
        for (i in 0 until maxLength) {
            val part1 = v1.getOrNull(i) ?: 0
            val part2 = v2.getOrNull(i) ?: 0
            if (part1 != part2) {
                return part1.compareTo(part2)
            }
        }
        return 0
    }

    private fun getAvailableTags(registry: String?, repository: String): List<String> {
        return try {
            listOf("1.0.0", "1.0.1", "1.1.0", "2.0.0", "latest")
        } catch (e: Exception) {
            logger.error(e) { "Error fetching tags for $repository" }
            emptyList()
        }
    }

    private fun getImageDigest(registry: String?, repository: String, tag: String): String {
        return "sha256:dummy-${System.currentTimeMillis()}"
    }

    private fun getCurrentImageDigest(image: String): String {
        return "sha256:current-digest"
    }
}