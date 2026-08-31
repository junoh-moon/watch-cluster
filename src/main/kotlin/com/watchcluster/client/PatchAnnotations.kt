package com.watchcluster.client

import com.fasterxml.jackson.databind.ObjectMapper

private val objectMapper = ObjectMapper()

/**
 * Merge-patches `metadata.annotations` on a Deployment. A null value removes
 * the annotation.
 *
 * Returns false when the patch did not apply, which every caller treats as a
 * failed write rather than a no-op.
 */
suspend fun K8sClient.patchAnnotations(
    namespace: String,
    name: String,
    annotations: Map<String, String?>,
): Boolean {
    val patchJson = objectMapper.writeValueAsString(mapOf("metadata" to mapOf("annotations" to annotations)))
    return patchDeployment(namespace, name, patchJson) != null
}
