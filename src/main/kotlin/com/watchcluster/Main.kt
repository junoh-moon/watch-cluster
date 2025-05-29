package com.watchcluster

import com.watchcluster.controller.WatchController
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main() = runBlocking {
    logger.info { "Starting watch-cluster..." }
    
    try {
        val kubernetesClient = KubernetesClientBuilder().build()
        logger.info { "Connected to Kubernetes cluster: ${kubernetesClient.configuration.masterUrl}" }
        
        val controller = WatchController(kubernetesClient)
        controller.start()
    } catch (e: Exception) {
        logger.error(e) { "Failed to start watch-cluster" }
        throw e
    }
}