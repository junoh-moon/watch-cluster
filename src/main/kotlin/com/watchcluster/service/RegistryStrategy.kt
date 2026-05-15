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
