package com.watchcluster.client.domain

data class DeploymentInfo(
    val namespace: String,
    val name: String,
    val generation: Long,
    val replicas: Int,
    val selector: Map<String, String>,
    val containers: List<ContainerInfo>,
    val imagePullSecrets: List<String>,
    val annotations: Map<String, String>,
    val status: DeploymentStatus,
)

data class DeploymentStatus(
    val observedGeneration: Long? = null,
    val updatedReplicas: Int? = null,
    val readyReplicas: Int? = null,
    val availableReplicas: Int? = null,
    val conditions: List<DeploymentCondition> = emptyList(),
)

data class DeploymentCondition(
    val type: String,
    val status: String,
    val lastUpdateTime: String? = null,
    val reason: String? = null,
    val message: String? = null,
)
