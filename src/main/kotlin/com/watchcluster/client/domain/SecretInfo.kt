package com.watchcluster.client.domain

data class SecretInfo(
    val namespace: String,
    val name: String,
    val type: String,
    val data: Map<String, String> // Base64 decoded data
)