package com.watchcluster.client

import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.K8sClientConfig
import com.watchcluster.client.domain.PodInfo
import com.watchcluster.client.domain.SecretInfo
import com.watchcluster.model.ImagePlatform

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

    suspend fun recordDeploymentEvent(
        namespace: String,
        deploymentName: String,
        reason: String,
        message: String,
        type: String = "Normal",
    ) {
    }

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

    // Node operations
    suspend fun getNodePlatform(nodeName: String): ImagePlatform? = null

    // Configuration
    suspend fun getConfiguration(): K8sClientConfig
}
