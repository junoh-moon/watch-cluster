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

        val (registry, repository) =
            when {
                registryAndRepo.isEmpty() -> null to imageName
                else -> {
                    val firstSlash = registryAndRepo.indexOf("/")
                    if (firstSlash == -1) {
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
