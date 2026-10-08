package com.videobridge.core.model

/** A saved video link. */
data class Video(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val sourceDomain: String,
    /** File size and short format name ("MP4", "HLS"), when the saving device could tell. */
    val sizeBytes: Long? = null,
    val format: String? = null,
    /** Where the viewer stopped, on any device. */
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    /** A picture the link itself advertises, if any. Never a frame grabbed from the video. */
    val thumbnailUrl: String? = null,
) {
    /** 0..1 watched, or null when nothing was watched yet or the length is unknown. */
    val progress: Float?
        get() = durationMs?.takeIf { it > 0 && positionMs > 0 }?.let { (positionMs.toFloat() / it).coerceIn(0f, 1f) }
}

private const val KB = 1024.0
private val SIZE_UNITS = listOf("KB", "MB", "GB", "TB")

/** "734 MB", "4.7 GB". */
fun formatBytes(bytes: Long): String {
    var value = bytes / KB
    var unit = 0
    while (value >= KB && unit < SIZE_UNITS.lastIndex) {
        value /= KB
        unit++
    }
    return if (value >= 100 || unit == 0) "%.0f %s".format(value, SIZE_UNITS[unit]) else "%.1f %s".format(value, SIZE_UNITS[unit])
}
