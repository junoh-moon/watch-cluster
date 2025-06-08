package com.watchcluster.util

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

data class ImageComponents(
    val registry: String?,
    val repository: String,
    val tag: String
)

object ImageParser {
    fun parseImageString(image: String): ImageComponents {
        logger.debug { "[ImageParser] Parsing image string: $image" }
        
        val imageWithoutDigest = image.substringBefore("@")
        logger.debug { "[ImageParser] Image without digest: $imageWithoutDigest" }
        
        val parts = imageWithoutDigest.split(":")
        val tag = parts.getOrNull(1) ?: "latest"
        val repoWithRegistry = parts.first()
        logger.debug { "[ImageParser] Split parts - repo: $repoWithRegistry, tag: $tag" }
        
        val (registry, repository) = when {
            !repoWithRegistry.contains("/") -> {
                logger.debug { "[ImageParser] No slash found, treating as Docker Hub image" }
                null to repoWithRegistry
            }
            else -> {
                val firstSlash = repoWithRegistry.indexOf("/")
                val possibleRegistry = repoWithRegistry.substring(0, firstSlash)
                logger.debug { "[ImageParser] Found slash at $firstSlash, possible registry: $possibleRegistry" }
                
                when {
                    possibleRegistry.contains(".") || 
                    possibleRegistry.contains(":") || 
                    possibleRegistry == "localhost" -> {
                        logger.debug { "[ImageParser] Detected custom registry: $possibleRegistry" }
                        possibleRegistry to repoWithRegistry.substring(firstSlash + 1)
                    }
                    else -> {
                        logger.debug { "[ImageParser] Treating as Docker Hub namespace" }
                        null to repoWithRegistry
                    }
                }
            }
        }
        
        val result = ImageComponents(registry, repository, tag)
        logger.debug { "[ImageParser] Parsed result - registry: $registry, repository: $repository, tag: $tag" }
        return result
    }

    fun buildImageString(components: ImageComponents): String =
        buildImageString(components.registry, components.repository, components.tag)

    fun buildImageString(registry: String?, repository: String, tag: String): String =
        registry?.let { "$it/$repository:$tag" } ?: "$repository:$tag"

    fun isVersionTag(tag: String): Boolean {
        val isVersion = tag.matches(Regex("^v?\\d+\\.\\d+(\\.\\d+)?(-.*)?$"))
        logger.debug { "[ImageParser] Checking if tag '$tag' is version tag: $isVersion" }
        return isVersion
    }

    fun parseVersion(tag: String): List<Int> {
        logger.debug { "[ImageParser] Parsing version from tag: $tag" }
        
        val versionPart = tag.removePrefix("v").split("-").first()
        logger.debug { "[ImageParser] Version part after removing prefix and suffix: $versionPart" }
        
        val parsedVersion = versionPart.split(".").map { it.toIntOrNull() ?: 0 }
        logger.debug { "[ImageParser] Parsed version components: $parsedVersion" }
        
        return parsedVersion
    }

    fun compareVersions(v1: List<Int>, v2: List<Int>): Int {
        logger.debug { "[ImageParser] Comparing versions: $v1 vs $v2" }
        
        val maxLength = maxOf(v1.size, v2.size)
        for (i in 0 until maxLength) {
            val part1 = v1.getOrNull(i) ?: 0
            val part2 = v2.getOrNull(i) ?: 0
            if (part1 != part2) {
                val result = part1.compareTo(part2)
                logger.debug { "[ImageParser] Version comparison result: $result (v1[$i]=$part1, v2[$i]=$part2)" }
                return result
            }
        }
        logger.debug { "[ImageParser] Versions are equal" }
        return 0
    }
}