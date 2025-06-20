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
        logger.info { "GHCRStrategy.getTags called for repository: $repository, auth present: ${dockerAuth != null}" }
        runCatching {
            // Get token (anonymous for public repos if no auth provided)
            val token = when {
                dockerAuth != null -> {
                    logger.debug { "Using provided auth token for $repository" }
                    dockerAuth.password
                }
                else -> {
                    logger.debug { "Fetching anonymous token for $repository" }
                    getAnonymousTokenForGHCR(repository)
                }
            }
            
            if (token == null) {
                logger.warn { "Failed to obtain token for GitHub Container Registry for repository: $repository" }
                return@withContext emptyList()
            }
            
            val url = "https://ghcr.io/v2/$repository/tags/list"
            logger.debug { "Fetching tags from URL: $url" }
            
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $token")
            
            val request = requestBuilder.build()
            
            client.newCall(request).await().use { response ->
                logger.debug { "GHCR tags response code: ${response.code} for repository: $repository" }
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    logger.warn { "Failed to fetch tags from GitHub Container Registry for $repository: ${response.code}, body: $errorBody" }
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val tagsResponse = mapper.readValue<com.watchcluster.service.GitHubContainerRegistryTagsResponse>(body)
                logger.info { "Successfully fetched ${tagsResponse.tags.size} tags for $repository from GHCR" }
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
        val tokenUrl = "https://ghcr.io/token?service=ghcr.io&scope=repository:$repository:pull"
        logger.info { "Fetching anonymous token from: $tokenUrl" }
        
        val request = Request.Builder()
            .url(tokenUrl)
            .get()
            .build()
        
        return runCatching {
            client.newCall(request).await().use { response ->
                logger.debug { "Anonymous token response code: ${response.code} for repository: $repository" }
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    logger.warn { "Failed to fetch anonymous token for $repository: ${response.code}, body: $errorBody" }
                    return null
                }
                
                val body = response.body?.string() ?: return null
                val tokenResponse = mapper.readTree(body)
                val token = tokenResponse.get("token")?.asText()
                if (token != null) {
                    logger.info { "Successfully obtained anonymous token for $repository (token length: ${token.length})" }
                } else {
                    logger.warn { "Token field was null in response for $repository" }
                }
                token
            }
        }.getOrElse { e ->
            logger.error(e) { "Error fetching anonymous token for $repository" }
            null
        }
    }
}