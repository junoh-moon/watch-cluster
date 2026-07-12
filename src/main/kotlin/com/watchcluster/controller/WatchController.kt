package com.watchcluster.controller

import com.watchcluster.client.K8sClient
import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.EventType
import com.watchcluster.client.domain.K8sWatchEvent
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.model.WatchedDeployment
import com.watchcluster.model.WebhookConfig
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.CronTicker
import com.watchcluster.util.CronUtilsTicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

/**
 * Watches deployments and converges the set of [DeploymentWorker]s toward
 * the cluster state, level-triggered: watch events apply incremental updates
 * and a periodic reconcile re-applies the full snapshot, so any event lost
 * during a watch outage (including DELETED) is recovered within one
 * reconcile interval.
 */
class WatchController(
    private val k8sClient: K8sClient,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    imageChecker: ImageChecker? = null,
    deploymentUpdater: DeploymentUpdater? = null,
    cronTicker: CronTicker? = null,
) {
    private val webhookConfig = WebhookConfig.fromEnvironment()
    private val webhookService = WebhookService(webhookConfig)
    private val imageChecker = imageChecker ?: ImageChecker(k8sClient)
    private val deploymentUpdater = deploymentUpdater ?: DeploymentUpdater(k8sClient, webhookService)
    private val cronTicker = cronTicker ?: CronUtilsTicker()
    internal val workers = ConcurrentHashMap<String, DeploymentWorker>()
    private var reconcileJob: Job? = null
    private val stopRequested = AtomicBoolean(false)

    suspend fun start() {
        logger.info { "Starting deployment watcher..." }

        startWatch()

        // Launched only after the watch is established, so a fatal watch
        // failure propagating out of start() leaves no orphaned reconcile
        // loop behind.
        reconcileJob =
            coroutineScope.launch {
                while (isActive) {
                    delay(RECONCILE_INTERVAL_MS)
                    reconcileDeployments()
                }
            }
    }

    private suspend fun startWatch() {
        k8sClient.watchDeployments(
            object : K8sWatcher<DeploymentInfo> {
                override suspend fun eventReceived(event: K8sWatchEvent<DeploymentInfo>) {
                    val deployment = event.resource
                    when (event.type) {
                        EventType.ADDED, EventType.MODIFIED -> {
                            handleDeployment(deployment)
                        }

                        EventType.DELETED -> {
                            val key = "${deployment.namespace}/${deployment.name}"
                            workers[key]?.stop()
                        }

                        EventType.ERROR -> {
                            logger.warn { "Watch error for deployment ${deployment.namespace}/${deployment.name}" }
                        }
                    }
                }

                override suspend fun onClose(exception: Exception?) {
                    logger.warn { "Deployment watch closed: ${exception?.message}" }
                }
            },
        )
    }

    fun stop() {
        stopRequested.set(true)
        reconcileJob?.cancel()
        workers.values.forEach { it.stop() }
    }

    suspend fun stopAndJoin() {
        stop()
        reconcileJob?.join()

        do {
            val terminatingWorkers = workers.values.toList()
            terminatingWorkers.forEach { it.stop() }
            terminatingWorkers.forEach { it.awaitTermination() }
        } while (workers.isNotEmpty())
    }

    internal suspend fun reconcileDeployments() {
        val deployments =
            runCatching { k8sClient.listDeployments() }
                .getOrElse { e ->
                    // A failed list is not an empty cluster — pruning here
                    // would tear down every watched deployment.
                    logger.error(e) { "Error listing deployments during reconcile; keeping current watch state" }
                    return
                }

        deployments.forEach { deployment ->
            handleDeployment(deployment)
        }

        // Prune workers whose deployment disappeared while watch events were
        // missed — a DELETED event lost during a watch outage is recovered
        // here. A worker created from a watch event racing this snapshot may
        // be stopped spuriously; the next event or reconcile recreates it.
        val liveKeys = deployments.mapTo(mutableSetOf()) { "${it.namespace}/${it.name}" }
        workers.forEach { (key, worker) ->
            if (key !in liveKeys) {
                worker.stop()
            }
        }
    }

    private suspend fun handleDeployment(deployment: DeploymentInfo) {
        if (stopRequested.get()) return

        val annotations = deployment.annotations
        val enabled = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false
        val checkNowRequested = annotations.containsKey(WatchClusterAnnotations.CHECK_NOW)

        val namespace = deployment.namespace
        val name = deployment.name
        val key = "$namespace/$name"

        if (!enabled) {
            workers[key]?.stop()
            return
        }

        val cronExpression = annotations[WatchClusterAnnotations.CRON] ?: WatchClusterAnnotations.DEFAULT_CRON
        val strategyStr = annotations[WatchClusterAnnotations.STRATEGY] ?: "version"
        val strategy = UpdateStrategy.fromString(strategyStr)

        val containers = deployment.containers
        if (containers.isEmpty()) return

        val spec =
            WatchedDeployment(
                namespace = namespace,
                name = name,
                cronExpression = cronExpression,
                updateStrategy = strategy,
                currentImage = containers[0].image,
                imagePullSecrets = deployment.imagePullSecrets,
            )

        while (true) {
            val worker = obtainWorker(key, spec) ?: return
            var delivered = worker.send(WorkerCommand.SpecChanged(spec))
            if (delivered && checkNowRequested) {
                delivered = worker.send(WorkerCommand.ManualCheck)
            }
            if (delivered) return
            // The worker was stopped between lookup and send; retry with a
            // fresh one.
        }
    }

    /**
     * Returns the live worker for [key], replacing a stopped one. The
     * replacement waits for its predecessor to terminate before processing,
     * so at most one check per deployment ever runs even across
     * disable/re-enable races.
     */
    private fun obtainWorker(
        key: String,
        initialSpec: WatchedDeployment,
    ): DeploymentWorker? {
        while (true) {
            if (stopRequested.get()) return null

            val existing = workers[key]
            if (existing != null && !existing.isStopRequested) return existing

            val replacement =
                DeploymentWorker(
                    key = key,
                    initialSpec = initialSpec,
                    parentScope = coroutineScope,
                    cronTicker = cronTicker,
                    imageChecker = imageChecker,
                    deploymentUpdater = deploymentUpdater,
                    webhookService = webhookService,
                    k8sClient = k8sClient,
                    predecessor = existing,
                    onTerminated = { workers.remove(key, it) },
                )

            val installed =
                if (existing == null) {
                    workers.putIfAbsent(key, replacement) == null
                } else {
                    workers.replace(key, existing, replacement)
                }

            if (installed) {
                replacement.start()
                if (stopRequested.get()) {
                    replacement.stop()
                    return null
                }
                return replacement
            }
        }
    }

    private companion object {
        const val RECONCILE_INTERVAL_MS = 60_000L
    }
}
