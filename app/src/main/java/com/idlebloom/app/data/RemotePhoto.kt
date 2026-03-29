package com.idlebloom.app.data

data class RemotePhoto(
    val id: String,
    val name: String,
    val url: String,
    val contentType: String?,
    val lastModified: String?
)
