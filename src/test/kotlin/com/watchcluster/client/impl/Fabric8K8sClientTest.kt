package com.watchcluster.client.impl

import io.fabric8.kubernetes.client.KubernetesClient
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class Fabric8K8sClientTest {
    @Test
    fun `close releases the underlying Kubernetes client`() {
        val kubernetesClient = mockk<KubernetesClient>(relaxed = true)
        val client = Fabric8K8sClient(kubernetesClient)

        client.close()
        client.close()

        verify(exactly = 1) { kubernetesClient.close() }
    }
}
