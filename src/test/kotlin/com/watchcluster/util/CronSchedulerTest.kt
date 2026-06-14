package com.watchcluster.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
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
    fun `should parse unix cron expressions`() {
        assertNotNull(cronScheduler.parseCron("*/5 * * * *"))
        assertNotNull(cronScheduler.parseCron("0 2 * * *"))
        assertNotNull(cronScheduler.parseCron("0 9-17 * * MON-FRI"))
    }

    @Test
    fun `should parse quartz cron expressions for backward compatibility`() {
        assertNotNull(cronScheduler.parseCron("0 */5 * * * ?"))
        assertNotNull(cronScheduler.parseCron("0 0 2 * * ?"))
        assertNotNull(cronScheduler.parseCron("0 0 0 1 * ? 2026"))
    }

    @Test
    fun `should reject unsupported cron field counts`() {
        assertFailsWith<IllegalArgumentException> {
            cronScheduler.parseCron("* * * *")
        }
    }

    @Test
    fun `should handle invalid cron expression gracefully`() {
        val jobId = "invalid-job"

        cronScheduler.scheduleJob(jobId, "invalid cron") {
            // Should not throw exception
        }
    }

}
