package com.watchcluster.util

import com.cronutils.model.time.ExecutionTime
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.ZonedDateTime

/**
 * Suspends the caller until the next execution time of a cron expression.
 *
 * Extracted as an interface so that callers owning their own scheduling loop
 * (e.g. per-deployment workers) can be driven by a manual ticker in tests.
 */
interface CronTicker {
    /**
     * Suspends until the next execution time of [cronExpression].
     *
     * @throws IllegalArgumentException if the expression cannot be parsed or
     *   yields no next execution time.
     */
    suspend fun awaitNextExecution(cronExpression: String)
}

class CronUtilsTicker internal constructor(
    private val nowProvider: () -> ZonedDateTime,
    private val sleeper: suspend (Long) -> Unit,
) : CronTicker {
    constructor() : this(
        nowProvider = ZonedDateTime::now,
        sleeper = { millis -> delay(millis) },
    )

    override suspend fun awaitNextExecution(cronExpression: String) {
        val cron =
            runCatching { CronExpressions.parse(cronExpression) }
                .getOrElse { e ->
                    throw IllegalArgumentException("Invalid cron expression: $cronExpression", e)
                }

        val now = nowProvider()
        val nextExecution =
            ExecutionTime
                .forCron(cron)
                .nextExecution(now)
                .orElseThrow {
                    IllegalArgumentException("No next execution time for cron expression: $cronExpression")
                }

        waitUntil(nextExecution)
    }

    private suspend fun waitUntil(target: ZonedDateTime) {
        while (true) {
            val now = nowProvider()
            if (!now.isBefore(target)) return

            val remaining = Duration.between(now, target)
            sleeper(remaining.toCeilingMillis())
        }
    }
}

private fun Duration.toCeilingMillis(): Long {
    val truncatedMillis = toMillis()
    return if (minusMillis(truncatedMillis).isZero) truncatedMillis else truncatedMillis + 1
}
