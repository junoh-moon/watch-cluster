package com.watchcluster.client

import com.watchcluster.client.domain.*

interface K8sClient {
    // Deployment operations
    fun getDeployment(namespace: String, name: String): DeploymentInfo?
    fun patchDeployment(namespace: String, name: String, patchJson: String): DeploymentInfo?
    fun watchDeployments(watcher: K8sWatcher<DeploymentInfo>): AutoCloseable
    
    // Pod operations
    fun getPod(namespace: String, name: String): PodInfo?
    fun listPodsByLabels(namespace: String, labels: Map<String, String>): List<PodInfo>
    
    // Secret operations
    fun getSecret(namespace: String, name: String): SecretInfo?
    
    // Configuration
    fun getConfiguration(): K8sClientConfig
}