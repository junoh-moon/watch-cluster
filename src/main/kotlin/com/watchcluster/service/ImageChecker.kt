package com.watchcluster.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.watchcluster.model.DockerAuth
import com.watchcluster.model.ImageUpdateResult
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.util.ImageParser
import io.fabric8.kubernetes.client.KubernetesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.stereotype.Service
import java.util.Base64

private val logger = KotlinLogging.logger {}

@Service
class ImageChecker(
    private val kubernetesClient: KubernetesClient,
    private val registryClient: DockerRegistryClient
) {
    private val objectMapper = ObjectMapper()

    suspend fun checkForUpdate(
        currentImage: String, 
        strategy: UpdateStrategy,
        namespace: String,
        imagePullSecrets: List<String>?,
        deploymentName: String? = null
    ): ImageUpdateResult {
        logger.debug { "[ImageChecker] Starting update check for image: $currentImage" }
        logger.debug { "[ImageChecker] Strategy: $strategy, namespace: $namespace, deploymentName: $deploymentName" }
        logger.debug { "[ImageChecker] ImagePullSecrets: $imagePullSecrets" }
        
        return runCatching {
            val dockerAuth = imagePullSecrets?.let { 
                logger.debug { "[ImageChecker] Extracting docker auth from secrets" }
                extractDockerAuth(namespace, it, currentImage)
            }
            logger.debug { "[ImageChecker] Docker auth available: ${dockerAuth != null}" }
            
            val result = when (strategy) {
                is UpdateStrategy.Version -> {
                    logger.debug { "[ImageChecker] Using version update strategy" }
                    checkVersionUpdate(currentImage, strategy, dockerAuth)
                }
                is UpdateStrategy.Latest -> {
                    logger.debug { "[ImageChecker] Using latest update strategy" }
                    checkLatestUpdate(currentImage, dockerAuth, namespace, deploymentName)
                }
            }
            
            logger.debug { "[ImageChecker] Update check result: hasUpdate=${result.hasUpdate}, reason=${result.reason}" }
            result
        }.getOrElse { e ->
            logger.error(e) { "[ImageChecker] Error checking image update for $currentImage" }
            ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Error: ${e.message}"
            )
        }
    }

    private suspend fun extractDockerAuth(namespace: String, secretNames: List<String>, image: String): DockerAuth? {
        val components = ImageParser.parseImageString(image)
        val registryUrl = components.registry ?: "index.docker.io"
        
        for (secretName in secretNames) {
            runCatching {
                val secret = withContext(Dispatchers.IO) {
                    kubernetesClient.secrets()
                        .inNamespace(namespace)
                        .withName(secretName)
                        .get()
                }
                
                if (secret?.type == "kubernetes.io/dockerconfigjson") {
                    val dockerConfigJson = secret.data?.get(".dockerconfigjson") ?: return@runCatching
                    val decodedConfig = Base64.getDecoder().decode(dockerConfigJson).toString(Charsets.UTF_8)
                    val configRoot = objectMapper.readTree(decodedConfig)
                    val authsNode = configRoot.get("auths") ?: return@runCatching
                    
                    // Try exact match first
                    var authNode = authsNode.get(registryUrl)
                    
                    // Try common variations
                    if (authNode == null && registryUrl == "index.docker.io") {
                        authNode = authsNode.get("https://index.docker.io/v1/") 
                            ?: authsNode.get("docker.io")
                            ?: authsNode.get("https://docker.io")
                    }
                    
                    if (authNode != null) {
                        val authString = authNode.get("auth")?.asText() ?: return@runCatching
                        val decodedAuth = Base64.getDecoder().decode(authString).toString(Charsets.UTF_8)
                        val (username, password) = decodedAuth.split(":", limit = 2)
                        return DockerAuth(username, password)
                    }
                }
            }.onFailure { e ->
                logger.error(e) { "Error extracting auth from secret $secretName" }
            }
        }
        return null
    }
    
    private suspend fun checkVersionUpdate(currentImage: String, strategy: UpdateStrategy.Version, dockerAuth: DockerAuth?): ImageUpdateResult {
        val imageComponents = parseImageForVersionUpdate(currentImage)
            ?: return createImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Current tag is not a version tag"
            )
            
        val versionComparison = findBestVersionMatch(imageComponents, strategy, dockerAuth)
            ?: return createNoUpdateResult(currentImage, strategy, imageComponents.currentMajorVersion)
            
        return createVersionUpdateResult(currentImage, imageComponents, versionComparison, dockerAuth)
    }
    
    private data class ImageVersionComponents(
        val registry: String?,
        val repository: String,
        val tag: String,
        val currentVersion: List<Int>,
        val hasVPrefix: Boolean,
        val currentMajorVersion: Int
    )
    
    private data class VersionComparison(
        val originalTag: String,
        val parsedVersion: List<Int>
    )
    
    private suspend fun parseImageForVersionUpdate(currentImage: String): ImageVersionComponents? {
        val components = ImageParser.parseImageString(currentImage)
        val (registry, repository, tag) = components
        
        if (!ImageParser.isVersionTag(tag)) {
            return null
        }
        
        val currentVersion = ImageParser.parseVersion(tag)
        val hasVPrefix = tag.startsWith("v")
        val currentMajorVersion = currentVersion.getOrNull(0) ?: 0
        
        return ImageVersionComponents(
            registry = registry,
            repository = repository,
            tag = tag,
            currentVersion = currentVersion,
            hasVPrefix = hasVPrefix,
            currentMajorVersion = currentMajorVersion
        )
    }
    
    private suspend fun findBestVersionMatch(
        imageComponents: ImageVersionComponents,
        strategy: UpdateStrategy.Version,
        dockerAuth: DockerAuth?
    ): VersionComparison? {
        logger.debug { "[ImageChecker] Finding best version match for ${imageComponents.repository}:${imageComponents.tag}" }
        logger.debug { "[ImageChecker] Current version: ${imageComponents.currentVersion}, major version: ${imageComponents.currentMajorVersion}" }
        logger.debug { "[ImageChecker] Lock major version: ${strategy.lockMajorVersion}" }
        
        val availableTags = getAvailableTags(imageComponents.registry, imageComponents.repository, dockerAuth)
        logger.debug { "[ImageChecker] Found ${availableTags.size} available tags" }
        
        val versionTags = availableTags.filter { ImageParser.isVersionTag(it) }
        logger.debug { "[ImageChecker] Filtered to ${versionTags.size} version tags: ${versionTags.take(10).joinToString()} ${if (versionTags.size > 10) "..." else ""}" }
        
        val parsedVersions = versionTags.map { tagString -> 
            val parsed = ImageParser.parseVersion(tagString)
            logger.debug { "[ImageChecker] Parsed tag '$tagString' -> version: $parsed" }
            tagString to parsed
        }
        
        val candidateVersions = parsedVersions.filter { (tagString, version) -> 
            val isCandidate = if (strategy.lockMajorVersion) {
                val candidateMajor = version.getOrNull(0) ?: 0
                val majorMatches = candidateMajor == imageComponents.currentMajorVersion
                val isNewer = ImageParser.compareVersions(version, imageComponents.currentVersion) > 0
                logger.debug { "[ImageChecker] Candidate '$tagString': major=$candidateMajor (matches: $majorMatches), newer: $isNewer" }
                majorMatches && isNewer
            } else {
                val isNewer = ImageParser.compareVersions(version, imageComponents.currentVersion) > 0
                logger.debug { "[ImageChecker] Candidate '$tagString': newer than current: $isNewer" }
                isNewer
            }
            isCandidate
        }
        
        logger.debug { "[ImageChecker] Found ${candidateVersions.size} candidate versions" }
        
        val newerVersions = candidateVersions.sortedWith { a, b -> ImageParser.compareVersions(b.second, a.second) }
        logger.debug { "[ImageChecker] Sorted candidates: ${newerVersions.take(5).map { it.first }.joinToString()}" }
            
        return if (newerVersions.isNotEmpty()) {
            val (originalTag, parsedVersion) = newerVersions.first()
            logger.debug { "[ImageChecker] Selected best version: $originalTag ($parsedVersion)" }
            VersionComparison(originalTag, parsedVersion)
        } else {
            logger.debug { "[ImageChecker] No newer versions found" }
            null
        }
    }
    
    private suspend fun createVersionUpdateResult(
        currentImage: String,
        imageComponents: ImageVersionComponents,
        versionComparison: VersionComparison,
        dockerAuth: DockerAuth?
    ): ImageUpdateResult {
        val newTag = normalizeVersionTag(versionComparison.originalTag, imageComponents.hasVPrefix)
        val newImage = ImageParser.buildImageString(imageComponents.registry, imageComponents.repository, newTag)
        
        val currentDigest = safeGetCurrentDigest(currentImage, dockerAuth)
        val newDigest = safeGetImageDigest(imageComponents.registry, imageComponents.repository, newTag, dockerAuth)
        
        return ImageUpdateResult(
            hasUpdate = true,
            currentImage = currentImage,
            newImage = newImage,
            reason = "Found newer version: $newTag",
            currentDigest = currentDigest,
            newDigest = newDigest
        )
    }
    
    private fun normalizeVersionTag(originalTag: String, hasVPrefix: Boolean): String {
        return when {
            hasVPrefix && !originalTag.startsWith("v") -> "v$originalTag"
            !hasVPrefix && originalTag.startsWith("v") -> originalTag.removePrefix("v")
            else -> originalTag
        }
    }
    
    private fun createNoUpdateResult(currentImage: String, strategy: UpdateStrategy.Version, currentMajorVersion: Int): ImageUpdateResult {
        val noUpdateReason = if (strategy.lockMajorVersion) {
            "No newer version available within major version $currentMajorVersion"
        } else {
            "No newer version available"
        }
        return createImageUpdateResult(
            hasUpdate = false,
            currentImage = currentImage,
            reason = noUpdateReason
        )
    }
    
    private fun createImageUpdateResult(
        hasUpdate: Boolean,
        currentImage: String,
        newImage: String? = null,
        reason: String,
        currentDigest: String? = null,
        newDigest: String? = null
    ): ImageUpdateResult {
        return ImageUpdateResult(
            hasUpdate = hasUpdate,
            currentImage = currentImage,
            newImage = newImage,
            reason = reason,
            currentDigest = currentDigest,
            newDigest = newDigest
        )
    }
    
    private suspend fun safeGetCurrentDigest(currentImage: String, dockerAuth: DockerAuth?): String? {
        return runCatching {
            getCurrentImageDigest(currentImage, dockerAuth)
        }.getOrElse { e ->
            logger.debug { "Could not get current digest: ${e.message}" }
            null
        }
    }
    
    private suspend fun safeGetImageDigest(registry: String?, repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        return runCatching {
            getImageDigest(registry, repository, tag, dockerAuth)
        }.getOrElse { e ->
            logger.debug { "Could not get new digest: ${e.message}" }
            null
        }
    }

    private suspend fun checkLatestUpdate(currentImage: String, dockerAuth: DockerAuth?, namespace: String?, deploymentName: String?): ImageUpdateResult {
        val components = ImageParser.parseImageString(currentImage)
        val (registry, repository, tag) = components
        
        logger.debug { "[ImageChecker] Checking latest update for $repository:$tag" }
        logger.debug { "[ImageChecker] Registry: $registry, Repository: $repository, Tag: $tag" }
        
        // Check if this is a version tag - those should use version strategy
        if (ImageParser.isVersionTag(tag)) {
            logger.debug { "[ImageChecker] Tag '$tag' is a version tag, should use version strategy" }
            return ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Use version strategy for version tags"
            )
        }
        
        return runCatching {
            logger.debug { "[ImageChecker] Fetching registry digest for $repository:$tag" }
            val registryDigest = getImageDigest(registry, repository, tag, dockerAuth)
            logger.debug { "[ImageChecker] Registry digest: $registryDigest" }
            
            logger.debug { "[ImageChecker] Fetching current digest for image" }
            val currentDigest = getCurrentImageDigest(currentImage, dockerAuth, namespace, deploymentName)
            logger.debug { "[ImageChecker] Current digest: $currentDigest" }

            logger.debug { "[ImageChecker] Comparing digests - Registry: $registryDigest, Current: $currentDigest" }
            val digestsMatch = registryDigest == currentDigest
            val bothDigestsAvailable = registryDigest != null && currentDigest != null
            logger.debug { "[ImageChecker] Digests match: $digestsMatch, Both available: $bothDigestsAvailable" }
            
            if (registryDigest != null && currentDigest != null && registryDigest != currentDigest) {
                logger.debug { "[ImageChecker] Image has been updated - digests differ" }
                ImageUpdateResult(
                    hasUpdate = true,
                    currentImage = currentImage,
                    newImage = currentImage,
                    reason = if (tag == "latest") "Latest image has been updated" else "Tag '$tag' has been updated",
                    currentDigest = currentDigest,
                    newDigest = registryDigest
                )
            } else {
                val reason = when {
                    registryDigest == null -> "Could not fetch registry digest"
                    currentDigest == null -> "Could not fetch current digest"
                    registryDigest == currentDigest -> "Already using the latest image"
                    else -> "Unknown comparison result"
                }
                logger.debug { "[ImageChecker] No update needed: $reason" }
                ImageUpdateResult(
                    hasUpdate = false,
                    currentImage = currentImage,
                    reason = reason,
                    currentDigest = currentDigest,
                    newDigest = registryDigest
                )
            }
        }.getOrElse { e ->
            logger.error(e) { "[ImageChecker] Error checking image digest for tag '$tag'" }
            ImageUpdateResult(
                hasUpdate = false,
                currentImage = currentImage,
                reason = "Error checking digest: ${e.message}"
            )
        }
    }


    private suspend fun getAvailableTags(registry: String?, repository: String, dockerAuth: DockerAuth?): List<String> {
        return runCatching {
            registryClient.getTags(registry, repository, dockerAuth)
        }.getOrElse { e ->
            logger.error(e) { "Error fetching tags for $repository" }
            emptyList()
        }
    }

    private suspend fun getImageDigest(registry: String?, repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        return registryClient.getImageDigest(registry, repository, tag, dockerAuth)
    }

    private suspend fun getCurrentImageDigest(image: String, @Suppress("UNUSED_PARAMETER") dockerAuth: DockerAuth? = null, namespace: String? = null, deploymentName: String? = null): String? {
        logger.debug { "[ImageChecker] Getting current digest for image: $image" }
        logger.debug { "[ImageChecker] Namespace: $namespace, DeploymentName: $deploymentName" }
        
        return runCatching {
            // If we have deployment info, get the actual running digest from Kubernetes
            if (namespace != null && deploymentName != null) {
                logger.debug { "[ImageChecker] Querying pods with label app=$deploymentName in namespace $namespace" }
                val podList = kubernetesClient.pods()
                    .inNamespace(namespace)
                    .withLabel("app", deploymentName)
                    .list()
                
                logger.debug { "[ImageChecker] Found ${podList.items.size} pods" }
                
                if (podList.items.isNotEmpty()) {
                    val pod = podList.items.first()
                    logger.debug { "[ImageChecker] Using pod: ${pod.metadata?.name}" }
                    
                    val containerStatus = pod.status?.containerStatuses?.firstOrNull()
                    logger.debug { "[ImageChecker] Container status: ${containerStatus?.name}" }
                    
                    val imageID = containerStatus?.imageID
                    logger.debug { "[ImageChecker] ImageID from pod: $imageID" }
                    
                    if (imageID != null && imageID.contains("@")) {
                        // Extract digest from imageID (format: docker://image@sha256:...)
                        val digest = imageID.substringAfter("@")
                        logger.debug { "[ImageChecker] Extracted digest from pod imageID: $digest" }
                        return digest
                    } else {
                        logger.debug { "[ImageChecker] ImageID does not contain digest" }
                    }
                } else {
                    logger.debug { "[ImageChecker] No pods found for deployment" }
                }
            } else {
                logger.debug { "[ImageChecker] Missing namespace or deployment name, cannot query Kubernetes" }
            }
            
            // Fallback: if we can't get from K8s, return null to indicate unknown
            logger.debug { "[ImageChecker] Cannot determine current digest from Kubernetes for $image" }
            null
        }.getOrElse { e ->
            logger.error(e) { "[ImageChecker] Error getting current image digest for $image" }
            null
        }
    }
}
