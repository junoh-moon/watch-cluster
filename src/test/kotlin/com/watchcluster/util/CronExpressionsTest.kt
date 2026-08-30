package com.watchcluster.util

import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CronExpressionsTest {
    @Test
    fun `parses unix cron expressions`() {
        assertNotNull(CronExpressions.parse("*/5 * * * *"))
        assertNotNull(CronExpressions.parse("0 2 * * *"))
        assertNotNull(CronExpressions.parse("0 9-17 * * MON-FRI"))
    }

    @Test
    fun `parses quartz cron expressions for backward compatibility`() {
        assertNotNull(CronExpressions.parse("0 */5 * * * ?"))
        assertNotNull(CronExpressions.parse("0 0 2 * * ?"))
        assertNotNull(CronExpressions.parse("0 0 0 1 * ? 2026"))
    }

    @Test
    fun `rejects unsupported cron field counts`() {
        assertFailsWith<IllegalArgumentException> {
            CronExpressions.parse("* * * *")
        }
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertNotNull(CronExpressions.parse("  */5 * * * *  "))
    }

    @Test
    fun `reports validity without throwing`() {
        assertTrue(CronExpressions.isValid("*/5 * * * *"))
        assertFalse(CronExpressions.isValid("nonsense"))
    }

    @Test
    fun `an expression that parses but never fires is not valid`() {
        // Feb 30 never occurs. The parser accepts it, but a worker given it
        // disables its ticker — so it must not pass validation.
        assertNotNull(CronExpressions.parse("0 0 0 30 2 ?"))
        assertFalse(CronExpressions.isValid("0 0 0 30 2 ?"))
    }

    @Test
    fun `computes the next execution after a given instant`() {
        val from = ZonedDateTime.of(2026, 7, 12, 18, 34, 30, 0, ZoneId.of("UTC"))

        assertEquals(
            ZonedDateTime.of(2026, 7, 12, 18, 35, 0, 0, ZoneId.of("UTC")),
            CronExpressions.nextExecution("* * * * *", from),
        )
    }

    @Test
    fun `returns null instead of throwing for an unusable expression`() {
        // Callers rendering a schedule show "not scheduled" rather than failing.
        assertNull(CronExpressions.nextExecution("nonsense"))
        // Feb 30 never occurs.
        assertNull(CronExpressions.nextExecution("0 0 0 30 2 ?"))
    }
}
