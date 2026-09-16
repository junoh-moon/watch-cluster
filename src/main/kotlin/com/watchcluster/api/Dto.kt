package com.watchcluster.api

/**
 * Wire models for the admin API.
 *
 * Timestamps are ISO-8601 strings rather than temporal types so that the
 * browser can render them without the server picking a format, and every
 * runtime-only field is nullable — the controller may have just restarted and
 * know nothing about a deployment it is otherwise watching.
 */
data class RolloutDto(
    val replicas: Int,
    val ready: Int?,
    /** True while Kubernetes has not yet converged on the desired replica set. */
    val inProgress: Boolean,
)

data class CheckResultDto(
    val at: String,
    val trigger: String,
    val status: String,
    val message: String,
    val image: String,
)

data class AppDto(
    val namespace: String,
    val name: String,
    val image: String,
    val strategy: String,
    val cron: String,
    val cronValid: Boolean,
    /** Null when the cron expression is invalid or the worker is not running. */
    val nextCheckAt: String?,
    val lastResult: CheckResultDto?,
    /** From the `watch-cluster.io/last-update` annotation; survives restarts. */
    val lastUpdate: String?,
    /** From the `watch-cluster.io/change` annotation. */
    val change: String?,
    val checkInProgress: Boolean,
    /** A `check-now` annotation is set but the controller has not consumed it yet. */
    val checkNowPending: Boolean,
    /** False when the annotation says enabled but no worker is running yet. */
    val watched: Boolean,
    val rollout: RolloutDto,
    /** Raw annotation; invalid values are ignored with a warning by the worker. */
    val minimumReleaseAge: String? = null,
)

data class CandidateDto(
    val namespace: String,
    val name: String,
    val image: String,
    val containerCount: Int,
)

data class EventDto(
    val type: String,
    val reason: String,
    val message: String,
    val count: Int,
    val lastTimestamp: String?,
)

data class AppDetailDto(
    val app: AppDto,
    /** In-memory check history; empty after a controller restart. */
    val history: List<CheckResultDto>,
    val events: List<EventDto>,
    /**
     * True when Events could not be read at all — usually the `list` verb
     * missing from RBAC after an upgrade. Distinguishes that from a
     * deployment that genuinely has no events.
     */
    val eventsUnavailable: Boolean,
)

/**
 * Body of `PUT /api/apps/{ns}/{name}/watch`. Both fields are optional so the
 * form can update one setting without restating the other; omitted fields
 * keep their current annotation value, or fall back to the defaults when the
 * deployment is not watched yet.
 */
data class WatchRequest(
    val cron: String? = null,
    val strategy: String? = null,
)

data class MessageDto(
    val message: String,
)

data class ErrorDto(
    val error: String,
)
