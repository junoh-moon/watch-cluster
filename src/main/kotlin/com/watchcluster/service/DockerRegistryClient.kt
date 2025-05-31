package com.watchcluster.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.watchcluster.model.DockerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

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
    
    suspend fun getTags(registry: String?, repository: String, dockerAuth: DockerAuth? = null): List<String> = withContext(Dispatchers.IO) {
        try {
            when {
                registry == null || registry == "docker.io" -> getDockerHubTags(repository, dockerAuth)
                registry.contains("ghcr.io") -> getGitHubContainerRegistryTags(repository, dockerAuth)
                else -> getGenericRegistryTags(registry, repository, dockerAuth)
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch tags for $repository from $registry" }
            emptyList()
        }
    }
    
    suspend fun getImageDigest(registry: String?, repository: String, tag: String, dockerAuth: DockerAuth? = null): String? = withContext(Dispatchers.IO) {
        logger.debug { "Getting image digest for registry=$registry, repository=$repository, tag=$tag" }
        try {
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
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch digest for $repository:$tag from $registry" }
            null
        }
    }
    
    private fun getDockerHubTags(repository: String, dockerAuth: DockerAuth?): List<String> {
        val namespace = if (repository.contains("/")) repository else "library/$repository"
        val url = "https://hub.docker.com/v2/repositories/$namespace/tags/?page_size=100"
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        
        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }
        
        val request = requestBuilder.build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch tags from Docker Hub: ${response.code}" }
                return emptyList()
            }
            
            val body = response.body?.string() ?: return emptyList()
            val tagsResponse = mapper.readValue<DockerHubTagsResponse>(body)
            return tagsResponse.results.map { it.name }
        }
    }
    
    private fun getGitHubContainerRegistryTags(repository: String, dockerAuth: DockerAuth?): List<String> {
        val url = "https://ghcr.io/v2/$repository/tags/list"
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        
        if (dockerAuth != null) {
            requestBuilder.header("Authorization", "Bearer ${dockerAuth.password}")
        }
        
        val request = requestBuilder.build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch tags from GitHub Container Registry: ${response.code}" }
                return emptyList()
            }
            
            val body = response.body?.string() ?: return emptyList()
            val tagsResponse = mapper.readValue<GitHubContainerRegistryTagsResponse>(body)
            return tagsResponse.tags
        }
    }
    
    private fun getGenericRegistryTags(registry: String, repository: String, dockerAuth: DockerAuth?): List<String> {
        val url = "https://$registry/v2/$repository/tags/list"
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        
        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }
        
        val request = requestBuilder.build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch tags from $registry: ${response.code}" }
                return emptyList()
            }
            
            val body = response.body?.string() ?: return emptyList()
            val tagsResponse = mapper.readTree(body)
            return tagsResponse.get("tags")?.map { it.asText() } ?: emptyList()
        }
    }
    
    private fun getDockerHubDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? {
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
        
        client.newCall(request).execute().use { response ->
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
    
    private fun getGitHubContainerRegistryDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        // Get token (anonymous for public repos if no auth provided)
        val token = when {
            dockerAuth != null -> dockerAuth.password
            else -> getAnonymousTokenForGHCR(repository)
        }
        
        if (token == null) {
            logger.warn { "Failed to obtain token for GitHub Container Registry" }
            return null
        }
        
        val url = "https://ghcr.io/v2/$repository/manifests/$tag"
        logger.debug { "Fetching GitHub Container Registry digest from: $url" }
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            // Support both Docker V2 and OCI formats
            .header("Accept", 
                listOf(
                    "application/vnd.docker.distribution.manifest.v2+json",
                    "application/vnd.docker.distribution.manifest.list.v2+json",
                    "application/vnd.oci.image.manifest.v1+json",
                    "application/vnd.oci.image.index.v1+json"
                ).joinToString(", ")
            )
        
        requestBuilder.header("Authorization", "Bearer $token")
        
        val request = requestBuilder.build()
        
        client.newCall(request).execute().use { response ->
            logger.debug { "GitHub Container Registry response code: ${response.code}" }
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch manifest from GitHub Container Registry: ${response.code}" }
                return null
            }
            
            // Docker-Content-Digest header contains the digest
            val digest = response.header("Docker-Content-Digest")
            logger.debug { "GitHub Container Registry digest from header: $digest" }
            return digest
        }
    }
    
    private fun getAnonymousTokenForGHCR(repository: String): String? {
        val tokenUrl = "https://ghcr.io/token?scope=repository:$repository:pull"
        logger.debug { "Fetching anonymous token for repository: $repository" }
        
        val request = Request.Builder()
            .url(tokenUrl)
            .get()
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Failed to fetch anonymous token: ${response.code}" }
                    return null
                }
                
                val body = response.body?.string() ?: return null
                val tokenResponse = mapper.readTree(body)
                val token = tokenResponse.get("token")?.asText()
                logger.debug { "Successfully obtained anonymous token" }
                token
            }
        } catch (e: Exception) {
            logger.error(e) { "Error fetching anonymous token" }
            null
        }
    }
    
    private fun getGenericRegistryDigest(registry: String, repository: String, tag: String, dockerAuth: DockerAuth?): String? {
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
        
        client.newCall(request).execute().use { response ->
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
