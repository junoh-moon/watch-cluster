package com.watchcluster.api

import com.watchcluster.client.K8sClient
import com.watchcluster.client.domain.DeploymentInfo
import com.watchcluster.client.domain.replicasConverged
import com.watchcluster.client.patchAnnotations
import com.watchcluster.controller.CheckRecord
import com.watchcluster.controller.WatchController
import com.watchcluster.controller.WorkerStatus
import com.watchcluster.model.UpdateStrategy
import com.watchcluster.model.WatchClusterAnnotations
import com.watchcluster.model.isWatchEnabled
import com.watchcluster.model.watchCronExpression
import com.watchcluster.model.watchStrategy
import com.watchcluster.util.CronExpressions
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Read and write operations behind the admin UI.
 *
 * Writes go through Deployment annotations rather than through the
 * controller: the annotations are the source of truth, and reusing them keeps
 * the UI, `kubectl annotate`, and the watch/reconcile path on a single code
 * path. Reads join those annotations with the controller's in-memory worker
 * state, which is the only place schedule and check history live.
 */
class AdminService(
    private val k8sClient: K8sClient,
    private val controller: WatchController,
) {
    sealed interface Outcome {
        data class Ok(
            val message: String,
        ) : Outcome

        data object NotFound : Outcome

        data class Invalid(
            val message: String,
        ) : Outcome

        data class Failed(
            val message: String,
        ) : Outcome
    }

    suspend fun listApps(): List<AppDto> {
        val statuses = controller.watchedStatuses()
        return k8sClient
            .listDeployments()
            .filter { it.isWatchEnabled && it.containers.isNotEmpty() }
            .map { toAppDto(it, statuses["${it.namespace}/${it.name}"]) }
            .sortedWith(compareBy({ it.namespace }, { it.name }))
    }

    /** Deployments eligible to be watched but not yet annotated. */
    suspend fun listCandidates(): List<CandidateDto> =
        k8sClient
            .listDeployments()
            .filter { !it.isWatchEnabled && it.containers.isNotEmpty() }
            .map {
                CandidateDto(
                    namespace = it.namespace,
                    name = it.name,
                    image = it.containers[0].image,
                    containerCount = it.containers.size,
                )
            }.sortedWith(compareBy({ it.namespace }, { it.name }))

    suspend fun detail(
        namespace: String,
        name: String,
    ): AppDetailDto? {
        val deployment = k8sClient.getDeployment(namespace, name) ?: return null
        if (deployment.containers.isEmpty()) return null

        val status = controller.statusFor(namespace, name)
        // Unreadable events must not sink the rest of the page, but the UI is
        // told so it can say "unreadable" rather than "none".
        val events =
            runCatching { k8sClient.listDeploymentEvents(namespace, name) }
                .onFailure { e -> logger.warn(e) { "Failed to list events for $namespace/$name" } }

        return AppDetailDto(
            app = toAppDto(deployment, status),
            history = status?.recentChecks.orEmpty().map { it.toDto() },
            events =
                events.getOrDefault(emptyList()).map {
                    EventDto(
                        type = it.type,
                        reason = it.reason,
                        message = it.message,
                        count = it.count,
                        lastTimestamp = it.lastTimestamp,
                    )
                },
            eventsUnavailable = events.isFailure,
        )
    }

    /**
     * Requests one immediate check by setting the `check-now` annotation —
     * the same trigger `kubectl annotate` uses. The controller consumes and
     * removes the annotation asynchronously, so this returns as soon as the
     * request is recorded, not when the check finishes.
     */
    suspend fun requestCheck(
        namespace: String,
        name: String,
    ): Outcome {
        val deployment = k8sClient.getDeployment(namespace, name) ?: return Outcome.NotFound
        if (!deployment.isWatchEnabled) {
            // An unwatched deployment has no worker to consume the
            // annotation, so it would linger forever.
            return Outcome.Invalid("Deployment $namespace/$name is not watched; enable it first")
        }

        return patch(
            namespace,
            name,
            mapOf(WatchClusterAnnotations.CHECK_NOW to "true"),
            "Check requested for $namespace/$name",
        )
    }

    /**
     * Starts watching a deployment, or updates the schedule/strategy of one
     * already watched. Omitted fields keep their current annotation value.
     */
    suspend fun applyWatch(
        namespace: String,
        name: String,
        request: WatchRequest,
    ): Outcome {
        val deployment = k8sClient.getDeployment(namespace, name) ?: return Outcome.NotFound
        if (deployment.containers.isEmpty()) {
            return Outcome.Invalid("Deployment $namespace/$name has no containers")
        }

        val requestedCron = request.cron?.trim()
        if (requestedCron != null && requestedCron.isEmpty()) {
            return Outcome.Invalid("cron must not be blank")
        }
        val cron = requestedCron ?: deployment.watchCronExpression
        if (!CronExpressions.isValid(cron)) {
            return Outcome.Invalid("Invalid or unschedulable cron expression: $cron")
        }

        val requestedStrategy = request.strategy?.trim()
        if (requestedStrategy != null && requestedStrategy.isEmpty()) {
            return Outcome.Invalid("strategy must not be blank")
        }
        val strategy =
            if (requestedStrategy == null) {
                deployment.watchStrategy
            } else {
                // Persist the canonical name, so a synonym like "semver" is
                // stored as the strategy the controller will report back.
                UpdateStrategy.parseOrNull(requestedStrategy)
                    ?: return Outcome.Invalid("Unknown strategy: $requestedStrategy")
            }

        // Only fields the caller actually supplied are written, so a request
        // that changes the strategy cannot clobber a cron someone changed
        // concurrently with kubectl.
        val annotations =
            buildMap<String, String?> {
                put(WatchClusterAnnotations.ENABLED, "true")
                if (requestedCron != null) put(WatchClusterAnnotations.CRON, cron)
                if (requestedStrategy != null) put(WatchClusterAnnotations.STRATEGY, strategy.displayName)
            }

        return patch(
            namespace,
            name,
            annotations,
            "Watching $namespace/$name with cron '$cron' and strategy '${strategy.displayName}'",
        )
    }

    /**
     * Stops watching by flipping `enabled` to false. The cron and strategy
     * annotations are left in place so re-enabling restores the previous
     * settings.
     */
    suspend fun unwatch(
        namespace: String,
        name: String,
    ): Outcome {
        k8sClient.getDeployment(namespace, name) ?: return Outcome.NotFound

        return patch(
            namespace,
            name,
            mapOf(WatchClusterAnnotations.ENABLED to "false"),
            "Stopped watching $namespace/$name",
        )
    }

    private suspend fun patch(
        namespace: String,
        name: String,
        annotations: Map<String, String?>,
        successMessage: String,
    ): Outcome =
        if (k8sClient.patchAnnotations(namespace, name, annotations)) {
            Outcome.Ok(successMessage)
        } else {
            Outcome.Failed("Failed to annotate $namespace/$name")
        }

    private fun toAppDto(
        deployment: DeploymentInfo,
        status: WorkerStatus?,
    ): AppDto {
        val annotations = deployment.annotations
        val cron = deployment.watchCronExpression
        val container = deployment.containers[0]

        return AppDto(
            namespace = deployment.namespace,
            name = deployment.name,
            image = container.image,
            strategy = deployment.watchStrategy.displayName,
            cron = cron,
            cronValid = CronExpressions.isValid(cron),
            // Taken from the running worker's spec, not the annotation: between
            // a write and the watch event reaching the worker, the annotation
            // names a schedule nothing is ticking on yet.
            nextCheckAt =
                status
                    ?.let { CronExpressions.nextExecution(it.spec.cronExpression) }
                    ?.toInstant()
                    ?.toString(),
            lastResult = status?.recentChecks?.firstOrNull()?.toDto(),
            lastUpdate = annotations[WatchClusterAnnotations.LAST_UPDATE],
            change = annotations[WatchClusterAnnotations.CHANGE],
            checkInProgress = status?.checkInProgress ?: false,
            checkNowPending = annotations.containsKey(WatchClusterAnnotations.CHECK_NOW),
            watched = status != null,
            rollout =
                RolloutDto(
                    replicas = deployment.replicas,
                    ready = deployment.status.readyReplicas,
                    // Replica counts still describe the previous generation
                    // until Kubernetes observes the new one, so they alone can
                    // read as "converged" moments after a spec change.
                    inProgress =
                        deployment.status.observedGeneration != deployment.generation ||
                            !deployment.status.replicasConverged(deployment.replicas),
                ),
        )
    }
}

private fun CheckRecord.toDto(): CheckResultDto =
    CheckResultDto(
        at = at.toString(),
        trigger = trigger.name,
        status = status.name,
        message = message,
        image = image,
    )
