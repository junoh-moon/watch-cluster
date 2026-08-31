package com.watchcluster.util

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
    fun `never fires early or repeats the same cron boundary`() =
        runBlocking {
            var now =
                ZonedDateTime.of(
                    2026,
                    7,
                    12,
                    18,
                    53,
                    59,
                    500_000,
                    ZoneId.of("Asia/Seoul"),
                )
            val sleepDurations = mutableListOf<Long>()
            val deterministicTicker =
                CronUtilsTicker(
                    nowProvider = { now },
                    sleeper = { millis ->
                        sleepDurations += millis
                        now = now.plusNanos(millis * 1_000_000)
                    },
                )

            deterministicTicker.awaitNextExecution("* * * * *")
            val firstExecution = now
            deterministicTicker.awaitNextExecution("* * * * *")
            val secondExecution = now

            assertEquals(
                ZonedDateTime.of(2026, 7, 12, 18, 54, 0, 500_000, ZoneId.of("Asia/Seoul")),
                firstExecution,
            )
            assertEquals(
                ZonedDateTime.of(2026, 7, 12, 18, 55, 0, 500_000, ZoneId.of("Asia/Seoul")),
                secondExecution,
            )
            assertEquals(listOf(1_000L, 60_000L), sleepDurations)
        }
}
