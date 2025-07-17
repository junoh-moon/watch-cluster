package com.watchcluster.client

import com.watchcluster.client.domain.K8sWatchEvent

interface K8sWatcher<T> {
    suspend fun eventReceived(event: K8sWatchEvent<T>)
    suspend fun onClose(exception: Exception?)
}