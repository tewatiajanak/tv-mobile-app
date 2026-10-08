package com.videobridge.core.data.downloads

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** How one attempt ended. */
sealed interface DownloadOutcome {
    data object Completed : DownloadOutcome

    /** The caller asked to stop: the bytes so far are kept for a later resume. */
    data object Stopped : DownloadOutcome

    /** The source refused (expired link, not found, …). Retrying will not help. */
    data class Refused(val httpStatus: Int) : DownloadOutcome
}

private const val HTTP_PARTIAL = 206
private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val BUFFER_BYTES = 128 * 1024

/**
 * Downloads [url] into [part], continuing from whatever [part] already holds. Pure Kotlin and
 * OkHttp, so every case is unit-tested without Android.
 *
 * - Bytes already on disk are kept and the rest is requested with `Range`.
 * - A server that ignores the range (answers 200) means starting again from zero.
 * - Network failures throw [IOException]: the caller retries later and this resumes.
 */
fun downloadInto(
    client: OkHttpClient,
    url: String,
    part: File,
    isStopped: () -> Boolean,
    onTotalKnown: (Long) -> Unit = {},
): DownloadOutcome {
    val existing = if (part.exists()) part.length() else 0
    val request =
        Request
            .Builder()
            .url(url)
            .apply { if (existing > 0) header("Range", "bytes=$existing-") }
            .build()
    return client.newCall(request).execute().use { response ->
        when {
            // Asked for bytes past the end: everything was already here.
            response.code == HTTP_RANGE_NOT_SATISFIABLE && existing > 0 -> DownloadOutcome.Completed

            !response.isSuccessful -> DownloadOutcome.Refused(response.code)

            else -> writeBody(response, part, existing, isStopped, onTotalKnown)
        }
    }
}

private fun writeBody(
    response: Response,
    part: File,
    existing: Long,
    isStopped: () -> Boolean,
    onTotalKnown: (Long) -> Unit,
): DownloadOutcome {
    val resuming = response.code == HTTP_PARTIAL && existing > 0
    val remaining = response.body.contentLength()
    // The size of the whole file, when the server says how much is coming.
    val expected = if (remaining < 0) null else remaining + (if (resuming) existing else 0)
    expected?.let(onTotalKnown)

    RandomAccessFile(part, "rw").use { file ->
        if (resuming) file.seek(existing) else file.setLength(0)
        val finished = copyUntilStopped(response.body.byteStream(), file, isStopped)
        // A connection that closes early must not be mistaken for the end of the file.
        if (finished && expected != null && file.length() < expected) {
            throw IOException("connection closed before the download finished")
        }
        return if (finished) DownloadOutcome.Completed else DownloadOutcome.Stopped
    }
}

/** Copies until the source ends (true) or [isStopped] says to stop (false). */
private fun copyUntilStopped(source: InputStream, file: RandomAccessFile, isStopped: () -> Boolean): Boolean {
    val buffer = ByteArray(BUFFER_BYTES)
    while (!isStopped()) {
        val read = source.read(buffer)
        if (read < 0) return true
        file.write(buffer, 0, read)
    }
    return false
}
