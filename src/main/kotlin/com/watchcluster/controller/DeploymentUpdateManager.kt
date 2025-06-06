package com.watchcluster.controller

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Manages deployment updates with coroutine-aware mutex-based concurrency control.
 * Each deployment has its own mutex to prevent concurrent updates.
 * 
 * Kotlin's Mutex is designed for coroutines:
 * - It suspends (not blocks) when waiting for the lock
 * - Other coroutines can run on the same thread while one is waiting
 * - FIFO ordering ensures fairness
 */
@Component
class DeploymentUpdateManager {
    // One mutex per deployment to ensure serialized updates
    private val deploymentMutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Execute an update for a specific deployment with proper locking.
     * Multiple updates for the same deployment will be serialized.
     */
    suspend fun executeUpdate(
        namespace: String,
        name: String,
        updateAction: suspend () -> Unit
    ) {
        val key = "$namespace/$name"
        
        // Get or create mutex for this deployment
        val mutex = deploymentMutexes.computeIfAbsent(key) { Mutex() }
        
        // Execute update with lock - this will suspend (not block) if another
        // coroutine is already updating this deployment
        mutex.withLock {
            logger.debug { "Executing update for deployment: $key" }
            try {
                updateAction()
                logger.debug { "Update completed for deployment: $key" }
            } catch (e: Exception) {
                logger.error(e) { "Update failed for deployment: $key" }
                throw e
            }
        }
    }

    /**
     * Check if a deployment is currently being updated.
     */
    fun isUpdating(namespace: String, name: String): Boolean {
        val key = "$namespace/$name"
        return deploymentMutexes[key]?.isLocked ?: false
    }

    /**
     * Clean up mutex for a deployment when it's no longer watched.
     */
    fun removeDeployment(namespace: String, name: String) {
        val key = "$namespace/$name"
        deploymentMutexes.remove(key)
        logger.debug { "Removed mutex for deployment: $key" }
    }

    /**
     * Get the current number of tracked deployments.
     */
    fun getTrackedDeploymentCount(): Int = deploymentMutexes.size
}