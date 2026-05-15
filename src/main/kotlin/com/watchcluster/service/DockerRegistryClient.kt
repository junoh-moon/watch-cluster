package com.watchcluster.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.watchcluster.model.DockerAuth
import com.watchcluster.model.ImagePlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = KotlinLogging.logger {}

// Extension function to make OkHttp async
suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    continuation.resume(response)
                }

                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    continuation.resumeWithException(e)
                }
            },
        )

        continuation.invokeOnCancellation {
            cancel()
        }
    }

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerHubTagsResponse(
    val results: List<DockerHubTag>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerHubTag(
    val name: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerManifest(
    val config: DockerManifestConfig? = null,
    val digest: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerManifestConfig(
    val digest: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubContainerRegistryTagsResponse(
    val tags: List<String>,
)

class DockerRegistryClient {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private val mapper = jacksonObjectMapper()
    private val ghcrStrategy = GHCRStrategy()

    suspend fun getTags(
        registry: String?,
        repository: String,
        dockerAuth: DockerAuth? = null,
    ): List<String> =
        withContext(Dispatchers.IO) {
            logger.info { "Fetching tags for repository: $repository from registry: $registry" }
            runCatching {
                when {
                    registry == null || registry == "docker.io" -> {
                        logger.debug { "Using Docker Hub strategy for $repository" }
                        getDockerHubTags(repository, dockerAuth)
                    }
                    registry.contains("ghcr.io") -> {
                        logger.debug { "Using GitHub Container Registry strategy for $repository" }
                        getGitHubContainerRegistryTags(repository, dockerAuth)
                    }
                    else -> {
                        logger.debug { "Using generic registry strategy for $repository" }
                        getGenericRegistryTags(registry, repository, dockerAuth)
                    }
                }
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch tags for $repository from $registry" }
                emptyList()
            }
        }

    suspend fun getImageDigest(
        registry: String?,
        repository: String,
        tag: String,
        dockerAuth: DockerAuth? = null,
        platform: ImagePlatform? = null,
    ): String? =
        withContext(Dispatchers.IO) {
            logger.debug { "Getting image digest for registry=$registry, repository=$repository, tag=$tag, platform=$platform" }
            runCatching {
                val digest =
                    when {
                        registry == null || registry == "docker.io" -> {
                            logger.debug { "Using Docker Hub for digest lookup" }
                            getDockerHubDigest(repository, tag, dockerAuth, platform)
                        }
                        registry.contains("ghcr.io") -> {
                            logger.debug { "Using GitHub Container Registry for digest lookup" }
                            getGitHubContainerRegistryDigest(repository, tag, dockerAuth)
                        }
                        else -> {
                            logger.debug { "Using generic registry for digest lookup" }
                            getGenericRegistryDigest(registry, repository, tag, dockerAuth, platform)
                        }
                    }
                logger.debug { "Retrieved digest: $digest" }
                digest
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch digest for $repository:$tag from $registry" }
                null
            }
        }

    private suspend fun getDockerHubTags(
        repository: String,
        dockerAuth: DockerAuth?,
    ): List<String> {
        val namespace = if (repository.contains("/")) repository else "library/$repository"
        val url = "https://hub.docker.com/v2/repositories/$namespace/tags/?page_size=100"

        val requestBuilder =
            Request
                .Builder()
                .url(url)
                .get()

        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }

        val request = requestBuilder.build()

        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch tags from Docker Hub: ${response.code}" }
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val tagsResponse = mapper.readValue<DockerHubTagsResponse>(body)
            return tagsResponse.results.map { it.name }
        }
    }

    private suspend fun getGitHubContainerRegistryTags(
        repository: String,
        dockerAuth: DockerAuth?,
    ): List<String> {
        logger.info { "Delegating to GHCRStrategy for repository: $repository, auth present: ${dockerAuth != null}" }
        return ghcrStrategy.getTags(repository, dockerAuth)
    }

    private suspend fun getGenericRegistryTags(
        registry: String,
        repository: String,
        dockerAuth: DockerAuth?,
    ): List<String> {
        val url = "https://$registry/v2/$repository/tags/list"

        val requestBuilder =
            Request
                .Builder()
                .url(url)
                .get()

        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }

        val request = requestBuilder.build()

        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch tags from $registry: ${response.code}" }
                return emptyList()
            }

            val body = response.body?.string() ?: return emptyList()
            val tagsResponse = mapper.readTree(body)
            return tagsResponse.get("tags")?.map { it.asText() } ?: emptyList()
        }
    }

    private suspend fun getDockerHubDigest(
        repository: String,
        tag: String,
        dockerAuth: DockerAuth?,
        platform: ImagePlatform?,
    ): String? {
        val namespace = if (repository.contains("/")) repository else "library/$repository"

        if (platform != null && isDigestReference(tag)) {
            return getDockerHubPlatformDigestForReference(namespace, tag, dockerAuth, platform)
        }

        val url = "https://hub.docker.com/v2/repositories/$namespace/tags/$tag/"
        logger.debug { "Fetching Docker Hub digest from: $url" }

        val requestBuilder =
            Request
                .Builder()
                .url(url)
                .get()

        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }

        val request = requestBuilder.build()

        client.newCall(request).await().use { response ->
            logger.debug { "Docker Hub response code: ${response.code}" }
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch digest from Docker Hub: ${response.code}" }
                return null
            }

            val body = response.body?.string() ?: return null
            logger.debug { "Docker Hub response body: $body" }
            val tagInfo = mapper.readTree(body)
            platform?.let {
                val platformDigest = findPlatformDigest(tagInfo.get("images"), it)
                if (platformDigest != null) {
                    logger.debug { "Extracted Docker Hub platform digest: $platformDigest" }
                    return platformDigest
                }
            }
            val digest = tagInfo.get("digest")?.asText()
            logger.debug { "Extracted Docker Hub digest: $digest" }
            return digest
        }
    }

    private fun findPlatformDigest(
        imagesNode: JsonNode?,
        platform: ImagePlatform,
    ): String? {
        if (imagesNode == null || !imagesNode.isArray) return null

        return imagesNode
            .firstOrNull { image ->
                val platformNode = image.get("platform") ?: image
                val os = platformNode.get("os")?.asText()
                val architecture = platformNode.get("architecture")?.asText()
                val variant = platformNode.get("variant")?.asText()

                os == platform.os &&
                    architecture == platform.architecture &&
                    (platform.variant == null || variant == platform.variant)
            }?.get("digest")
            ?.asText()
    }

    private suspend fun getGitHubContainerRegistryDigest(
        repository: String,
        tag: String,
        dockerAuth: DockerAuth?,
    ): String? {
        logger.info { "Delegating to GHCRStrategy for digest: $repository:$tag, auth present: ${dockerAuth != null}" }
        return ghcrStrategy.getImageDigest(repository, tag, dockerAuth)
    }

    private suspend fun getDockerHubPlatformDigestForReference(
        namespace: String,
        reference: String,
        dockerAuth: DockerAuth?,
        platform: ImagePlatform,
    ): String? {
        val token = getDockerHubToken(namespace, dockerAuth) ?: return null
        return getRegistryPlatformDigest(
            registry = "registry-1.docker.io",
            repository = namespace,
            reference = reference,
            authorizationHeader = "Bearer $token",
            platform = platform,
        )
    }

    private suspend fun getDockerHubToken(
        namespace: String,
        dockerAuth: DockerAuth?,
    ): String? {
        val url =
            "https://auth.docker.io/token"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("service", "registry.docker.io")
                .addQueryParameter("scope", "repository:$namespace:pull")
                .build()

        val requestBuilder = Request.Builder().url(url).get()
        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }

        client.newCall(requestBuilder.build()).await().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch Docker Hub auth token: ${response.code}" }
                return null
            }

            val body = response.body?.string() ?: return null
            val json = mapper.readTree(body)
            return json.get("token")?.asText() ?: json.get("access_token")?.asText()
        }
    }

    private suspend fun getGenericRegistryDigest(
        registry: String,
        repository: String,
        tag: String,
        dockerAuth: DockerAuth?,
        platform: ImagePlatform?,
    ): String? {
        if (platform != null) {
            return getRegistryPlatformDigest(
                registry = registry,
                repository = repository,
                reference = tag,
                authorizationHeader = dockerAuth?.let { Credentials.basic(it.username, it.password) },
                platform = platform,
            )
        }

        val url = "https://$registry/v2/$repository/manifests/$tag"
        logger.debug { "Fetching generic registry digest from: $url" }

        val requestBuilder =
            Request
                .Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/vnd.docker.distribution.manifest.v2+json")

        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }

        val request = requestBuilder.build()

        client.newCall(request).await().use { response ->
            logger.debug { "Generic registry response code: ${response.code}" }
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch manifest from $registry: ${response.code}" }
                return null
            }

            // Docker-Content-Digest header contains the digest
            val digest = response.header("Docker-Content-Digest")
            logger.debug { "Generic registry digest from header: $digest" }
            return digest
        }
    }

    private suspend fun getRegistryPlatformDigest(
        registry: String,
        repository: String,
        reference: String,
        authorizationHeader: String?,
        platform: ImagePlatform,
    ): String? {
        val url = "https://$registry/v2/$repository/manifests/$reference"
        logger.debug { "Fetching registry platform digest from: $url for platform=$platform" }

        val requestBuilder =
            Request
                .Builder()
                .url(url)
                .get()
                .addHeader(
                    "Accept",
                    listOf(
                        "application/vnd.oci.image.index.v1+json",
                        "application/vnd.docker.distribution.manifest.list.v2+json",
                        "application/vnd.oci.image.manifest.v1+json",
                        "application/vnd.docker.distribution.manifest.v2+json",
                    ).joinToString(", "),
                )

        if (authorizationHeader != null) {
            requestBuilder.header("Authorization", authorizationHeader)
        }

        client.newCall(requestBuilder.build()).await().use { response ->
            logger.debug { "Registry platform digest response code: ${response.code}" }
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch manifest from $registry: ${response.code}" }
                return null
            }

            val digest = response.header("Docker-Content-Digest")
            val body = response.body?.string()
            if (!body.isNullOrBlank()) {
                val manifest = mapper.readTree(body)
                findPlatformDigest(manifest.get("manifests"), platform)?.let { platformDigest ->
                    logger.debug { "Registry platform digest from manifest list: $platformDigest" }
                    return platformDigest
                }
            }

            logger.debug { "Registry digest from header: $digest" }
            return digest
        }
    }

    private fun isDigestReference(reference: String): Boolean = reference.startsWith("sha256:")
}

suspend fun DockerRegistryClient.resolvePlatformDigest(
    registry: String?,
    repository: String,
    digest: String,
    dockerAuth: DockerAuth?,
    platform: ImagePlatform?,
): String {
    if (platform == null) return digest
    return runCatching {
        getImageDigest(registry, repository, digest, dockerAuth, platform) ?: digest
    }.getOrElse { e ->
        logger.debug { "Could not resolve platform digest for $digest on $platform: ${e.message}" }
        digest
    }
}
