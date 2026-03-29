package com.idlebloom.app.data

data class PhotoDiscoveryAttempt(
    val url: String,
    val requestVariant: String,
    val responseCode: Int?,
    val successful: Boolean,
    val detail: String
)

data class PhotoDiscoveryResult(
    val photos: List<RemotePhoto>,
    val attempts: List<PhotoDiscoveryAttempt>,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean
        get() = errorMessage == null

    val successfulAttempt: PhotoDiscoveryAttempt?
        get() = attempts.lastOrNull { it.successful }
}
