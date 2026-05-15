package com.watchcluster.client.domain

object K8sConditionStatus {
    const val TRUE = "True"
}

object DeploymentConditionType {
    const val PROGRESSING = "Progressing"
    const val AVAILABLE = "Available"
}

object DeploymentConditionReason {
    const val NEW_REPLICA_SET_AVAILABLE = "NewReplicaSetAvailable"
}

object PodConditionType {
    const val READY = "Ready"
}
