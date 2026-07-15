package com.idlebloom.app.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class OkHttpWebDavClient(
    private val okHttpClient: OkHttpClient = OkHttpClient()
) : WebDavClient {

    override suspend fun discoverPhotos(config: SourceConfig): PhotoDiscoveryResult = withContext(Dispatchers.IO) {
        require(config.isReady()) { "WebDAV 配置不完整。" }

        val directoryUrl = config.directoryUrl()
        val authHeader = Credentials.basic(config.username, config.password)
        var lastFailure: String? = null
        val attempts = mutableListOf<PhotoDiscoveryAttempt>()
        val discoveredPhotos = linkedMapOf<String, RemotePhoto>()
        val pendingDirectories = ArrayDeque<String>().apply { add(directoryUrl) }
        val visitedDirectories = linkedSetOf<String>()
        var discoveredAnyDirectory = false

        while (pendingDirectories.isNotEmpty() && visitedDirectories.size < MAX_DIRECTORY_VISITS) {
            val nextDirectory = pendingDirectories.removeFirst()
            val normalizedDirectory = normalizeComparableUrl(nextDirectory)
            if (!visitedDirectories.add(normalizedDirectory)) {
                continue
            }

            val listing = performDirectoryPropfind(
                directoryUrl = nextDirectory,
                authHeader = authHeader,
                attempts = attempts
            )

            if (listing == null) {
                continue
            }

            discoveredAnyDirectory = true
            lastFailure = null

            listing.photos.forEach { photo ->
                discoveredPhotos.putIfAbsent(photo.url, photo)
            }

            listing.childDirectories
                .map(::normalizeDirectoryUrl)
                .filterNot { normalizeComparableUrl(it) in visitedDirectories }
                .forEach(pendingDirectories::addLast)
        }

        return@withContext PhotoDiscoveryResult(
            photos = discoveredPhotos.values.sortedBy { it.name.lowercase() },
            attempts = attempts.toList(),
            errorMessage = if (discoveredAnyDirectory) null else buildFailureMessage(config, lastFailure)
        )
    }

    override suspend fun deletePhoto(config: SourceConfig, photo: RemotePhoto): PhotoDeleteResult =
        withContext(Dispatchers.IO) {
            require(config.isReady()) { "WebDAV 配置不完整。" }

            val request = Request.Builder()
                .url(photo.url)
                .header("Authorization", Credentials.basic(config.username, config.password))
                .header("User-Agent", USER_AGENT)
                .delete()
                .build()

            return@withContext try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        PhotoDeleteResult(success = true)
                    } else {
                        PhotoDeleteResult(
                            success = false,
                            errorMessage = response.message.ifBlank { "HTTP 状态码 ${response.code}" }
                        )
                    }
                }
            } catch (t: Throwable) {
                PhotoDeleteResult(
                    success = false,
                    errorMessage = t.message ?: t.javaClass.simpleName
                )
            }
        }

    private fun performDirectoryPropfind(
        directoryUrl: String,
        authHeader: String,
        attempts: MutableList<PhotoDiscoveryAttempt>
    ): DirectoryListing? {
        var lastFailureForDirectory: String? = null

        for (candidateUrl in candidateUrls(directoryUrl)) {
            for (candidateBody in candidateBodies()) {
                val request = Request.Builder()
                    .url(candidateUrl)
                    .header("Authorization", authHeader)
                    .header("Depth", "1")
                    .header("Accept", "application/xml, text/xml, */*")
                    .header("User-Agent", USER_AGENT)
                    .method("PROPFIND", candidateBody.body.toRequestBody(XML_MEDIA_TYPE))
                    .build()

                try {
                    okHttpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            lastFailureForDirectory = "${response.code} on $candidateUrl"
                            attempts += PhotoDiscoveryAttempt(
                                url = candidateUrl,
                                requestVariant = candidateBody.label,
                                responseCode = response.code,
                                successful = false,
                            detail = response.message.ifBlank { "HTTP 状态码 ${response.code}" }
                            )
                        } else {
                            val xml = response.body?.string().orEmpty()
                            val listing = parseDirectoryListing(xml = xml, requestedDirectoryUrl = candidateUrl)
                            attempts += PhotoDiscoveryAttempt(
                                url = candidateUrl,
                                requestVariant = candidateBody.label,
                                responseCode = response.code,
                                successful = true,
                                detail = "找到 ${listing.photos.size} 张照片和 ${listing.childDirectories.size} 个子文件夹"
                            )
                            return listing
                        }
                    }
                } catch (t: Throwable) {
                    lastFailureForDirectory = t.message ?: t.javaClass.simpleName
                    attempts += PhotoDiscoveryAttempt(
                        url = candidateUrl,
                        requestVariant = candidateBody.label,
                        responseCode = null,
                        successful = false,
                        detail = t.message ?: t.javaClass.simpleName
                    )
                }
            }
        }

        return null
    }

    private fun parseDirectoryListing(
        xml: String,
        requestedDirectoryUrl: String
    ): DirectoryListing {
        if (xml.isBlank()) return DirectoryListing()

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        val photos = mutableListOf<RemotePhoto>()
        val childDirectories = mutableListOf<String>()
        var current = MutableEntry()
        var currentTag: String? = null

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    val tag = parser.name.localName()
                    when (tag) {
                        "response" -> current = MutableEntry()
                        "href", "displayname", "getcontenttype", "getlastmodified", "getcontentlength" -> {
                            currentTag = tag
                        }
                        "collection" -> current.isCollection = true
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        when (currentTag) {
                            "href" -> current.href = text
                            "displayname" -> current.displayName = text
                            "getcontenttype" -> current.contentType = text
                            "getlastmodified" -> current.lastModified = text
                            "getcontentlength" -> current.contentLength = text
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    val tag = parser.name.localName()
                    if (tag == "response") {
                        when (val entry = current.toResolvedEntry(requestedDirectoryUrl)) {
                            is ResolvedEntry.Directory -> childDirectories += entry.url
                            is ResolvedEntry.Photo -> photos += entry.photo
                            null -> Unit
                        }
                        current = MutableEntry()
                    }
                    if (tag == currentTag) {
                        currentTag = null
                    }
                }
            }
        }

        return DirectoryListing(
            photos = photos.distinctBy { it.url },
            childDirectories = childDirectories
                .distinctBy(::normalizeComparableUrl)
                .sortedBy { decodePathSegment(it.substringAfterLast('/').substringBefore('?')) }
        )
    }

    private data class MutableEntry(
        var href: String? = null,
        var displayName: String? = null,
        var contentType: String? = null,
        var contentLength: String? = null,
        var lastModified: String? = null,
        var isCollection: Boolean = false
    ) {
        fun toResolvedEntry(requestedDirectoryUrl: String): ResolvedEntry? {
            val hrefValue = href ?: return null
            val absoluteUrl = Companion.resolveUrl(requestedDirectoryUrl, hrefValue) ?: return null
            val normalizedAbsolute = normalizeComparableUrl(absoluteUrl)
            val normalizedRequested = normalizeComparableUrl(requestedDirectoryUrl)
            if (normalizedAbsolute == normalizedRequested) return null

            if (isCollection) {
                return ResolvedEntry.Directory(normalizeDirectoryUrl(absoluteUrl))
            }

            val imageType = contentType?.startsWith("image/") == true
            val imageByExtension = IMAGE_EXTENSIONS.any { absoluteUrl.endsWith(it, ignoreCase = true) }
            if (!imageType && !imageByExtension) return null

            val name = displayName
                ?.takeIf { it.isNotBlank() }
                ?: Companion.decodePathSegment(absoluteUrl.substringAfterLast('/').substringBefore('?'))

            return RemotePhoto(
                id = absoluteUrl,
                name = name,
                url = absoluteUrl,
                contentType = contentType,
                lastModified = lastModified
            ).let(ResolvedEntry::Photo)
        }
    }

    private sealed interface ResolvedEntry {
        data class Photo(val photo: RemotePhoto) : ResolvedEntry
        data class Directory(val url: String) : ResolvedEntry
    }

    private data class DirectoryListing(
        val photos: List<RemotePhoto> = emptyList(),
        val childDirectories: List<String> = emptyList()
    )

    private fun String.localName(): String {
        return substringAfter(':')
    }

    private fun candidateUrls(directoryUrl: String): List<String> {
        val trimmed = directoryUrl.trim()
        if (trimmed.isEmpty()) return emptyList()

        return buildList {
            add(trimmed)
            if (trimmed.endsWith('/')) {
                if (trimmed.length > 1) add(trimmed.trimEnd('/'))
            } else {
                add("$trimmed/")
            }
        }.distinct()
    }

    private fun candidateBodies(): List<RequestVariant> {
        return listOf(
            RequestVariant(
                label = "指定属性",
                body = PROP_BODY
            ),
            RequestVariant(
                label = "全部属性",
                body = ALLPROP_BODY
            ),
            RequestVariant(
                label = "空请求体",
                body = ""
            )
        )
    }

    private data class RequestVariant(
        val label: String,
        val body: String
    )

    private fun buildFailureMessage(config: SourceConfig, lastFailure: String?): String {
        val details = lastFailure ?: "未知响应"
        return buildString {
            append("WebDAV PROPFIND 请求失败：")
            append(details)

            if (lastFailure?.startsWith("400") == true) {
                append("。在 NAS 上，服务器地址通常只包含 NAS 主机和 WebDAV 端口；照片文件夹应包含共享文件夹名称。例如：服务器地址 http://192.168.1.10:80，照片文件夹 /Public")
            }

            if (config.normalizedRemoteDirectory() == "/") {
                append("。当前照片文件夹为“/”，这通常不是有效的 NAS WebDAV 共享路径")
            }
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private const val USER_AGENT = "IdleBloom/0.1"
        private const val MAX_DIRECTORY_VISITS = 512

        private val IMAGE_EXTENSIONS = listOf(
            ".jpg",
            ".jpeg",
            ".png",
            ".webp",
            ".gif",
            ".bmp",
            ".heic"
        )

        private val PROP_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:displayname />
                    <d:getcontenttype />
                    <d:getlastmodified />
                    <d:getcontentlength />
                    <d:resourcetype />
                </d:prop>
            </d:propfind>
        """.trimIndent().trim()

        private val ALLPROP_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:allprop />
            </d:propfind>
        """.trimIndent().trim()

        private fun resolveUrl(baseUrl: String, href: String): String? {
            val trimmedHref = href.trim()
            val httpBase = baseUrl.toHttpUrlOrNull()
            if (httpBase != null) {
                httpBase.resolve(trimmedHref)?.let { return it.toString() }
                trimmedHref.toHttpUrlOrNull()?.let { return it.toString() }
            }

            return runCatching { URI(baseUrl).resolve(trimmedHref).toString() }.getOrNull()
        }

        private fun decodePathSegment(value: String): String {
            return runCatching {
                URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
            }.getOrDefault(value)
        }

        private fun normalizeComparableUrl(value: String): String {
            return value.trim().trimEnd('/')
        }

        private fun normalizeDirectoryUrl(value: String): String {
            return "${normalizeComparableUrl(value)}/"
        }
    }
}
