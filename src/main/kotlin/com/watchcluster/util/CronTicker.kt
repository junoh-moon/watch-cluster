package com.watchcluster.util

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
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

private val whitespace = Regex("\\s+")

class CronUtilsTicker internal constructor(
    private val nowProvider: () -> ZonedDateTime,
    private val sleeper: suspend (Long) -> Unit,
) : CronTicker {
    constructor() : this(
        nowProvider = ZonedDateTime::now,
        sleeper = { millis -> delay(millis) },
    )

    private val unixCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private val quartzCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))

    override suspend fun awaitNextExecution(cronExpression: String) {
        val cron =
            runCatching { parseCron(cronExpression) }
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

    /**
     * Parses 5-field Unix cron as the primary format; 6/7-field Quartz cron
     * remains supported for backward compatibility.
     */
    internal fun parseCron(cronExpression: String): Cron =
        cronExpression.trim().let { expression ->
            parserFor(expression).parse(expression)
        }

    private fun parserFor(cronExpression: String): CronParser {
        val fieldCount = cronExpression.split(whitespace).size
        return when (fieldCount) {
            5 -> unixCronParser
            6, 7 -> quartzCronParser
            else -> throw IllegalArgumentException(
                "Unsupported cron expression. Use 5-field Unix cron or 6/7-field Quartz cron.",
            )
        }
    }
}

private fun Duration.toCeilingMillis(): Long {
    val truncatedMillis = toMillis()
    return if (minusMillis(truncatedMillis).isZero) truncatedMillis else truncatedMillis + 1
}
