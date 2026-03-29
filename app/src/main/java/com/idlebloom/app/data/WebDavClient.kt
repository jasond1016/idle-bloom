package com.idlebloom.app.data

interface WebDavClient {
    suspend fun listPhotos(config: SourceConfig): List<RemotePhoto>
}
