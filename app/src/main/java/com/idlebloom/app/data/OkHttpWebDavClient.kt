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
        require(config.isReady()) { "WebDAV config is incomplete." }

        val directoryUrl = config.directoryUrl()
        val authHeader = Credentials.basic(config.username, config.password)
        var lastFailure: String? = null
        val attempts = mutableListOf<PhotoDiscoveryAttempt>()

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
                            lastFailure = "${response.code} on $candidateUrl"
                            attempts += PhotoDiscoveryAttempt(
                                url = candidateUrl,
                                requestVariant = candidateBody.label,
                                responseCode = response.code,
                                successful = false,
                                detail = response.message.ifBlank { "HTTP ${response.code}" }
                            )
                        } else {
                            val xml = response.body?.string().orEmpty()
                            val photos = parsePhotos(xml = xml, requestedDirectoryUrl = candidateUrl)
                            attempts += PhotoDiscoveryAttempt(
                                url = candidateUrl,
                                requestVariant = candidateBody.label,
                                responseCode = response.code,
                                successful = true,
                                detail = "Found ${photos.size} photo entries"
                            )
                            return@withContext PhotoDiscoveryResult(
                                photos = photos,
                                attempts = attempts.toList()
                            )
                        }
                    }
                } catch (t: Throwable) {
                    lastFailure = t.message ?: t.javaClass.simpleName
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

        return@withContext PhotoDiscoveryResult(
            photos = emptyList(),
            attempts = attempts.toList(),
            errorMessage = buildFailureMessage(config, lastFailure)
        )
    }

    private fun parsePhotos(
        xml: String,
        requestedDirectoryUrl: String
    ): List<RemotePhoto> {
        if (xml.isBlank()) return emptyList()

        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }

        val photos = mutableListOf<RemotePhoto>()
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
                        current.toRemotePhoto(requestedDirectoryUrl)?.let(photos::add)
                        current = MutableEntry()
                    }
                    if (tag == currentTag) {
                        currentTag = null
                    }
                }
            }
        }

        return photos
            .distinctBy { it.url }
            .sortedBy { it.name.lowercase() }
    }

    private data class MutableEntry(
        var href: String? = null,
        var displayName: String? = null,
        var contentType: String? = null,
        var contentLength: String? = null,
        var lastModified: String? = null,
        var isCollection: Boolean = false
    ) {
        fun toRemotePhoto(requestedDirectoryUrl: String): RemotePhoto? {
            if (isCollection) return null

            val hrefValue = href ?: return null
            val absoluteUrl = Companion.resolveUrl(requestedDirectoryUrl, hrefValue) ?: return null
            val normalizedAbsolute = absoluteUrl.trimEnd('/')
            val normalizedRequested = requestedDirectoryUrl.trimEnd('/')
            if (normalizedAbsolute == normalizedRequested) return null

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
            )
        }
    }

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
                label = "prop fields",
                body = PROP_BODY
            ),
            RequestVariant(
                label = "allprop",
                body = ALLPROP_BODY
            ),
            RequestVariant(
                label = "empty body",
                body = ""
            )
        )
    }

    private data class RequestVariant(
        val label: String,
        val body: String
    )

    private fun buildFailureMessage(config: SourceConfig, lastFailure: String?): String {
        val details = lastFailure ?: "unknown response"
        return buildString {
            append("WebDAV PROPFIND failed: ")
            append(details)

            if (lastFailure?.startsWith("400") == true) {
                append(". On NAS, the base URL is usually just the NAS host and WebDAV port, and the photo folder should include the shared folder name. Example: base URL http://192.168.1.10:80 and photo folder /Public")
            }

            if (config.normalizedRemoteDirectory() == "/") {
                append(". The current photo folder is '/', which is often not a valid NAS WebDAV share path")
            }
        }
    }

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
        private const val USER_AGENT = "IdleBloom/0.1"

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
    }
}
