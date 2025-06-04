package com.watchcluster.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.watchcluster.model.DockerAuth
import com.watchcluster.model.ImageUpdateResult
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.util.ImageParser
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
        imagePullSecrets: List<String>?,
        deploymentName: String? = null
    ): ImageUpdateResult {
        return try {
            val dockerAuth = imagePullSecrets?.let { extractDockerAuth(namespace, it, currentImage) }
            
            when (strategy) {
                is UpdateStrategy.Version -> checkVersionUpdate(currentImage, strategy, dockerAuth)
                is UpdateStrategy.Latest -> checkLatestUpdate(currentImage, dockerAuth, namespace, deploymentName)
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
        val components = ImageParser.parseImageString(image)
        val registryUrl = components.registry ?: "index.docker.io"
        
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
        val components = ImageParser.parseImageString(currentImage)
        val (registry, repository, tag) = components
        
        if (!ImageParser.isVersionTag(tag)) {
            return ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Current tag is not a version tag"
            )
        }
        
        val currentVersion = ImageParser.parseVersion(tag)
        val availableTags = getAvailableTags(registry, repository, dockerAuth)
        
        val hasVPrefix = tag.startsWith("v")
        val currentMajorVersion = currentVersion.getOrNull(0) ?: 0
        
        val newerVersions = availableTags
            .filter { ImageParser.isVersionTag(it) }
            .map { tagString -> tagString to ImageParser.parseVersion(tagString) }
            .filter { (_, version) -> 
                if (strategy.lockMajorVersion) {
                    val candidateMajor = version.getOrNull(0) ?: 0
                    candidateMajor == currentMajorVersion && ImageParser.compareVersions(version, currentVersion) > 0
                } else {
                    ImageParser.compareVersions(version, currentVersion) > 0
                }
            }
            .sortedWith { a, b -> ImageParser.compareVersions(b.second, a.second) }
        
        return if (newerVersions.isNotEmpty()) {
            val (originalTag, _) = newerVersions.first()
            val newTag = if (hasVPrefix && !originalTag.startsWith("v")) {
                "v$originalTag"
            } else if (!hasVPrefix && originalTag.startsWith("v")) {
                originalTag.removePrefix("v")
            } else {
                originalTag
            }
            val newImage = ImageParser.buildImageString(registry, repository, newTag)
            
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

    private suspend fun checkLatestUpdate(currentImage: String, dockerAuth: DockerAuth?, namespace: String?, deploymentName: String?): ImageUpdateResult {
        val components = ImageParser.parseImageString(currentImage)
        val (registry, repository, tag) = components
        
        // Check if this is a version tag - those should use version strategy
        if (ImageParser.isVersionTag(tag)) {
            return ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Use version strategy for version tags"
            )
        }
        
        try {
            val registryDigest = getImageDigest(registry, repository, tag, dockerAuth)
            val currentDigest = getCurrentImageDigest(currentImage, dockerAuth, namespace, deploymentName)

            
            return if (registryDigest != null && currentDigest != null && registryDigest != currentDigest) {
                ImageUpdateResult(
                    hasUpdate = true,
                    currentImage = currentImage,
                    newImage = currentImage,
                    reason = if (tag == "latest") "Latest image has been updated" else "Tag '$tag' has been updated",
                    currentDigest = currentDigest,
                    newDigest = registryDigest
                )
            } else {
                ImageUpdateResult(
                    hasUpdate = false,
                    currentImage = currentImage,
                    reason = "Already using the latest image",
                    currentDigest = currentDigest,
                    newDigest = registryDigest
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Error checking image digest for tag '$tag'" }
            return ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Error checking digest: ${e.message}"
            )
        }
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

    private suspend fun getCurrentImageDigest(image: String, @Suppress("UNUSED_PARAMETER") dockerAuth: DockerAuth? = null, namespace: String? = null, deploymentName: String? = null): String? {
        return try {
            // If we have deployment info, get the actual running digest from Kubernetes
            if (namespace != null && deploymentName != null) {
                // Get the running pod's image ID - this is the source of truth
                val podList = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withLabel("app", deploymentName)
                    .list()
                
                if (podList.items.isNotEmpty()) {
                    val pod = podList.items.first()
                    val containerStatus = pod.status?.containerStatuses?.firstOrNull()
                    val imageID = containerStatus?.imageID
                    
                    if (imageID != null && imageID.contains("@")) {
                        // Extract digest from imageID (format: docker://image@sha256:...)
                        val digest = imageID.substringAfter("@")
                        logger.debug { "Got digest from pod: $digest" }
                        return digest
                    }
                }
            }
            
            // Fallback: if we can't get from K8s, return null to indicate unknown
            logger.debug { "Cannot determine current digest from Kubernetes for $image" }
            null
        } catch (e: Exception) {
            logger.error(e) { "Error getting current image digest for $image" }
            null
        }
    }
}
