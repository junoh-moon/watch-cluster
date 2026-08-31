package com.watchcluster.util

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import java.time.ZonedDateTime

private val whitespace = Regex("\\s+")

/**
 * Parsing and next-execution arithmetic for the cron expressions carried by
 * `watch-cluster.io/cron`.
 *
 * Kept separate from [CronTicker] so that callers which only need to answer
 * "is this expression valid?" or "when does it next fire?" — request
 * validation and the status API — do not have to own a ticker or suspend.
 */
object CronExpressions {
    private val unixCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private val quartzCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))

    /**
     * Parses 5-field Unix cron as the primary format; 6/7-field Quartz cron
     * remains supported for backward compatibility.
     *
     * @throws IllegalArgumentException if the expression cannot be parsed.
     */
    fun parse(cronExpression: String): Cron =
        cronExpression.trim().let { expression ->
            parserFor(expression).parse(expression)
        }

    /**
     * Returns the next firing time of [cronExpression] after [from], or null
     * if the expression is invalid or has no further execution. Callers
     * displaying schedule state use the null to mean "not scheduled" rather
     * than failing the whole response.
     */
    fun nextExecution(
        cronExpression: String,
        from: ZonedDateTime = ZonedDateTime.now(),
    ): ZonedDateTime? = runCatching { parse(cronExpression) }.getOrNull()?.let { nextExecution(it, from) }

    /** Overload for callers that already parsed the expression. */
    fun nextExecution(
        cron: Cron,
        from: ZonedDateTime = ZonedDateTime.now(),
    ): ZonedDateTime? =
        ExecutionTime
            .forCron(cron)
            .nextExecution(from)
            .orElse(null)

    /**
     * True when the expression parses *and* has a future execution. An
     * expression like `0 0 0 30 2 ?` parses cleanly but never fires, and a
     * worker given one disables its ticker — so accepting it would show a
     * schedule that silently never runs.
     */
    fun isValid(cronExpression: String): Boolean = nextExecution(cronExpression) != null

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
