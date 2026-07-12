package com.watchcluster.client.impl

import com.watchcluster.client.K8sWatcher
import com.watchcluster.client.domain.K8sWatchEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Preserves the ordering of callbacks from a single Kubernetes watch while
 * allowing downstream deployment workers to retain their own parallelism.
 */
internal class OrderedWatchDispatcher<T>(
    scope: CoroutineScope,
    private val watcher: K8sWatcher<T>,
) {
    private val events = Channel<WatchCallback<T>>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (callback in events) {
                when (callback) {
                    is WatchCallback.Event -> notifyEvent(callback.event)
                    is WatchCallback.Closed -> notifyClosed(callback)
                }
            }
        }
    }

    fun dispatch(event: K8sWatchEvent<T>) {
        if (events.trySend(WatchCallback.Event(event)).isFailure) {
            logger.warn { "Dropping Kubernetes watch event because its dispatcher is closed" }
        }
    }

    suspend fun dispatchClose(cause: Exception?) {
        val completed = CompletableDeferred<Unit>()
        events.send(WatchCallback.Closed(cause, completed))
        completed.await()
    }

    private suspend fun notifyEvent(event: K8sWatchEvent<T>) {
        try {
            watcher.eventReceived(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Error in watcher.eventReceived" }
        }
    }

    private suspend fun notifyClosed(callback: WatchCallback.Closed<T>) {
        try {
            watcher.onClose(callback.cause)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Error in watcher.onClose" }
        } finally {
            callback.completed.complete(Unit)
        }
    }

    private sealed interface WatchCallback<T> {
        data class Event<T>(
            val event: K8sWatchEvent<T>,
        ) : WatchCallback<T>

        data class Closed<T>(
            val cause: Exception?,
            val completed: CompletableDeferred<Unit>,
        ) : WatchCallback<T>
    }
}
