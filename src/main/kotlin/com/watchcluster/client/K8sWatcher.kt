package com.watchcluster.client

import com.watchcluster.client.domain.K8sWatchEvent

interface K8sWatcher<T> {
    fun eventReceived(event: K8sWatchEvent<T>)
    fun onClose(exception: Exception?)
}