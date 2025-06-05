package com.watchcluster.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.watchcluster.model.DockerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}


class GHCRStrategy : BaseRegistryStrategy() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val mapper = jacksonObjectMapper()
    
    override suspend fun getTags(repository: String, dockerAuth: DockerAuth?): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://ghcr.io/v2/$repository/tags/list"
            
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
            
            if (dockerAuth != null) {
                requestBuilder.header("Authorization", "Bearer ${dockerAuth.password}")
            }
            
            val request = requestBuilder.build()
            
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Failed to fetch tags from GitHub Container Registry: ${response.code}" }
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val tagsResponse = mapper.readValue<com.watchcluster.service.GitHubContainerRegistryTagsResponse>(body)
                tagsResponse.tags
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch tags for $repository from GitHub Container Registry" }
            emptyList()
        }
    }
    
    override suspend fun getImageDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? = withContext(Dispatchers.IO) {
        runCatching {
            // Get token (anonymous for public repos if no auth provided)
            val token = when {
                dockerAuth != null -> dockerAuth.password
                else -> getAnonymousTokenForGHCR(repository)
            }
            
            if (token == null) {
                logger.warn { "Failed to obtain token for GitHub Container Registry" }
                return@withContext null
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
            
            client.newCall(request).await().use { response ->
                logger.debug { "GitHub Container Registry response code: ${response.code}" }
                if (!response.isSuccessful) {
                    logger.warn { "Failed to fetch manifest from GitHub Container Registry: ${response.code}" }
                    return@withContext null
                }
                
                // Docker-Content-Digest header contains the digest
                val digest = response.header("Docker-Content-Digest")
                logger.debug { "GitHub Container Registry digest from header: $digest" }
                digest
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch digest for $repository:$tag from GitHub Container Registry" }
            null
        }
    }
    
    private suspend fun getAnonymousTokenForGHCR(repository: String): String? {
        val tokenUrl = "https://ghcr.io/token?scope=repository:$repository:pull"
        logger.debug { "Fetching anonymous token for repository: $repository" }
        
        val request = Request.Builder()
            .url(tokenUrl)
            .get()
            .build()
        
        return runCatching {
            client.newCall(request).await().use { response ->
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
        }.getOrElse { e ->
            logger.error(e) { "Error fetching anonymous token" }
            null
        }
    }
}