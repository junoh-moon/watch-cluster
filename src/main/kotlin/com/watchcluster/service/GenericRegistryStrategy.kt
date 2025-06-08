package com.watchcluster.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.watchcluster.model.DockerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class GenericRegistryStrategy(private val registry: String) : BaseRegistryStrategy() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val mapper = jacksonObjectMapper()
    
    override suspend fun getTags(repository: String, dockerAuth: DockerAuth?): List<String> = withContext(Dispatchers.IO) {
        logger.debug { "[GenericRegistry:$registry] Fetching tags for repository: $repository" }
        runCatching {
            val url = "https://$registry/v2/$repository/tags/list"
            logger.debug { "[GenericRegistry:$registry] Using URL: $url" }
            
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
            
            if (dockerAuth != null) {
                logger.debug { "[GenericRegistry:$registry] Using basic authentication for user: ${dockerAuth.username}" }
                requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
            } else {
                logger.debug { "[GenericRegistry:$registry] No authentication provided for tags fetch" }
            }
            
            val request = requestBuilder.build()
            
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "[GenericRegistry:$registry] Failed to fetch tags: ${response.code}" }
                    logger.debug { "[GenericRegistry:$registry] Response headers: ${response.headers}" }
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                logger.debug { "[GenericRegistry:$registry] Response body length: ${body.length}" }
                val tagsResponse = mapper.readTree(body)
                val tags = tagsResponse.get("tags")?.map { it.asText() } ?: emptyList()
                logger.debug { "[GenericRegistry:$registry] Found ${tags.size} tags: ${tags.take(10).joinToString()} ${if (tags.size > 10) "..." else ""}" }
                tags
            }
        }.getOrElse { e ->
            logger.error(e) { "[GenericRegistry:$registry] Failed to fetch tags for $repository" }
            emptyList()
        }
    }
    
    override suspend fun getImageDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? = withContext(Dispatchers.IO) {
        logger.debug { "[GenericRegistry:$registry] Getting image digest for $repository:$tag" }
        runCatching {
            val url = "https://$registry/v2/$repository/manifests/$tag"
            logger.debug { "[GenericRegistry:$registry] Fetching digest from: $url" }
            
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
                .addHeader("Accept", "application/vnd.docker.distribution.manifest.v2+json")
            
            logger.debug { "[GenericRegistry:$registry] Accept header: application/vnd.docker.distribution.manifest.v2+json" }
            
            if (dockerAuth != null) {
                logger.debug { "[GenericRegistry:$registry] Using basic authentication for digest fetch" }
                requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
            } else {
                logger.debug { "[GenericRegistry:$registry] No authentication for digest fetch" }
            }
            
            val request = requestBuilder.build()
            
            client.newCall(request).await().use { response ->
                logger.debug { "[GenericRegistry:$registry] Response code for digest: ${response.code}" }
                if (!response.isSuccessful) {
                    logger.warn { "[GenericRegistry:$registry] Failed to fetch manifest: ${response.code}" }
                    logger.debug { "[GenericRegistry:$registry] Response headers: ${response.headers}" }
                    return@withContext null
                }
                
                // Docker-Content-Digest header contains the digest
                val digest = response.header("Docker-Content-Digest")
                logger.debug { "[GenericRegistry:$registry] Extracted digest from Docker-Content-Digest header for $repository:$tag -> $digest" }
                logger.debug { "[GenericRegistry:$registry] All response headers: ${response.headers}" }
                digest
            }
        }.getOrElse { e ->
            logger.error(e) { "[GenericRegistry:$registry] Failed to fetch digest for $repository:$tag" }
            null
        }
    }
}