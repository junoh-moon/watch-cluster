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


class DockerHubStrategy : BaseRegistryStrategy() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val mapper = jacksonObjectMapper()
    
    override suspend fun getTags(repository: String, dockerAuth: DockerAuth?): List<String> = withContext(Dispatchers.IO) {
        logger.debug { "[DockerHub] Fetching tags for repository: $repository" }
        runCatching {
            val namespace = if (repository.contains("/")) repository else "library/$repository"
            val url = "https://hub.docker.com/v2/repositories/$namespace/tags/?page_size=100"
            logger.debug { "[DockerHub] Using namespace: $namespace, URL: $url" }
            
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
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val tagsResponse = mapper.readValue<com.watchcluster.service.DockerHubTagsResponse>(body)
                tagsResponse.results.map { it.name }
            }
        }.getOrElse { e ->
            logger.error(e) { "Failed to fetch tags for $repository from Docker Hub" }
            emptyList()
        }
    }
    
    override suspend fun getImageDigest(repository: String, tag: String, dockerAuth: DockerAuth?): String? = withContext(Dispatchers.IO) {
        logger.debug { "[DockerHub] Getting image digest for $repository:$tag" }
        runCatching {
            val namespace = if (repository.contains("/")) repository else "library/$repository"
            val url = "https://hub.docker.com/v2/repositories/$namespace/tags/$tag/"
            logger.debug { "[DockerHub] Fetching digest from: $url" }
            
            val requestBuilder = Request.Builder()
                .url(url)
                .get()
            
            if (dockerAuth != null) {
                logger.debug { "[DockerHub] Using authentication for digest fetch" }
                requestBuilder.header("Authorization", Credentials.basic(dockerAuth.username, dockerAuth.password))
            } else {
                logger.debug { "[DockerHub] No authentication for digest fetch" }
            }
            
            val request = requestBuilder.build()
            
            client.newCall(request).await().use { response ->
                logger.debug { "[DockerHub] Response code for digest: ${response.code}" }
                if (!response.isSuccessful) {
                    logger.warn { "[DockerHub] Failed to fetch digest from Docker Hub: ${response.code}" }
                    logger.debug { "[DockerHub] Response headers: ${response.headers}" }
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                logger.debug { "[DockerHub] Response body for digest fetch: $body" }
                val tagInfo = mapper.readTree(body)
                val digest = tagInfo.get("digest")?.asText()
                logger.debug { "[DockerHub] Extracted digest for $repository:$tag -> $digest" }
                digest
            }
        }.getOrElse { e ->
            logger.error(e) { "[DockerHub] Failed to fetch digest for $repository:$tag from Docker Hub" }
            null
        }
    }
}