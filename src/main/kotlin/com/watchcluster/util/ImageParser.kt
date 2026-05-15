package com.watchcluster.util

data class ImageComponents(
    val registry: String?,
    val repository: String,
    val tag: String,
)

object ImageParser {
    fun parseImageString(image: String): ImageComponents {
        val imageWithoutDigest = image.substringBefore("@")

        val lastSlash = imageWithoutDigest.lastIndexOf("/")
        val (registryAndRepo, imageAndTag) =
            if (lastSlash == -1) {
                "" to imageWithoutDigest
            } else {
                imageWithoutDigest.take(lastSlash) to imageWithoutDigest.substring(lastSlash + 1)
            }

        val colonIndex = imageAndTag.indexOf(":")
        val (imageName, tag) =
            if (colonIndex == -1) {
                imageAndTag to "latest"
            } else {
                imageAndTag.substring(0, colonIndex) to imageAndTag.substring(colonIndex + 1)
            }

        val (registry, repository) = splitRegistry(registryAndRepo, imageName)

        return ImageComponents(registry, repository, tag)
    }

    private fun splitRegistry(
        registryAndRepo: String,
        imageName: String,
    ): Pair<String?, String> {
        if (registryAndRepo.isEmpty()) return null to imageName

        val firstSlash = registryAndRepo.indexOf("/")
        val possibleRegistry = if (firstSlash == -1) registryAndRepo else registryAndRepo.substring(0, firstSlash)
        val restOfPath = if (firstSlash == -1) "" else registryAndRepo.substring(firstSlash + 1)

        return if (looksLikeRegistry(possibleRegistry)) {
            val repository = if (restOfPath.isEmpty()) imageName else "$restOfPath/$imageName"
            possibleRegistry to repository
        } else {
            null to "$registryAndRepo/$imageName"
        }
    }

    private fun looksLikeRegistry(host: String): Boolean = host.contains(".") || host.contains(":") || host == "localhost"

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

    fun removeDigest(image: String): String = image.substringBefore("@")

    fun addDigest(
        image: String,
        digest: String,
    ): String {
        val imageWithoutDigest = removeDigest(image)
        return "$imageWithoutDigest@$digest"
    }

    fun extractDigest(imageRef: String?): String? =
        imageRef
            ?.takeIf { it.contains("@") }
            ?.substringAfter("@")
            ?.takeIf { it.isNotBlank() }
}

infix operator fun List<Int>.compareTo(other: List<Int>): Int =
    this.zipLongest(other, 0)
        .map { (l, r) -> l.compareTo(r) }
        .firstOrNull { it != 0 } ?: 0
