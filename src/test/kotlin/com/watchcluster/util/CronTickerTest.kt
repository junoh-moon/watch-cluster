package com.watchcluster.util

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CronTickerTest {
    private val ticker = CronUtilsTicker()

    @Test
    fun `awaits next execution of a valid expression`(): Unit =
        runBlocking {
            // Fires every second, so the await must complete well within 3s.
            withTimeout(3_000) {
                ticker.awaitNextExecution("*/1 * * * * ?")
            }
        }

    @Test
    fun `throws on invalid expression`(): Unit =
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                ticker.awaitNextExecution("invalid cron")
            }
        }

    @Test
    fun `throws when expression has no next execution`(): Unit =
        runBlocking {
            // Feb 30 never occurs.
            assertFailsWith<IllegalArgumentException> {
                ticker.awaitNextExecution("0 0 0 30 2 ?")
            }
        }

    @Test
    fun `parses unix cron expressions`() {
        assertNotNull(ticker.parseCron("*/5 * * * *"))
        assertNotNull(ticker.parseCron("0 2 * * *"))
        assertNotNull(ticker.parseCron("0 9-17 * * MON-FRI"))
    }

    @Test
    fun `parses quartz cron expressions for backward compatibility`() {
        assertNotNull(ticker.parseCron("0 */5 * * * ?"))
        assertNotNull(ticker.parseCron("0 0 2 * * ?"))
        assertNotNull(ticker.parseCron("0 0 0 1 * ? 2026"))
    }

    @Test
    fun `rejects unsupported cron field counts`() {
        assertFailsWith<IllegalArgumentException> {
            ticker.parseCron("* * * *")
        }
    }
}
