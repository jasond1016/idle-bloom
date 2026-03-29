package com.idlebloom.app.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class SourceConfig(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val remoteDirectory: String = "/",
    val slideIntervalSeconds: Int = 20,
    val shuffleEnabled: Boolean = true
) {
    fun isReady(): Boolean {
        return baseUrl.isNotBlank() && username.isNotBlank() && remoteDirectory.isNotBlank()
    }

    fun normalizedBaseUrl(): String {
        return baseUrl.trim().trimEnd('/')
    }

    fun normalizedRemoteDirectory(): String {
        val trimmed = remoteDirectory.trim()
        if (trimmed.isBlank() || trimmed == "/") {
            return "/"
        }
        return "/${trimmed.trim('/') }"
    }

    fun intervalMillis(): Long {
        return slideIntervalSeconds.coerceAtLeast(5) * 1_000L
    }

    fun directoryUrl(): String {
        val base = normalizedBaseUrl().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("WebDAV base URL is invalid")

        val directorySegments = normalizedRemoteDirectory()
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }

        val builder = base.newBuilder()
        directorySegments.forEach(builder::addPathSegment)
        return builder.build().toString()
    }
}
