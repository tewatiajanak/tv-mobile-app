package com.videobridge.core.data.videos

import com.videobridge.core.common.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

data class VideoInfo(
    val sizeBytes: Long? = null,
    val format: String? = null,
    /** A picture the page advertises for itself (og:image). Only web pages have one. */
    val thumbnailUrl: String? = null,
)

/**
 * Asks the video's own server how big the file is and what it is, reading headers only (a HEAD
 * request, or one byte when HEAD is refused). It runs on the device, never on the backend, and
 * sends nothing of ours: a bare client with no account headers.
 */
class VideoProbe
@Inject
constructor(@IoDispatcher private val ioDispatcher: CoroutineDispatcher) {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS * 2, TimeUnit.SECONDS)
            .build()

    suspend fun inspect(url: String): VideoInfo = withContext(ioDispatcher) {
        val fromUrl = formatFromUrl(url)
        try {
            (head(url, fromUrl) ?: firstByte(url, fromUrl) ?: VideoInfo(format = fromUrl)).let { info ->
                // Only a link with no video format is worth asking for a page picture.
                if (info.format == null) info.copy(thumbnailUrl = pagePicture(url)) else info
            }
        } catch (_: IOException) {
            VideoInfo(format = fromUrl)
        } catch (_: IllegalArgumentException) {
            // Not a URL OkHttp accepts; the backend will reject it with a proper message.
            VideoInfo(format = fromUrl)
        }
    }

    private fun head(url: String, fromUrl: String?): VideoInfo? =
        client.newCall(Request.Builder().url(url).head().build()).execute().use { response ->
            if (!response.isSuccessful) return null
            val size = response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            VideoInfo(size, formatFromContentType(response.header("Content-Type")) ?: fromUrl)
        }

    /**
     * For a web page: the picture it advertises for itself (og:image), read from the first
     * {@link MAX_HTML_BYTES} of the page. The video is never fetched or decoded for a picture.
     */
    private fun pagePicture(url: String): String? {
        val request = Request.Builder().url(url).header("Range", "bytes=0-${MAX_HTML_BYTES - 1}").build()
        return client.newCall(request).execute().use { response ->
            val isPage = response.isSuccessful && response.header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true
            if (!isPage) return@use null
            val html = response.body.source().apply { request(MAX_HTML_BYTES.toLong()) }.buffer.snapshot().utf8()
            OG_IMAGE.find(html)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }?.replace("&amp;", "&")?.takeIf(::isWebUrl)
        }
    }

    private fun firstByte(url: String, fromUrl: String?): VideoInfo? =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute().use { response ->
            if (!response.isSuccessful) return null
            // "bytes 0-0/12345" carries the full size.
            val size = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.takeIf { it > 0 }
            VideoInfo(size, formatFromContentType(response.header("Content-Type")) ?: fromUrl)
        }

    companion object {
        private const val TIMEOUT_SECONDS = 6L
        private const val MAX_FORMAT_LENGTH = 5
        private const val MAX_HTML_BYTES = 65_536
        private const val MAX_URL_LENGTH = 2048

        // <meta property="og:image" content="…">, with the two attributes in either order.
        private const val QUOTED = """["']([^"']+)["']"""
        private const val IS_OG_IMAGE = """property=["']og:image["']"""
        private val OG_IMAGE =
            Regex("<meta[^>]+$IS_OG_IMAGE[^>]+content=$QUOTED|<meta[^>]+content=$QUOTED[^>]+$IS_OG_IMAGE", RegexOption.IGNORE_CASE)

        private fun isWebUrl(value: String): Boolean =
            value.length <= MAX_URL_LENGTH && (value.startsWith("https://") || value.startsWith("http://")) &&
                value.none { it.isWhitespace() || it in "<>\"'" }

        private val CONTENT_TYPES =
            mapOf(
                "video/mp4" to "MP4",
                "video/x-matroska" to "MKV",
                "video/webm" to "WEBM",
                "video/mp2t" to "TS",
                "video/quicktime" to "MOV",
                "video/x-msvideo" to "AVI",
                "video/x-m4v" to "M4V",
                "application/vnd.apple.mpegurl" to "HLS",
                "application/x-mpegurl" to "HLS",
                "audio/mpegurl" to "HLS",
                "application/dash+xml" to "DASH",
            )
        private val EXTENSIONS =
            mapOf(
                "mp4" to "MP4",
                "m4v" to "M4V",
                "mkv" to "MKV",
                "webm" to "WEBM",
                "mov" to "MOV",
                "avi" to "AVI",
                "ts" to "TS",
                "m3u8" to "HLS",
                "mpd" to "DASH",
                "mp3" to "MP3",
                "flv" to "FLV",
            )

        fun formatFromContentType(contentType: String?): String? = CONTENT_TYPES[contentType?.substringBefore(';')?.trim()?.lowercase()]

        fun formatFromUrl(url: String): String? {
            val extension =
                url
                    .substringBefore('#')
                    .substringBefore('?')
                    .substringAfterLast('/')
                    .substringAfterLast('.', "")
                    .lowercase()
            return EXTENSIONS[extension.takeIf { it.length in 2..MAX_FORMAT_LENGTH }]
        }
    }
}
