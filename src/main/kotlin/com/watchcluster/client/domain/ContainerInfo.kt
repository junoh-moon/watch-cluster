package com.watchcluster.client.domain

data class ContainerInfo(
    val name: String,
    val image: String,
    val imageID: String? = null,
)
