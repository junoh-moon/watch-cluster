package com.watchcluster.util

import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import kotlinx.coroutines.*
import mu.KotlinLogging
import java.time.ZonedDateTime
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class CronScheduler {
    private val jobs = ConcurrentHashMap<String, Job>()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cronParser = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
    
    fun scheduleJob(jobId: String, cronExpression: String, task: suspend () -> Unit) {
        cancelJob(jobId)
        
        try {
            val cron = cronParser.parse(cronExpression)
            val executionTime = ExecutionTime.forCron(cron)
            
            val job = scope.launch {
                while (isActive) {
                    val now = ZonedDateTime.now()
                    val nextExecution = executionTime.nextExecution(now)
                    
                    if (nextExecution.isPresent) {
                        val delayMillis = Duration.between(now, nextExecution.get()).toMillis()
                        if (delayMillis > 0) {
                            delay(delayMillis)
                            if (isActive) {
                                try {
                                    logger.debug { "Executing job: $jobId" }
                                    task()
                                } catch (e: Exception) {
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
        } catch (e: Exception) {
            logger.error(e) { "Failed to schedule job: $jobId with cron: $cronExpression" }
        }
    }
    
    fun cancelJob(jobId: String) {
        jobs.remove(jobId)?.cancel()
        logger.debug { "Cancelled job: $jobId" }
    }
    
    fun shutdown() {
        logger.info { "Shutting down cron scheduler..." }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        scope.cancel()
    }
}