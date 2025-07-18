package com.watchcluster.service

import com.watchcluster.model.DockerAuth

interface RegistryStrategy {
    suspend fun getTags(
        repository: String,
        dockerAuth: DockerAuth? = null,
    ): List<String>

    suspend fun getImageDigest(
        repository: String,
        tag: String,
        dockerAuth: DockerAuth? = null,
    ): String?
}

abstract class BaseRegistryStrategy : RegistryStrategy {
    protected fun buildRepositoryPath(repository: String): String = repository

    protected fun formatAuthHeader(dockerAuth: DockerAuth?): String? =
        dockerAuth?.let { "Basic ${java.util.Base64.getEncoder().encodeToString("${it.username}:${it.password}".toByteArray())}" }
}
