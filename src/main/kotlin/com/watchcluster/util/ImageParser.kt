package com.watchcluster.util

data class ImageComponents(
    val registry: String?,
    val repository: String,
    val tag: String,
)

object ImageParser {
    fun parseImageString(image: String): ImageComponents {
        // Remove digest if present
        val imageWithoutDigest = image.substringBefore("@")

        // Find the last occurrence of "/" to separate registry/repo from image:tag
        val lastSlash = imageWithoutDigest.lastIndexOf("/")
        val (registryAndRepo, imageAndTag) = if (lastSlash == -1) {
            "" to imageWithoutDigest
        } else {
            imageWithoutDigest.take(lastSlash) to imageWithoutDigest.substring(lastSlash + 1)
        }

        // Extract tag from image:tag part
        val colonIndex = imageAndTag.indexOf(":")
        val (imageName, tag) = if (colonIndex == -1) {
            imageAndTag to "latest"
        } else {
            imageAndTag.substring(0, colonIndex) to imageAndTag.substring(colonIndex + 1)
        }

        // Determine registry and repository
        val (registry, repository) = when {
            registryAndRepo.isEmpty() -> null to imageName
            else -> {
                val firstSlash = registryAndRepo.indexOf("/")
                if (firstSlash == -1) {
                    // Only one part before image name, check if it's a registry
                    val possibleRegistry = registryAndRepo
                    when {
                        possibleRegistry.contains(".") ||
                            possibleRegistry.contains(":") ||
                            possibleRegistry == "localhost" -> {
                            possibleRegistry to imageName
                        }

                        else -> null to "$registryAndRepo/$imageName"
                    }
                } else {
                    // Multiple parts, first part is registry if it contains special chars
                    val possibleRegistry = registryAndRepo.substring(0, firstSlash)
                    when {
                        possibleRegistry.contains(".") ||
                            possibleRegistry.contains(":") ||
                            possibleRegistry == "localhost" -> {
                            possibleRegistry to "${registryAndRepo.substring(firstSlash + 1)}/$imageName"
                        }

                        else -> null to "$registryAndRepo/$imageName"
                    }
                }
            }
        }

        return ImageComponents(registry, repository, tag)
    }

    fun buildImageString(components: ImageComponents): String = buildImageString(components.registry, components.repository, components.tag)

    fun buildImageString(
        registry: String?,
        repository: String,
        tag: String,
    ): String = registry?.let { "$it/$repository:$tag" } ?: "$repository:$tag"

    fun isVersionTag(tag: String): Boolean = tag.matches(Regex("^v?\\d+\\.\\d+(\\.\\d+)?(-.*)?$"))

    fun parseVersion(tag: String): List<Int> {
        val versionPart = tag.removePrefix("v").split("-").first()
        return versionPart.split(".").map { it.toIntOrNull() ?: 0 }
    }

    fun compareVersions(
        v1: List<Int>,
        v2: List<Int>,
    ): Int {
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

    fun removeDigest(image: String): String = image.substringBefore("@")

    fun addDigest(
        image: String,
        digest: String,
    ): String {
        val imageWithoutDigest = removeDigest(image)
        return "$imageWithoutDigest@$digest"
    }
}
