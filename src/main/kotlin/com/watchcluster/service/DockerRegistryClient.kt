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
        try {
            when {
                registry == null || registry == "docker.io" -> getDockerHubDigest(repository, tag, dockerAuth)
                registry.contains("ghcr.io") -> getGitHubContainerRegistryDigest(repository, tag, dockerAuth)
                else -> getGenericRegistryDigest(registry, repository, tag, dockerAuth)
            }
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
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
        
        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }
        
        val request = requestBuilder.build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch digest from Docker Hub: ${response.code}" }
                return null
            }
            
            val body = response.body?.string() ?: return null
            val tagInfo = mapper.readTree(body)
            return tagInfo.get("digest")?.asText()
        }
    }
    
    private fun getGitHubContainerRegistryDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        val url = "https://ghcr.io/v2/$repository/manifests/$tag"
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/vnd.docker.distribution.manifest.v2+json")
        
        if (dockerAuth != null) {
            requestBuilder.header("Authorization", "Bearer ${dockerAuth.password}")
        }
        
        val request = requestBuilder.build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch manifest from GitHub Container Registry: ${response.code}" }
                return null
            }
            
            // Docker-Content-Digest header contains the digest
            return response.header("Docker-Content-Digest")
        }
    }
    
    private fun getGenericRegistryDigest(registry: String, repository: String, tag: String, dockerAuth: DockerAuth?): String? {
        val url = "https://$registry/v2/$repository/manifests/$tag"
        
        val requestBuilder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/vnd.docker.distribution.manifest.v2+json")
        
        if (dockerAuth != null) {
            requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
        }
        
        val request = requestBuilder.build()
        
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch manifest from $registry: ${response.code}" }
                return null
            }
            
            // Docker-Content-Digest header contains the digest
            return response.header("Docker-Content-Digest")
        }
    }
}