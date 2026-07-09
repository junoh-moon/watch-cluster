package com.watchcluster.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.watchcluster.client.K8sClient
import com.watchcluster.model.DeploymentEventData
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.model.WatchedDeployment
import com.watchcluster.model.WebhookEvent
import com.watchcluster.model.WebhookEventType
import com.watchcluster.service.DeploymentUpdater
import com.watchcluster.service.ImageChecker
import com.watchcluster.service.WebhookService
import com.watchcluster.util.CronTicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}
private val objectMapper = ObjectMapper()

internal enum class DeploymentCheckStatus {
    UPDATED,
    NO_UPDATE,
    FAILED,
    SKIPPED,
}

internal data class DeploymentCheckResult(
    val status: DeploymentCheckStatus,
    val message: String,
)

internal sealed interface WorkerCommand {
    data class SpecChanged(
        val spec: WatchedDeployment,
    ) : WorkerCommand

    data object ManualCheck : WorkerCommand

    data object CronTick : WorkerCommand
}

/**
 * Owns all state and work for a single watched deployment.
 *
 * A single consumer coroutine processes commands from the mailbox, so every
 * operation on one deployment key is serialized structurally — no per-key
 * locking is needed, and the cron loop is a child of the worker so it cannot
 * outlive or drift from the deployment it belongs to.
 *
 * Lifecycle: [stop] requests a graceful stop — a check already in progress
 * runs to completion (so an ongoing rollout keeps its monitoring and
 * webhooks), queued commands are discarded, then the worker terminates and
 * deregisters itself via [onTerminated]. A worker created while its
 * predecessor is still draining waits for the predecessor to terminate before
 * processing anything, preserving the one-check-per-deployment invariant
 * across disable/re-enable races.
 */
