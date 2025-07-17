package com.watchcluster.client.domain

data class K8sWatchEvent<T>(
    val type: EventType,
    val resource: T
)

enum class EventType {
    ADDED,
    MODIFIED,
    DELETED,
    ERROR
}