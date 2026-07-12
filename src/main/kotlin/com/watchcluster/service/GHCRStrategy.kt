package com.watchcluster.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.watchcluster.model.DockerAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

internal data class SkopeoCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

class GHCRStrategy internal constructor(
    private val commandRunner: (List<String>) -> SkopeoCommandResult,
) : RegistryStrategy {
    constructor() : this(::runSkopeoCommand)

    private val mapper = jacksonObjectMapper()

    override suspend fun getTags(
        repository: String,
        dockerAuth: DockerAuth?,
    ): List<String> =
        withContext(Dispatchers.IO) {
            logger.info { "GHCRStrategy.getTags called for repository: $repository, auth present: ${dockerAuth != null}" }
            // Use skopeo to list tags directly from GHCR
            val imageRef = "docker://ghcr.io/$repository"
            logger.debug { "Using skopeo to list tags for: $imageRef" }

            val command = mutableListOf("skopeo", "list-tags", imageRef)

            // Set authentication if provided
            if (dockerAuth != null) {
                logger.debug { "Adding authentication credentials for skopeo" }
                command.addAll(listOf("--username", dockerAuth.username, "--password", dockerAuth.password))
            }

            val result = commandRunner(command)

            if (result.exitCode != 0) {
                throw IllegalStateException(
                    "skopeo command failed for $repository with exit code ${result.exitCode}: ${result.stderr}",
                )
            }

            // Parse JSON output from skopeo
            val jsonResponse = mapper.readTree(result.stdout)
            val tags = jsonResponse.get("Tags")?.map { it.asText() } ?: emptyList()

            logger.info { "Successfully fetched ${tags.size} tags for $repository using skopeo" }
            tags
        }

    override suspend fun getImageDigest(
        repository: String,
        tag: String,
        dockerAuth: DockerAuth?,
    ): String? =
        withContext(Dispatchers.IO) {
            // Use skopeo to get image digest directly from GHCR
            val imageRef = buildImageReference(repository, tag)
            logger.debug { "Using skopeo to get digest for: $imageRef" }

            val command = mutableListOf("skopeo", "inspect", imageRef)

            // Set authentication if provided
            if (dockerAuth != null) {
                logger.debug { "Adding authentication credentials for skopeo" }
                command.addAll(listOf("--username", dockerAuth.username, "--password", dockerAuth.password))
            }

            val result = commandRunner(command)

            if (result.exitCode != 0) {
                throw IllegalStateException(
                    "skopeo inspect failed for $repository:$tag with exit code ${result.exitCode}: ${result.stderr}",
                )
            }

            // Parse JSON output from skopeo inspect to get digest
            val jsonResponse = mapper.readTree(result.stdout)
            val digest = jsonResponse.get("Digest")?.asText()

            logger.debug { "Successfully got digest for $repository:$tag using skopeo: $digest" }
            digest
        }

    internal fun buildImageReference(
        repository: String,
        reference: String,
    ): String {
        val separator = if (reference.startsWith("sha256:")) "@" else ":"
        return "docker://ghcr.io/$repository$separator$reference"
    }
}

private fun runSkopeoCommand(command: List<String>): SkopeoCommandResult {
    val process = ProcessBuilder(command).start()
    val stdout = process.inputStream.bufferedReader().readText()
    val stderr = process.errorStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    return SkopeoCommandResult(exitCode, stdout, stderr)
}
