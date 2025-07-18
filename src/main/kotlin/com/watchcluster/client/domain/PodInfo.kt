package com.watchcluster.client.domain

data class PodInfo(
    val namespace: String,
    val name: String,
    val containers: List<ContainerInfo>,
    val status: PodStatus,
)

data class PodStatus(
    val phase: String? = null,
    val conditions: List<PodCondition> = emptyList(),
    val containerStatuses: List<ContainerStatus> = emptyList(),
)

data class PodCondition(
    val type: String,
    val status: String,
    val lastTransitionTime: String? = null,
    val reason: String? = null,
    val message: String? = null,
)

data class ContainerStatus(
    val name: String,
    val image: String,
    val imageID: String? = null,
    val ready: Boolean = false,
    val restartCount: Int = 0,
)
