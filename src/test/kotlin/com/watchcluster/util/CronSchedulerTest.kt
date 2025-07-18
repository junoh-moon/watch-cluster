package com.watchcluster.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

class CronSchedulerTest {
    private lateinit var cronScheduler: CronScheduler

    @BeforeEach
    fun setup() {
        cronScheduler = CronScheduler()
    }

    @AfterEach
    fun tearDown() {
        cronScheduler.shutdown()
    }

    @Test
    fun `should schedule and execute job`() =
        runBlocking {
            val counter = AtomicInteger(0)
            val jobId = "test-job"

            cronScheduler.scheduleJob(jobId, "*/1 * * * * ?") {
                counter.incrementAndGet()
            }

            delay(3000)

            assertTrue(counter.get() >= 2, "Job should have executed at least twice, but was ${counter.get()}")
        }

    @Test
    fun `should handle invalid cron expression gracefully`() {
        val jobId = "invalid-job"

        cronScheduler.scheduleJob(jobId, "invalid cron") {
            // Should not throw exception
        }
    }

    @Test
    fun `should support various cron expressions`() {
        val expressions =
            listOf(
                "0 */5 * * * ?",
                "0 0 * * * ?",
                "0 0 2 * * ?",
                "0 0 0 * * MON",
                "0 0 0 1 * ?",
            )

        expressions.forEach { expr ->
            cronScheduler.scheduleJob("job-$expr", expr) {
                // Just validate that it can be scheduled
            }
        }
    }
}
