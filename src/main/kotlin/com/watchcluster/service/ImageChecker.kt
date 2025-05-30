package com.watchcluster.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.watchcluster.model.DockerAuth
import com.watchcluster.model.ImageUpdateResult
import com.watchcluster.model.UpdateStrategy
import io.fabric8.kubernetes.client.KubernetesClient
import mu.KotlinLogging
import java.util.Base64

private val logger = KotlinLogging.logger {}

class ImageChecker(
    private val kubernetesClient: KubernetesClient
) {
    private val registryClient = DockerRegistryClient()
    private val objectMapper = ObjectMapper()

    suspend fun checkForUpdate(
        currentImage: String, 
        strategy: UpdateStrategy,
        namespace: String,
        imagePullSecrets: List<String>?
    ): ImageUpdateResult {
        return try {
            val dockerAuth = imagePullSecrets?.let { extractDockerAuth(namespace, it, currentImage) }
            
            when (strategy) {
                is UpdateStrategy.Version -> checkVersionUpdate(currentImage, strategy, dockerAuth)
                is UpdateStrategy.Latest -> checkLatestUpdate(currentImage, dockerAuth)
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

    private fun extractDockerAuth(namespace: String, secretNames: List<String>, image: String): DockerAuth? {
        val (registry, _, _) = parseImageString(image)
        val registryUrl = registry ?: "index.docker.io"
        
        for (secretName in secretNames) {
            try {
                val secret = kubernetesClient.secrets()
                    .inNamespace(namespace)
                    .withName(secretName)
                    .get()
                
                if (secret?.type == "kubernetes.io/dockerconfigjson") {
                    val dockerConfigJson = secret.data?.get(".dockerconfigjson") ?: continue
                    val decodedConfig = Base64.getDecoder().decode(dockerConfigJson).toString(Charsets.UTF_8)
                    val configRoot = objectMapper.readTree(decodedConfig)
                    val authsNode = configRoot.get("auths") ?: continue
                    
                    // Try exact match first
                    var authNode = authsNode.get(registryUrl)
                    
                    // Try common variations
                    if (authNode == null && registryUrl == "index.docker.io") {
                        authNode = authsNode.get("https://index.docker.io/v1/") 
                            ?: authsNode.get("docker.io")
                            ?: authsNode.get("https://docker.io")
                    }
                    
                    if (authNode != null) {
                        val authString = authNode.get("auth")?.asText() ?: continue
                        val decodedAuth = Base64.getDecoder().decode(authString).toString(Charsets.UTF_8)
                        val (username, password) = decodedAuth.split(":", limit = 2)
                        return DockerAuth(username, password)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Error extracting auth from secret $secretName" }
            }
        }
        return null
    }
    
    private suspend fun checkVersionUpdate(currentImage: String, strategy: UpdateStrategy.Version, dockerAuth: DockerAuth?): ImageUpdateResult {
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
        val availableTags = getAvailableTags(registry, repository, dockerAuth)
        
        val hasVPrefix = tag.startsWith("v")
        val currentMajorVersion = currentVersion.getOrNull(0) ?: 0
        
        val newerVersions = availableTags
            .filter { isVersionTag(it) }
            .map { tagString -> tagString to parseVersion(tagString) }
            .filter { (_, version) -> 
                if (strategy.lockMajorVersion) {
                    val candidateMajor = version.getOrNull(0) ?: 0
                    candidateMajor == currentMajorVersion && compareVersions(version, currentVersion) > 0
                } else {
                    compareVersions(version, currentVersion) > 0
                }
            }
            .sortedWith { a, b -> compareVersions(b.second, a.second) }
        
        return if (newerVersions.isNotEmpty()) {
            val (originalTag, _) = newerVersions.first()
            val newTag = if (hasVPrefix && !originalTag.startsWith("v")) {
                "v$originalTag"
            } else if (!hasVPrefix && originalTag.startsWith("v")) {
                originalTag.removePrefix("v")
            } else {
                originalTag
            }
            val newImage = buildImageString(registry, repository, newTag)
            
            // Get digests for version updates
            val currentDigest = try {
                getCurrentImageDigest(currentImage, dockerAuth)
            } catch (e: Exception) {
                logger.debug { "Could not get current digest: ${e.message}" }
                null
            }
            val newDigest = try {
                getImageDigest(registry, repository, newTag, dockerAuth)
            } catch (e: Exception) {
                logger.debug { "Could not get new digest: ${e.message}" }
                null
            }
            
            ImageUpdateResult(
                hasUpdate = true,
                currentImage = currentImage,
                newImage = newImage,
                reason = "Found newer version: $newTag",
                currentDigest = currentDigest,
                newDigest = newDigest
            )
        } else {
            val noUpdateReason = if (strategy.lockMajorVersion) {
                "No newer version available within major version $currentMajorVersion"
            } else {
                "No newer version available"
            }
            ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = noUpdateReason
            )
        }
    }

    private suspend fun checkLatestUpdate(currentImage: String, dockerAuth: DockerAuth?): ImageUpdateResult {
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
            val latestDigest = getImageDigest(registry, repository, "latest", dockerAuth)
            val currentDigest = getCurrentImageDigest(currentImage, dockerAuth)
            
            return if (latestDigest != null && currentDigest != null && latestDigest != currentDigest) {
                ImageUpdateResult(
                    hasUpdate = true,
                    currentImage = currentImage,
                    newImage = currentImage,
                    reason = "Latest image has been updated",
                    currentDigest = currentDigest,
                    newDigest = latestDigest
                )
            } else {
                ImageUpdateResult(
                    hasUpdate = false,
                    currentImage = currentImage,
                    reason = "Already using the latest image",
                    currentDigest = currentDigest,
                    newDigest = latestDigest
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

    private suspend fun getAvailableTags(registry: String?, repository: String, dockerAuth: DockerAuth?): List<String> {
        return try {
            registryClient.getTags(registry, repository, dockerAuth)
        } catch (e: Exception) {
            logger.error(e) { "Error fetching tags for $repository" }
            emptyList()
        }
    }

    private suspend fun getImageDigest(registry: String?, repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        return registryClient.getImageDigest(registry, repository, tag, dockerAuth)
    }

    private suspend fun getCurrentImageDigest(image: String, dockerAuth: DockerAuth? = null): String? {
        return try {
            // Parse image to get registry, repository, and tag
            val parts = parseImageString(image)
            val registry = parts.first
            val repository = parts.second
            val tag = parts.third
            
            // Get digest from registry instead of local docker daemon
            logger.debug { "Getting digest for $image from registry" }
            getImageDigest(registry, repository, tag, dockerAuth)
        } catch (e: Exception) {
            logger.error(e) { "Error getting current image digest for $image" }
            null
        }
    }
}