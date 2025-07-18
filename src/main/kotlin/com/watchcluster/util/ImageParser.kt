package com.watchcluster.util

data class ImageComponents(
    val registry: String?,
    val repository: String,
    val tag: String,
)

object ImageParser {
    fun parseImageString(image: String): ImageComponents {
        val imageWithoutDigest = image.substringBefore("@")
        val parts = imageWithoutDigest.split(":")
        val tag = parts.getOrNull(1) ?: "latest"
        val repoWithRegistry = parts.first()

        val (registry, repository) =
            when {
                !repoWithRegistry.contains("/") -> null to repoWithRegistry
                else -> {
                    val firstSlash = repoWithRegistry.indexOf("/")
                    val possibleRegistry = repoWithRegistry.substring(0, firstSlash)
                    when {
                        possibleRegistry.contains(".") ||
                            possibleRegistry.contains(":") ||
                            possibleRegistry == "localhost" -> {
                            possibleRegistry to repoWithRegistry.substring(firstSlash + 1)
                        }
                        else -> null to repoWithRegistry
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
