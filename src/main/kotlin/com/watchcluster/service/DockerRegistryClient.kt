package com.watchcluster.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.watchcluster.model.DockerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import okhttp3.*
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val logger = KotlinLogging.logger {}

// Extension function to make OkHttp async
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
    
    continuation.invokeOnCancellation {
        cancel()
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerHubTagsResponse(
    val results: List<DockerHubTag>
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerHubTag(
    val name: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerManifest(
    val config: DockerManifestConfig? = null,
    val digest: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DockerManifestConfig(
    val digest: String
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubContainerRegistryTagsResponse(
    val tags: List<String>
)

class DockerRegistryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val mapper = jacksonObjectMapper()
    private val ghcrStrategy = GHCRStrategy()
    
    suspend fun getTags(registry: String?, repository: String, dockerAuth: DockerAuth? = null): List<String> = withContext(Dispatchers.IO) {
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
    
    suspend fun getImageDigest(registry: String?, repository: String, tag: String, dockerAuth: DockerAuth? = null): String? = withContext(Dispatchers.IO) {
        logger.debug { "Getting image digest for registry=$registry, repository=$repository, tag=$tag" }
        runCatching {
            val digest = when {
                registry == null || registry == "docker.io" -> {
                    logger.debug { "Using Docker Hub for digest lookup" }
                    getDockerHubDigest(repository, tag, dockerAuth)
                }
                registry.contains("ghcr.io") -> {
                    logger.debug { "Using GitHub Container Registry for digest lookup" }
                    getGitHubContainerRegistryDigest(repository, tag, dockerAuth)
                }
                else -> {
                    logger.debug { "Using generic registry for digest lookup" }
                    getGenericRegistryDigest(registry, repository, tag, dockerAuth)
                }
            }
            logger.debug { "Retrieved digest: $digest" }
            digest
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch digest for $repository:$tag from $registry" }
            null
        }
    }
    
    private suspend fun getDockerHubTags(repository: String, dockerAuth: DockerAuth?): List<String> {
        val namespace = if (repository.contains("/")) repository else "library/$repository"
        val url = "https://hub.docker.com/v2/repositories/$namespace/tags/?page_size=100"
        
        val requestBuilder = Request.Builder()
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
    
    private suspend fun getGitHubContainerRegistryTags(repository: String, dockerAuth: DockerAuth?): List<String> {
        logger.info { "Delegating to GHCRStrategy for repository: $repository, auth present: ${dockerAuth != null}" }
        return ghcrStrategy.getTags(repository, dockerAuth)
    }
    
    private suspend fun getGenericRegistryTags(registry: String, repository: String, dockerAuth: DockerAuth?): List<String> {
        val url = "https://$registry/v2/$repository/tags/list"
        
        val requestBuilder = Request.Builder()
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
    
    private suspend fun getDockerHubDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        val namespace = if (repository.contains("/")) repository else "library/$repository"
        val url = "https://hub.docker.com/v2/repositories/$namespace/tags/$tag/"
        logger.debug { "Fetching Docker Hub digest from: $url" }
        
        val requestBuilder = Request.Builder()
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
            val digest = tagInfo.get("digest")?.asText()
            logger.debug { "Extracted Docker Hub digest: $digest" }
            return digest
        }
    }
    
    private suspend fun getGitHubContainerRegistryDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        logger.info { "Delegating to GHCRStrategy for digest: $repository:$tag, auth present: ${dockerAuth != null}" }
        return ghcrStrategy.getImageDigest(repository, tag, dockerAuth)
    }
    
    
    private suspend fun getGenericRegistryDigest(registry: String, repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        val url = "https://$registry/v2/$repository/manifests/$tag"
        logger.debug { "Fetching generic registry digest from: $url" }
        
        val requestBuilder = Request.Builder()
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
}
