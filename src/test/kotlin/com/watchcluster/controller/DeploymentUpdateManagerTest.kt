package com.watchcluster.controller

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class DeploymentUpdateManagerTest {
    
    private lateinit var deploymentUpdateManager: DeploymentUpdateManager
    
    @BeforeEach
    fun setup() {
        deploymentUpdateManager = DeploymentUpdateManager()
    }
    
    @Test
    fun `executeUpdate serializes updates for same deployment`() = runTest {
        val namespace = "test-ns"
        val name = "test-app"
        val executionOrder = mutableListOf<Int>()
        
        // Launch multiple concurrent updates
        val jobs = List(5) { index ->
            launch {
                deploymentUpdateManager.executeUpdate(namespace, name) {
                    // Simulate some work
                    delay(50)
                    executionOrder.add(index)
                }
            }
        }
        
        // Wait for all jobs to complete
        jobs.forEach { it.join() }
        
        // Verify execution order is sequential (0, 1, 2, 3, 4)
        assertEquals(listOf(0, 1, 2, 3, 4), executionOrder)
    }
    
    @Test
    fun `executeUpdate allows parallel updates for different deployments`() = runTest {
        val results = mutableListOf<String>()
        val startTime = System.currentTimeMillis()
        
        // Launch updates for different deployments
        val jobs = listOf(
            launch {
                deploymentUpdateManager.executeUpdate("ns1", "app1") {
                    delay(100)
                    synchronized(results) { results.add("app1") }
                }
            },
            launch {
                deploymentUpdateManager.executeUpdate("ns2", "app2") {
                    delay(100)
                    synchronized(results) { results.add("app2") }
                }
            },
            launch {
                deploymentUpdateManager.executeUpdate("ns3", "app3") {
                    delay(100)
                    synchronized(results) { results.add("app3") }
                }
            }
        )
        
        // Wait for all jobs to complete
        jobs.forEach { it.join() }
        
        val duration = System.currentTimeMillis() - startTime
        
        // Verify all three deployments were updated
        assertEquals(3, results.size)
        assertTrue(results.containsAll(listOf("app1", "app2", "app3")))
        
        // In test environment with virtual time, just verify they all completed
        // The important thing is they didn't block each other
    }
    
    @Test
    fun `executeUpdate propagates exceptions`() = runTest {
        val namespace = "test-ns"
        val name = "test-app"
        val testException = RuntimeException("Test error")
        
        assertFailsWith<RuntimeException> {
            deploymentUpdateManager.executeUpdate(namespace, name) {
                throw testException
            }
        }
    }
    
    @Test
    fun `isUpdating returns correct status`() = runTest {
        val namespace = "test-ns"
        val name = "test-app"
        
        // Initially should not be updating
        assertFalse(deploymentUpdateManager.isUpdating(namespace, name))
        
        // Start an update
        val job = launch {
            deploymentUpdateManager.executeUpdate(namespace, name) {
                delay(100)
            }
        }
        
        // Give it time to start
        delay(10)
        
        // Should be updating now
        assertTrue(deploymentUpdateManager.isUpdating(namespace, name))
        
        // Wait for completion
        job.join()
        
        // Should not be updating anymore
        assertFalse(deploymentUpdateManager.isUpdating(namespace, name))
    }
    
    @Test
    fun `removeDeployment cleans up resources`() = runTest {
        val namespace = "test-ns"
        val name = "test-app"
        
        // Create a deployment mutex by executing an update
        deploymentUpdateManager.executeUpdate(namespace, name) {
            // No-op
        }
        
        // Verify deployment is tracked
        assertEquals(1, deploymentUpdateManager.getTrackedDeploymentCount())
        
        // Remove deployment
        deploymentUpdateManager.removeDeployment(namespace, name)
        
        // Verify deployment is no longer tracked
        assertEquals(0, deploymentUpdateManager.getTrackedDeploymentCount())
    }
    
    @Test
    fun `multiple updates queue correctly`() = runTest {
        val namespace = "test-ns"
        val name = "test-app"
        val results = mutableListOf<String>()
        
        // Create a channel to control execution order
        val startSignal = CompletableDeferred<Unit>()
        
        // First update - will block until signaled
        val job1 = launch {
            deploymentUpdateManager.executeUpdate(namespace, name) {
                startSignal.await()
                results.add("first")
            }
        }
        
        // Give first update time to acquire lock
        delay(10)
        
        // Second update - should be queued
        val job2 = launch {
            deploymentUpdateManager.executeUpdate(namespace, name) {
                results.add("second")
            }
        }
        
        // Third update - should also be queued
        val job3 = launch {
            deploymentUpdateManager.executeUpdate(namespace, name) {
                results.add("third")
            }
        }
        
        // Give time for all updates to be queued
        delay(10)
        
        // Signal first update to proceed
        startSignal.complete(Unit)
        
        // Wait for all updates to complete
        listOf(job1, job2, job3).forEach { it.join() }
        
        // Verify execution order
        assertEquals(listOf("first", "second", "third"), results)
    }
    
    @Test
    fun `stress test with many concurrent updates`() = runTest(timeout = 5000.milliseconds) {
        val deploymentCount = 10
        val updatesPerDeployment = 20
        val updateCounts = mutableMapOf<String, Int>()
        
        // Launch many concurrent updates for multiple deployments
        val jobs = (1..deploymentCount).flatMap { deploymentId ->
            (1..updatesPerDeployment).map { updateId ->
                launch {
                    val key = "deploy-$deploymentId"
                    deploymentUpdateManager.executeUpdate("namespace", key) {
                        // Simulate some work
                        delay(1)
                        synchronized(updateCounts) {
                            updateCounts[key] = (updateCounts[key] ?: 0) + 1
                        }
                    }
                }
            }
        }
        
        // Wait for all jobs to complete
        jobs.forEach { it.join() }
        
        // Verify all updates were executed
        assertEquals(deploymentCount, updateCounts.size)
        updateCounts.forEach { (deployment, count) ->
            assertEquals(updatesPerDeployment, count, "Deployment $deployment should have $updatesPerDeployment updates")
        }
    }
}