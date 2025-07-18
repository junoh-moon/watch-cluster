package com.watchcluster.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.watchcluster.model.DockerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

class GHCRStrategy : BaseRegistryStrategy() {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    private val mapper = jacksonObjectMapper()

    override suspend fun getTags(
        repository: String,
        dockerAuth: DockerAuth?,
    ): List<String> =
        withContext(Dispatchers.IO) {
            logger.info { "GHCRStrategy.getTags called for repository: $repository, auth present: ${dockerAuth != null}" }
            runCatching {
                // Use skopeo to list tags directly from GHCR
                val imageRef = "docker://ghcr.io/$repository"
                logger.debug { "Using skopeo to list tags for: $imageRef" }

                val command = mutableListOf("skopeo", "list-tags", imageRef)

                // Set authentication if provided
                if (dockerAuth != null) {
                    logger.debug { "Adding authentication credentials for skopeo" }
                    command.addAll(listOf("--username", dockerAuth.username, "--password", dockerAuth.password))
                }

                val processBuilder = ProcessBuilder(command)
                val process = processBuilder.start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    logger.warn { "skopeo command failed for $repository with exit code $exitCode: $errorOutput" }
                    return@withContext emptyList()
                }

                // Parse JSON output from skopeo
                val jsonResponse = mapper.readTree(output)
                val tags = jsonResponse.get("Tags")?.map { it.asText() } ?: emptyList()

                logger.info { "Successfully fetched ${tags.size} tags for $repository using skopeo" }
                tags
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch tags for $repository using skopeo" }
                emptyList()
            }
        }

    override suspend fun getImageDigest(
        repository: String,
        tag: String,
        dockerAuth: DockerAuth?,
    ): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                // Use skopeo to get image digest directly from GHCR
                val imageRef = "docker://ghcr.io/$repository:$tag"
                logger.debug { "Using skopeo to get digest for: $imageRef" }

                val command = mutableListOf("skopeo", "inspect", imageRef)

                // Set authentication if provided
                if (dockerAuth != null) {
                    logger.debug { "Adding authentication credentials for skopeo" }
                    command.addAll(listOf("--username", dockerAuth.username, "--password", dockerAuth.password))
                }

                val processBuilder = ProcessBuilder(command)
                val process = processBuilder.start()
                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (exitCode != 0) {
                    val errorOutput = process.errorStream.bufferedReader().readText()
                    logger.warn { "skopeo inspect command failed for $repository:$tag with exit code $exitCode: $errorOutput" }
                    return@withContext null
                }

                // Parse JSON output from skopeo inspect to get digest
                val jsonResponse = mapper.readTree(output)
                val digest = jsonResponse.get("Digest")?.asText()

                logger.debug { "Successfully got digest for $repository:$tag using skopeo: $digest" }
                digest
            }.getOrElse { e ->
                logger.error(e) { "Failed to fetch digest for $repository:$tag using skopeo" }
                null
            }
        }
}