internal class DeploymentWorker(
    private val key: String,
    initialSpec: WatchedDeployment,
    parentScope: CoroutineScope,
    private val cronTicker: CronTicker,
    private val imageChecker: ImageChecker,
    private val deploymentUpdater: DeploymentUpdater,
    private val webhookService: WebhookService,
    private val k8sClient: K8sClient,
    private val predecessor: DeploymentWorker? = null,
    private val onTerminated: (DeploymentWorker) -> Unit = {},
) {
    private val mailbox = Channel<WorkerCommand>(Channel.UNLIMITED)
    private val stopRequested = AtomicBoolean(false)

    /**
     * True from the moment a manual check is accepted until it finishes, so
     * redelivered check-now events are absorbed for the whole duration —
     * including while the check is running, not just while it is queued.
     */
    private val manualCheckPending = AtomicBoolean(false)

    @Volatile
    internal var spec: WatchedDeployment = initialSpec
        private set

    private val job: Job =
        parentScope.launch(start = CoroutineStart.LAZY) {
            try {
                predecessor?.awaitTermination()
                run()
            } finally {
                onTerminated(this@DeploymentWorker)
            }
        }

    val isStopRequested: Boolean
        get() = stopRequested.get()

    fun start() {
        job.start()
    }

    /**
     * Queues [command] for processing. Returns false if the worker has been
     * stopped, in which case the caller should obtain a fresh worker and
     * retry. A [WorkerCommand.ManualCheck] that duplicates one already
     * pending or running is absorbed (returns true).
     */
    fun send(command: WorkerCommand): Boolean {
        if (command is WorkerCommand.ManualCheck && !manualCheckPending.compareAndSet(false, true)) {
            logger.debug { "Manual check already in progress for $key" }
            return true
        }

        if (!mailbox.trySend(command).isSuccess) return false

        // stop() may have discarded the queue between the stopRequested flag
        // and the channel close; a delivery racing that window must not be
        // trusted, or the command silently vanishes until the next reconcile.
        return !stopRequested.get()
    }

    fun stop() {
        if (stopRequested.compareAndSet(false, true)) {
            mailbox.close()
            logger.info { "Stopped watching deployment: $key" }
        }
    }

    suspend fun awaitTermination() {
        job.join()
    }

    /**
     * A drained mailbox batch: only the latest spec matters, and pending
     * ticks/manual requests collapse into a single check.
     */
    private class Batch {
        var latestSpec: WatchedDeployment? = null
        var manualCheckRequested = false
        var checkDue = false
    }

    private fun drainQueued(first: WorkerCommand? = null): Batch {
        val batch = Batch()
        var current = first ?: mailbox.tryReceive().getOrNull()
        while (current != null) {
            when (current) {
                is WorkerCommand.SpecChanged -> batch.latestSpec = current.spec
                WorkerCommand.ManualCheck -> batch.manualCheckRequested = true
                WorkerCommand.CronTick -> batch.checkDue = true
            }
            current = mailbox.tryReceive().getOrNull()
        }
        return batch
    }

    private suspend fun run() =
        coroutineScope {
            if (stopRequested.get()) return@coroutineScope

            // Fold commands queued while waiting on the predecessor into the
            // initial state, so the first announcement reflects the latest
            // spec instead of a stale one.
            val initial = drainQueued()
            initial.latestSpec?.let { spec = it }
            announceSpec()
            var tickerJob = launchTicker(spec.cronExpression)

            try {
                if (initial.manualCheckRequested) runManualCheck()

                for (command in mailbox) {
                    if (stopRequested.get()) break

                    val batch = drainQueued(command)

                    batch.latestSpec?.let { newSpec ->
                        if (newSpec != spec) {
                            val cronChanged = newSpec.cronExpression != spec.cronExpression
                            spec = newSpec
                            announceSpec()
                            if (cronChanged) {
                                tickerJob.cancel()
                                tickerJob = launchTicker(newSpec.cronExpression)
                            }
                        } else {
                            logger.debug { "Ignoring unchanged deployment: $key" }
                        }
                    }

                    when {
                        // A manual check subsumes a concurrently due tick.
                        batch.manualCheckRequested -> runManualCheck()
                        batch.checkDue -> checkAndUpdate()
                    }
                }
            } finally {
                tickerJob.cancel()
            }
        }

    private suspend fun runManualCheck() {
        try {
            performManualCheck()
        } finally {
            manualCheckPending.set(false)
        }
    }

    private fun CoroutineScope.launchTicker(cronExpression: String): Job =
        launch {
            while (true) {
                runCatching { cronTicker.awaitNextExecution(cronExpression) }
                    .onFailure { e ->
                        if (e is CancellationException) throw e
                        logger.error(e) { "Disabling periodic checks for $key: ${e.message}" }
                        return@launch
                    }
                mailbox.trySend(WorkerCommand.CronTick)
            }
        }

    private suspend fun announceSpec() {
        webhookService.sendWebhook(
            WebhookEvent(
                eventType = WebhookEventType.DEPLOYMENT_DETECTED,
                timestamp =
                    java.time.Instant
                        .now()
                        .toString(),
                deployment = DeploymentEventData(spec.namespace, spec.name, spec.currentImage),
                details =
                    mapOf(
                        "cronExpression" to spec.cronExpression,
                        "updateStrategy" to spec.updateStrategy.displayName,
                    ),
            ),
        )

        logger.info { "Watching deployment: $key with cron: ${spec.cronExpression} and strategy: ${spec.updateStrategy}" }
    }

    internal suspend fun checkAndUpdate(): DeploymentCheckResult {
        val deployment = spec

        return runCatching {
            logger.info { "Current image: ${deployment.currentImage}" }
            logger.info { "Checking for updates: ${deployment.namespace}/${deployment.name}" }

            val updateResult =
                imageChecker.checkForUpdate(
                    deployment.currentImage,
                    deployment.updateStrategy,
                    deployment.namespace,
                    deployment.imagePullSecrets,
                    deployment.name,
                )

            when {
                // Has update
                updateResult.newImage != null -> {
                    logger.info {
                        buildString {
                            append("Found update for ${deployment.namespace}/${deployment.name}: ${updateResult.newImage}")
                            updateResult.reason?.let { append(" $it") }
                        }
                    }
                    deploymentUpdater.updateDeployment(
                        deployment.namespace,
                        deployment.name,
                        updateResult.newImage,
                        updateResult.currentImage,
                        deployment.updateStrategy,
                        updateResult.newDigest,
                    )

                    // Update the owned spec so the next check sees the new image.
                    spec = spec.copy(currentImage = updateResult.newImage)

                    DeploymentCheckResult(
                        DeploymentCheckStatus.UPDATED,
                        "Updated ${deployment.namespace}/${deployment.name} to ${updateResult.newImage}",
                    )
                }

                else -> {
                    val message =
                        updateResult.reason
                            ?: "No update available for ${deployment.namespace}/${deployment.name}"
                    logger.debug {
                        buildString {
                            append("No update available for ${deployment.namespace}/${deployment.name}.")
                            updateResult.reason?.let { append(" $it") }
                        }
                    }
                    DeploymentCheckResult(DeploymentCheckStatus.NO_UPDATE, message)
                }
            }
        }.getOrElse { e ->
            val message = "Error checking deployment ${deployment.namespace}/${deployment.name}: ${e.message ?: "unknown error"}"
            logger.error(e) { "Error checking deployment ${deployment.namespace}/${deployment.name}" }
            DeploymentCheckResult(DeploymentCheckStatus.FAILED, message)
        }
    }

    private suspend fun performManualCheck() {
        val namespace = spec.namespace
        val name = spec.name

        val removed = removeCheckNowAnnotation(namespace, name)
        if (!removed) {
            recordDeploymentEvent(
                namespace = namespace,
                deploymentName = name,
                reason = "ManualCheckFailed",
                message = "Manual check for $key was not run because ${WatchClusterAnnotations.CHECK_NOW} could not be removed",
                type = "Warning",
            )
            return
        }

        recordDeploymentEvent(
            namespace = namespace,
            deploymentName = name,
            reason = "ManualCheckRequested",
            message = "Manual check requested for $key by ${WatchClusterAnnotations.CHECK_NOW}",
            type = "Normal",
        )

        val result = checkAndUpdate()
        recordManualCheckResult(namespace, name, result)
    }

    private suspend fun removeCheckNowAnnotation(
        namespace: String,
        name: String,
    ): Boolean {
        val annotationPatch: Map<String, String?> = mapOf(WatchClusterAnnotations.CHECK_NOW to null)
        val patchJson =
            objectMapper.writeValueAsString(
                mapOf(
                    "metadata" to
                        mapOf(
                            "annotations" to annotationPatch,
                        ),
                ),
            )

        return k8sClient.patchDeployment(namespace, name, patchJson) != null
    }

    private suspend fun recordManualCheckResult(
        namespace: String,
        name: String,
        result: DeploymentCheckResult,
    ) {
        val (reason, type) =
            when (result.status) {
                DeploymentCheckStatus.UPDATED -> "ManualCheckUpdated" to "Normal"
                DeploymentCheckStatus.NO_UPDATE -> "ManualCheckNoUpdate" to "Normal"
                DeploymentCheckStatus.FAILED -> "ManualCheckFailed" to "Warning"
                DeploymentCheckStatus.SKIPPED -> "ManualCheckSkipped" to "Warning"
            }

        recordDeploymentEvent(
            namespace = namespace,
            deploymentName = name,
            reason = reason,
            message = result.message,
            type = type,
        )
    }

    private suspend fun recordDeploymentEvent(
        namespace: String,
        deploymentName: String,
        reason: String,
        message: String,
        type: String,
    ) {
        runCatching {
            k8sClient.recordDeploymentEvent(namespace, deploymentName, reason, message, type)
        }.onFailure { e ->
            logger.warn(e) { "Failed to audit event $reason for deployment $namespace/$deploymentName" }
        }
    }
}
