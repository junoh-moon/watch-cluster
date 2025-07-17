package com.watchcluster.client

import com.watchcluster.client.domain.*

interface K8sClient {
    // Deployment operations
    suspend fun getDeployment(namespace: String, name: String): DeploymentInfo?
    suspend fun patchDeployment(namespace: String, name: String, patchJson: String): DeploymentInfo?
    fun watchDeployments(watcher: K8sWatcher<DeploymentInfo>): AutoCloseable
    
    // Pod operations
    suspend fun getPod(namespace: String, name: String): PodInfo?
    suspend fun listPodsByLabels(namespace: String, labels: Map<String, String>): List<PodInfo>
    
    // Secret operations
    suspend fun getSecret(namespace: String, name: String): SecretInfo?
    
    // Configuration
    suspend fun getConfiguration(): K8sClientConfig
}