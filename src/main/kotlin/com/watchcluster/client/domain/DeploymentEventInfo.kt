package com.watchcluster.client.domain

/**
 * A Kubernetes Event recorded against a Deployment.
 *
 * watch-cluster writes its manual-check audit trail as Events, so reading
 * them back is how a caller reconstructs history that outlives the
 * controller process.
 */
data class DeploymentEventInfo(
    val type: String,
    val reason: String,
    val message: String,
    val count: Int,
    val lastTimestamp: String? = null,
)
