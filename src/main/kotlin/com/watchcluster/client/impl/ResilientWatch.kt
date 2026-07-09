package com.watchcluster.client.impl

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.random.Random

internal class ResilientWatch(
    private val scope: CoroutineScope,
    private val initialReconnectDelayMillis: Long,
    private val maxReconnectDelayMillis: Long = initialReconnectDelayMillis,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
    private val openWatch: (onClose: (Exception?) -> Unit) -> AutoCloseable,
    private val onClose: suspend (Exception?) -> Unit,
) {
    private val reconnecting = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private var reconnectAttempt = 0

    @Volatile
    internal var currentWatch: AutoCloseable? = null
        private set

    /**
     * Opens the initial watch. A failure here propagates to the caller so that
     * fatal misconfiguration (e.g. missing RBAC) surfaces as a startup failure
     * instead of being retried forever. Only closes of an established watch
     * are retried with backoff.
     */
    fun start() {
        if (stopped.get()) return

        currentWatch = openWatch(::handleClose)
        closeIfStopped()
    }

    fun stop() {
        stopped.set(true)
        currentWatch?.close()
        currentWatch = null
    }

    private fun connect() {
        if (stopped.get()) return

        runCatching {
            currentWatch = openWatch(::handleClose)
            reconnectAttempt = 0
            closeIfStopped()
        }.onFailure { e ->
            handleClose(e as? Exception ?: RuntimeException(e))
        }
    }

    /**
     * Re-checks [stopped] after assigning [currentWatch] so that a stop()
     * racing with an open does not leave a live, unreferenced watch behind.
     */
    private fun closeIfStopped() {
        if (stopped.get()) {
            currentWatch?.close()
            currentWatch = null
        }
    }

    private fun handleClose(cause: Exception?) {
        currentWatch = null

        if (stopped.get()) return
        if (!reconnecting.compareAndSet(false, true)) return

        scope.launch {
            runCatching { onClose(cause) }
            delay(nextReconnectDelayMillis())
            reconnecting.set(false)
            connect()
        }
    }

    private fun nextReconnectDelayMillis(): Long {
        val baseDelay = exponentialDelayMillis(reconnectAttempt)
        reconnectAttempt++

        if (jitterRatio <= 0.0 || baseDelay <= 0L) return baseDelay

        val jitter = (baseDelay * jitterRatio).toLong()
        if (jitter <= 0L) return baseDelay

        val minDelay = (baseDelay - jitter).coerceAtLeast(0L)
        val maxDelayExclusive = baseDelay + jitter + 1
        if (maxDelayExclusive <= minDelay) return baseDelay

        return random.nextLong(minDelay, maxDelayExclusive)
    }

    private fun exponentialDelayMillis(attempt: Int): Long {
        val multiplier = 2.0.pow(attempt.coerceAtMost(MAX_BACKOFF_EXPONENT)).toLong()
        return (initialReconnectDelayMillis * multiplier).coerceAtMost(maxReconnectDelayMillis)
    }

    private companion object {
        const val MAX_BACKOFF_EXPONENT = 30
    }
}
