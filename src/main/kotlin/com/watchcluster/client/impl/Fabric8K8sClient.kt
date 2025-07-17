package com.watchcluster.client.impl

import com.watchcluster.client.K8sClient
import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.*
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.api.model.Secret
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.Watcher
import io.fabric8.kubernetes.client.WatcherException
import mu.KotlinLogging
import java.util.Base64

private val logger = KotlinLogging.logger {}

class Fabric8K8sClient(
    private val kubernetesClient: KubernetesClient
) : K8sClient {
    
    override fun getDeployment(namespace: String, name: String): DeploymentInfo? {
        return try {
            val deployment = kubernetesClient.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(name)
                .get()
            
            deployment?.let { mapToDeploymentInfo(it) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get deployment $namespace/$name" }
            null
        }
    }
    
    override fun patchDeployment(namespace: String, name: String, patchJson: String): DeploymentInfo? {
        return try {
            val deployment = kubernetesClient.apps()
                .deployments()
                .inNamespace(namespace)
                .withName(name)
                .patch(patchJson)
            
            deployment?.let { mapToDeploymentInfo(it) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to patch deployment $namespace/$name" }
            null
        }
    }
    
    override fun watchDeployments(watcher: K8sWatcher<DeploymentInfo>): AutoCloseable {
        val fabric8Watcher = object : Watcher<Deployment> {
            override fun eventReceived(action: Watcher.Action, resource: Deployment) {
                val eventType = when (action) {
                    Watcher.Action.ADDED -> EventType.ADDED
                    Watcher.Action.MODIFIED -> EventType.MODIFIED
                    Watcher.Action.DELETED -> EventType.DELETED
                    Watcher.Action.ERROR -> EventType.ERROR
                    Watcher.Action.BOOKMARK -> return // Ignore bookmark events
                }
                
                val deploymentInfo = mapToDeploymentInfo(resource)
                watcher.eventReceived(K8sWatchEvent(eventType, deploymentInfo))
            }
            
            override fun onClose(cause: WatcherException?) {
                watcher.onClose(cause)
            }
        }
        
        val watch = kubernetesClient.apps()
            .deployments()
            .inAnyNamespace()
            .watch(fabric8Watcher)
        
        return AutoCloseable {
            watch.close()
        }
    }
    
    override fun getPod(namespace: String, name: String): PodInfo? {
        return try {
            val pod = kubernetesClient.pods()
                .inNamespace(namespace)
                .withName(name)
                .get()
            
            pod?.let { mapToPodInfo(it) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pod $namespace/$name" }
            null
        }
    }
    
    override fun listPodsByLabels(namespace: String, labels: Map<String, String>): List<PodInfo> {
        return try {
            val pods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabels(labels)
                .list()
                .items
            
            pods.map { mapToPodInfo(it) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to list pods in namespace $namespace with labels $labels" }
            emptyList()
        }
    }
    
    override fun getSecret(namespace: String, name: String): SecretInfo? {
        return try {
            val secret = kubernetesClient.secrets()
                .inNamespace(namespace)
                .withName(name)
                .get()
            
            secret?.let { mapToSecretInfo(it) }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get secret $namespace/$name" }
            null
        }
    }
    
    override fun getConfiguration(): K8sClientConfig {
        return K8sClientConfig(
            masterUrl = kubernetesClient.configuration.masterUrl
        )
    }
    
    private fun mapToDeploymentInfo(deployment: Deployment): DeploymentInfo {
        val metadata = deployment.metadata
        val spec = deployment.spec
        val status = deployment.status
        
        return DeploymentInfo(
            namespace = metadata.namespace ?: "",
            name = metadata.name ?: "",
            generation = metadata.generation ?: 0,
            replicas = spec.replicas ?: 1,
            selector = spec.selector?.matchLabels ?: emptyMap(),
            containers = spec.template?.spec?.containers?.map { container ->
                ContainerInfo(
                    name = container.name,
                    image = container.image ?: "",
                    imageID = null
                )
            } ?: emptyList(),
            imagePullSecrets = spec.template?.spec?.imagePullSecrets?.map { it.name } ?: emptyList(),
            annotations = metadata.annotations ?: emptyMap(),
            status = mapToDeploymentStatus(status)
        )
    }
    
    private fun mapToDeploymentStatus(status: io.fabric8.kubernetes.api.model.apps.DeploymentStatus?): DeploymentStatus {
        if (status == null) return DeploymentStatus()
        
        return DeploymentStatus(
            observedGeneration = status.observedGeneration,
            updatedReplicas = status.updatedReplicas,
            readyReplicas = status.readyReplicas,
            availableReplicas = status.availableReplicas,
            conditions = status.conditions?.map { condition ->
                DeploymentCondition(
                    type = condition.type,
                    status = condition.status,
                    lastUpdateTime = condition.lastUpdateTime,
                    reason = condition.reason,
                    message = condition.message
                )
            } ?: emptyList()
        )
    }
    
    private fun mapToPodInfo(pod: Pod): PodInfo {
        val metadata = pod.metadata
        val spec = pod.spec
        val status = pod.status
        
        return PodInfo(
            namespace = metadata.namespace ?: "",
            name = metadata.name ?: "",
            containers = spec?.containers?.map { container ->
                ContainerInfo(
                    name = container.name,
                    image = container.image ?: "",
                    imageID = null
                )
            } ?: emptyList(),
            status = mapToPodStatus(status)
        )
    }
    
    private fun mapToPodStatus(status: io.fabric8.kubernetes.api.model.PodStatus?): PodStatus {
        if (status == null) return PodStatus()
        
        return PodStatus(
            phase = status.phase,
            conditions = status.conditions?.map { condition ->
                PodCondition(
                    type = condition.type,
                    status = condition.status,
                    lastTransitionTime = condition.lastTransitionTime,
                    reason = condition.reason,
                    message = condition.message
                )
            } ?: emptyList(),
            containerStatuses = status.containerStatuses?.map { containerStatus ->
                ContainerStatus(
                    name = containerStatus.name,
                    image = containerStatus.image ?: "",
                    imageID = containerStatus.imageID,
                    ready = containerStatus.ready ?: false,
                    restartCount = containerStatus.restartCount ?: 0
                )
            } ?: emptyList()
        )
    }
    
    private fun mapToSecretInfo(secret: Secret): SecretInfo {
        val metadata = secret.metadata
        
        // Decode base64 data
        val decodedData = secret.data?.mapValues { (_, value) ->
            try {
                String(Base64.getDecoder().decode(value))
            } catch (e: Exception) {
                logger.warn { "Failed to decode secret data: ${e.message}" }
                value // Return original if decoding fails
            }
        } ?: emptyMap()
        
        return SecretInfo(
            namespace = metadata.namespace ?: "",
            name = metadata.name ?: "",
            type = secret.type ?: "",
            data = decodedData
        )
    }
}