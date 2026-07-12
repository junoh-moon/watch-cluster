package com.watchcluster.client.impl

import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.EventType
import com.watchcluster.client.domain.K8sWatchEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class OrderedWatchDispatcherTest {
    @Test
    fun `dispatches watch events in arrival order`() =
        runTest {
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val allReceived = CompletableDeferred<Unit>()
            val received = mutableListOf<Int>()
            val watcher =
                object : K8sWatcher<Int> {
                    override suspend fun eventReceived(event: K8sWatchEvent<Int>) {
                        if (event.resource == 1) {
                            firstStarted.complete(Unit)
                            releaseFirst.await()
                        }
                        received += event.resource
                        if (received.size == 2) allReceived.complete(Unit)
                    }

                    override suspend fun onClose(exception: Exception?) = Unit
                }
            val dispatcher = OrderedWatchDispatcher(backgroundScope, watcher)

            dispatcher.dispatch(K8sWatchEvent(EventType.ADDED, 1))
            dispatcher.dispatch(K8sWatchEvent(EventType.MODIFIED, 2))
            firstStarted.await()

            assertEquals(emptyList(), received)

            releaseFirst.complete(Unit)
            allReceived.await()

            assertEquals(listOf(1, 2), received)
        }
}
