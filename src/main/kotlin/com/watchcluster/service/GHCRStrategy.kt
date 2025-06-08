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
        logger.debug { "[GHCR] Fetching tags for repository: $repository" }
        runCatching {
            val url = "https://ghcr.io/v2/$repository/tags/list"
            logger.debug { "[GHCR] Using URL: $url" }
            
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
            
            if (dockerAuth != null) {
                logger.debug { "[GHCR] Using Bearer token authentication" }
                requestBuilder.header("Authorization", "Bearer ${dockerAuth.password}")
            } else {
                logger.debug { "[GHCR] No authentication provided for tags fetch" }
            }
            
            val request = requestBuilder.build()
            
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "[GHCR] Failed to fetch tags from GitHub Container Registry: ${response.code}" }
                    logger.debug { "[GHCR] Response headers: ${response.headers}" }
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                logger.debug { "[GHCR] Response body length: ${body.length}" }
                val tagsResponse = mapper.readValue<com.watchcluster.service.GitHubContainerRegistryTagsResponse>(body)
                val tags = tagsResponse.tags
                logger.debug { "[GHCR] Found ${tags.size} tags: ${tags.take(10).joinToString()} ${if (tags.size > 10) "..." else ""}" }
                tags
            }
        }.getOrElse { e ->
            logger.error(e) { "[GHCR] Failed to fetch tags for $repository from GitHub Container Registry" }
            emptyList()
        }
    }
    
    override suspend fun getImageDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? = withContext(Dispatchers.IO) {
        logger.debug { "[GHCR] Getting image digest for $repository:$tag" }
        runCatching {
            // Get token (anonymous for public repos if no auth provided)
            val token = when {
                dockerAuth != null -> {
                    logger.debug { "[GHCR] Using provided authentication token" }
                    dockerAuth.password
                }
                else -> {
                    logger.debug { "[GHCR] Fetching anonymous token for public repository" }
                    getAnonymousTokenForGHCR(repository)
                }
            }
            
            if (token == null) {
                logger.warn { "[GHCR] Failed to obtain token for GitHub Container Registry" }
                return@withContext null
            }
            logger.debug { "[GHCR] Successfully obtained token" }
            
            val url = "https://ghcr.io/v2/$repository/manifests/$tag"
            logger.debug { "[GHCR] Fetching digest from: $url" }
            
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
            
            logger.debug { "[GHCR] Accept headers: ${listOf("application/vnd.docker.distribution.manifest.v2+json", "application/vnd.docker.distribution.manifest.list.v2+json", "application/vnd.oci.image.manifest.v1+json", "application/vnd.oci.image.index.v1+json").joinToString(", ")}" }
            
            requestBuilder.header("Authorization", "Bearer $token")
            
            val request = requestBuilder.build()
            
            client.newCall(request).await().use { response ->
                logger.debug { "[GHCR] Response code for digest: ${response.code}" }
                if (!response.isSuccessful) {
                    logger.warn { "[GHCR] Failed to fetch manifest from GitHub Container Registry: ${response.code}" }
                    logger.debug { "[GHCR] Response headers: ${response.headers}" }
                    return@withContext null
                }
                
                // Docker-Content-Digest header contains the digest
                val digest = response.header("Docker-Content-Digest")
                logger.debug { "[GHCR] Extracted digest from Docker-Content-Digest header for $repository:$tag -> $digest" }
                logger.debug { "[GHCR] All response headers: ${response.headers}" }
                digest
            }
        }.getOrElse { e ->
            logger.error(e) { "[GHCR] Failed to fetch digest for $repository:$tag from GitHub Container Registry" }
            null
        }
    }
    
    private suspend fun getAnonymousTokenForGHCR(repository: String): String? {
        val tokenUrl = "https://ghcr.io/token?scope=repository:$repository:pull"
        logger.debug { "[GHCR] Fetching anonymous token for repository: $repository from $tokenUrl" }
        
        val request = Request.Builder()
            .url(tokenUrl)
            .get()
            .build()
        
        return runCatching {
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "[GHCR] Failed to fetch anonymous token: ${response.code}" }
                    logger.debug { "[GHCR] Token response headers: ${response.headers}" }
                    return null
                }
                
                val body = response.body?.string() ?: return null
                logger.debug { "[GHCR] Token response body: $body" }
                val tokenResponse = mapper.readTree(body)
                val token = tokenResponse.get("token")?.asText()
                logger.debug { "[GHCR] Successfully obtained anonymous token: ${token?.take(20)}..." }
                token
            }
        }.getOrElse { e ->
            logger.error(e) { "[GHCR] Error fetching anonymous token" }
            null
        }
    }
}