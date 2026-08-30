package com.watchcluster.model

import com.watchcluster.client.domain.DeploymentInfo

/**
 * How watch-cluster reads its annotations off a Deployment.
 *
 * The controller and the admin API both interpret the same annotations, so
 * the defaults and the enabled/check-now rules live here instead of being
 * restated at every reader — a drift between the two would show the UI
 * reporting a schedule the controller is not actually running.
 */
val DeploymentInfo.isWatchEnabled: Boolean
    get() = annotations[WatchClusterAnnotations.ENABLED]?.toBoolean() ?: false

val DeploymentInfo.isCheckNowRequested: Boolean
    get() = annotations.containsKey(WatchClusterAnnotations.CHECK_NOW)

val DeploymentInfo.watchCronExpression: String
    get() = annotations[WatchClusterAnnotations.CRON] ?: WatchClusterAnnotations.DEFAULT_CRON

val DeploymentInfo.watchStrategy: UpdateStrategy
    get() =
        UpdateStrategy.fromString(
            annotations[WatchClusterAnnotations.STRATEGY] ?: WatchClusterAnnotations.DEFAULT_STRATEGY,
        )
