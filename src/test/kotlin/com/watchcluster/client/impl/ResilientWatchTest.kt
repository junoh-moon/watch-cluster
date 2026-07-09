package com.watchcluster.client.impl

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class ResilientWatchTest {
    private class FakeWatch : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `start keeps the opened watch strongly referenced`() =
        runTest {
            val openedWatch = FakeWatch()
            val resilientWatch =
                ResilientWatch(
                    scope = TestScope(testScheduler),
                    initialReconnectDelayMillis = 0,
                    jitterRatio = 0.0,
                    openWatch = { openedWatch },
                    onClose = {},
                )

            resilientWatch.start()

            assertSame(openedWatch, resilientWatch.currentWatch)
        }

    @Test
    fun `closed watch is reopened`() =
        runTest {
            val closeHandlers = mutableListOf<(Exception?) -> Unit>()
            val openedWatches = mutableListOf<FakeWatch>()
            val resilientWatch =
                ResilientWatch(
                    scope = TestScope(testScheduler),
                    initialReconnectDelayMillis = 0,
                    jitterRatio = 0.0,
                    openWatch = { onClose ->
                        closeHandlers += onClose
                        FakeWatch().also(openedWatches::add)
                    },
                    onClose = {},
                )

            resilientWatch.start()
            closeHandlers.single().invoke(null)
            advanceUntilIdle()

            assertEquals(2, openedWatches.size)
            assertSame(openedWatches.last(), resilientWatch.currentWatch)
        }

    @Test
    fun `stop prevents reopen after close`() =
        runTest {
            var closeHandler: ((Exception?) -> Unit)? = null
            val resilientWatch =
                ResilientWatch(
                    scope = TestScope(testScheduler),
                    initialReconnectDelayMillis = 100,
                    jitterRatio = 0.0,
                    openWatch = { onClose ->
                        closeHandler = onClose
                        FakeWatch()
                    },
                    onClose = {},
                )

            resilientWatch.start()
            closeHandler?.invoke(null)
            resilientWatch.stop()
            advanceTimeBy(100)
            advanceUntilIdle()

            assertNull(resilientWatch.currentWatch)
        }

    @Test
    fun `initial open failure propagates out of start`() =
        runTest {
            var attempts = 0
            val resilientWatch =
                ResilientWatch(
                    scope = TestScope(testScheduler),
                    initialReconnectDelayMillis = 100,
                    jitterRatio = 0.0,
                    openWatch = { _ ->
                        attempts++
                        throw IllegalStateException("watch failed")
                    },
                    onClose = {},
                )

            assertFailsWith<IllegalStateException> {
                resilientWatch.start()
            }

            advanceUntilIdle()

            assertEquals(1, attempts)
            assertNull(resilientWatch.currentWatch)
        }

    @Test
    fun `reconnect failure is retried with exponential backoff`() =
        runTest {
            val closeHandlers = mutableListOf<(Exception?) -> Unit>()
            var attempts = 0
            val resilientWatch =
                ResilientWatch(
                    scope = TestScope(testScheduler),
                    initialReconnectDelayMillis = 100,
                    maxReconnectDelayMillis = 250,
                    jitterRatio = 0.0,
                    openWatch = { onClose ->
                        attempts++
                        if (attempts in 2..3) {
                            throw IllegalStateException("watch failed")
                        }
                        closeHandlers += onClose
                        FakeWatch()
                    },
                    onClose = {},
                )

            resilientWatch.start()
            assertEquals(1, attempts)

            closeHandlers.single().invoke(null)

            advanceTimeBy(99)
            assertEquals(1, attempts)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, attempts)

            advanceTimeBy(199)
            assertEquals(2, attempts)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, attempts)

            advanceTimeBy(249)
            assertEquals(3, attempts)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(4, attempts)
            assertNotNull(resilientWatch.currentWatch)
        }

    @Test
    fun `stop racing with open closes the freshly opened watch`() =
        runTest {
            lateinit var resilientWatch: ResilientWatch
            val openedWatch = FakeWatch()
            resilientWatch =
                ResilientWatch(
                    scope = TestScope(testScheduler),
                    initialReconnectDelayMillis = 100,
                    jitterRatio = 0.0,
                    openWatch = { _ ->
                        // Simulate stop() interleaving between the stopped
                        // check and the currentWatch assignment.
                        resilientWatch.stop()
                        openedWatch
                    },
                    onClose = {},
                )

            resilientWatch.start()

            assertEquals(true, openedWatch.closed)
            assertNull(resilientWatch.currentWatch)
        }
}
