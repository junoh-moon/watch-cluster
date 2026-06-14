package com.watchcluster.util

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mu.KotlinLogging
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

private val whitespace = Regex("\\s+")

class CronScheduler {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val unixCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX))
    private val quartzCronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))

    fun scheduleJob(
        jobId: String,
        cronExpression: String,
        task: suspend () -> Unit,
    ) {
        cancelJob(jobId)

        runCatching {
            val cron = parseCron(cronExpression)
            val executionTime = ExecutionTime.forCron(cron)

            val job =
                scope.launch {
                    while (isActive) {
                        val now = ZonedDateTime.now()
                        val nextExecution = executionTime.nextExecution(now)

                        if (nextExecution.isPresent) {
                            val delayMillis = Duration.between(now, nextExecution.get()).toMillis()
                            if (delayMillis > 0) {
                                delay(delayMillis)
                                if (isActive) {
                                    runCatching {
                                        logger.debug { "Executing job: $jobId" }
                                        task()
                                    }.onFailure { e ->
                                        logger.error(e) { "Error executing job: $jobId" }
                                    }
                                }
                            }
                        } else {
                            logger.warn { "No next execution time found for job: $jobId" }
                            break
                        }
                    }
                }

            jobs[jobId] = job
            logger.info { "Scheduled job: $jobId with cron: $cronExpression" }
        }.onFailure { e ->
            logger.error(e) { "Failed to schedule job: $jobId with cron: $cronExpression" }
        }
    }

    internal fun parseCron(cronExpression: String) =
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

    fun cancelJob(jobId: String) {
        jobs.remove(jobId)?.cancel()
        logger.debug { "Cancelled job: $jobId" }
    }

    suspend fun cancelAndJoinJob(jobId: String) {
        jobs.remove(jobId)?.cancelAndJoin()
        logger.debug { "Cancelled and joined job: $jobId" }
    }

    fun shutdown() {
        logger.info { "Shutting down cron scheduler..." }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }
}
