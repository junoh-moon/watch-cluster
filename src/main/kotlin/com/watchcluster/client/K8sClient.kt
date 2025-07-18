package com.watchcluster.client

import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.K8sClientConfig
import com.watchcluster.client.domain.PodInfo
import com.watchcluster.client.domain.SecretInfo

interface K8sClient {
    // Deployment operations
    suspend fun getDeployment(
        namespace: String,
        name: String,
    ): DeploymentInfo?

    suspend fun patchDeployment(
        namespace: String,
        name: String,
        patchJson: String,
    ): DeploymentInfo?

    suspend fun watchDeployments(watcher: K8sWatcher<DeploymentInfo>): Unit

    // Pod operations
    suspend fun getPod(
        namespace: String,
        name: String,
    ): PodInfo?

    suspend fun listPodsByLabels(
        namespace: String,
        labels: Map<String, String>,
    ): List<PodInfo>

    // Secret operations
    suspend fun getSecret(
        namespace: String,
        name: String,
    ): SecretInfo?

    // Configuration
    suspend fun getConfiguration(): K8sClientConfig
}
