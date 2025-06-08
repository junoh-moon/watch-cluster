package com.watchcluster.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.watchcluster.model.DockerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging
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
        runCatching {
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
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val tagsResponse = mapper.readTree(body)
                tagsResponse.get("tags")?.map { it.asText() } ?: emptyList()
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch tags for $repository from $registry" }
            emptyList()
        }
    }
    
    override suspend fun getImageDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? = withContext(Dispatchers.IO) {
        runCatching {
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
                    return@withContext null
                }
                
                // Docker-Content-Digest header contains the digest
                val digest = response.header("Docker-Content-Digest")
                logger.debug { "Generic registry digest from header: $digest" }
                digest
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch digest for $repository:$tag from $registry" }
            null
        }
    }
}