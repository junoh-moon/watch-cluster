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

class CronUtilsTicker : CronTicker {
    private val unixCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private val quartzCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))

    override suspend fun awaitNextExecution(cronExpression: String) {
        val cron =
            runCatching { parseCron(cronExpression) }
                .getOrElse { e ->
                    throw IllegalArgumentException("Invalid cron expression: $cronExpression", e)
                }

        val now = ZonedDateTime.now()
        val nextExecution =
            ExecutionTime
                .forCron(cron)
                .nextExecution(now)
                .orElseThrow {
                    IllegalArgumentException("No next execution time for cron expression: $cronExpression")
                }

        val delayMillis = Duration.between(now, nextExecution).toMillis()
        // Guard against busy-spinning when the next execution rounds down to
        // "now"; legitimate sub-second delays are preserved as-is.
        delay(if (delayMillis > 0) delayMillis else MIN_DELAY_MILLIS)
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

    private companion object {
        const val MIN_DELAY_MILLIS = 1_000L
    }
}
