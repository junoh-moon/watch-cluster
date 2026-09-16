package com.watchcluster.model

import java.time.Duration

object MinimumReleaseAge {
    private val pattern = Regex("([1-9][0-9]*)([hdw])")

    /** Null means invalid; an absent annotation and literal 0 disable the gate. */
    fun parse(value: String?): Duration? {
        if (value == null || value == "0") return Duration.ZERO
        val match = pattern.matchEntire(value) ?: return null
        val amount = match.groupValues[1].toLongOrNull() ?: return null
        val secondsPerUnit =
            when (match.groupValues[2]) {
                "h" -> 3600L
                "d" -> 86400L
                else -> 604800L
            }
        return try {
            Duration.ofSeconds(Math.multiplyExact(amount, secondsPerUnit))
        } catch (_: ArithmeticException) {
            null
        }
    }
}
