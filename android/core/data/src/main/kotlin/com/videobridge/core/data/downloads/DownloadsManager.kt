package com.videobridge.core.data.downloads

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.videobridge.core.common.IoDispatcher
import com.videobridge.core.datastore.DeviceSettings
import com.videobridge.core.datastore.DownloadRecord
import com.videobridge.core.datastore.DownloadRecords
import com.videobridge.core.datastore.DownloadState
import com.videobridge.core.model.Video
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** A place downloads can be saved: the device's own storage, or a plugged-in USB drive / SD card. */
data class StorageOption(
    /** Stable for a given drive on a given device. */
    val id: String,
    val isRemovable: Boolean,
    /** False when a drive that was chosen earlier is not plugged in now. */
    val isConnected: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

sealed interface DownloadStatus {
    data object None : DownloadStatus

    /** [fraction] is 0..1, or null while the size is unknown. */
    data class Running(val fraction: Float?) : DownloadStatus

    /** Stopped by the user; [fraction] of the file is already on the device. */
    data class Paused(val fraction: Float?) : DownloadStatus

    data object Failed : DownloadStatus

    /** [uri] is a file on this device that Dekho's player can open. */
    data class Done(val uri: String) : DownloadStatus
}

enum class DownloadStart { STARTED, ALREADY_THERE, STORAGE_NOT_CONNECTED, NOT_ENOUGH_SPACE, NOT_DOWNLOADABLE }

/** Downloads as the screens see them (and as tests fake them). */
interface Downloads {
    fun storageOptions(): List<StorageOption>

    suspend fun start(video: Video): DownloadStart

    /** Stops the download and keeps what was fetched. */
    suspend fun pause(videoId: String)

    /** Continues a paused or failed download from where it stopped. */
    suspend fun resume(videoId: String)

    /** Stops the download if running and deletes the file. */
    suspend fun remove(videoId: String)

    fun statuses(): Flow<Map<String, DownloadStatus>>
}

/**
 * Dekho's own downloader: the device fetches the file straight from the source (never through
 * our backend) with a background worker that can be stopped and continued. Files go to this
 * app's folder on the chosen storage, which needs no permission and works for USB drives; they
 * are removed if the app is uninstalled.
 */
@Singleton
class DownloadsManager
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val settings: DeviceSettings,
    private val records: DownloadRecords,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : Downloads {
    /** Everything the system currently has mounted for this app; index 0 is internal storage. */
    override fun storageOptions(): List<StorageOption> =
        context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).filterNotNull().mapIndexed { index, dir ->
            val mounted = Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED
            val stat = if (mounted) runCatching { StatFs(dir.path) }.getOrNull() else null
            StorageOption(
                id = dir.path,
                isRemovable = index > 0,
                isConnected = mounted,
                freeBytes = stat?.availableBytes ?: 0,
                totalBytes = stat?.totalBytes ?: 0,
            )
        }

    /** The chosen storage, or internal storage when none was chosen. Null if it is unplugged. */
    private suspend fun targetDirectory(): File? {
        val options = storageOptions()
        val chosen = settings.settings.first().storageId
        val option = options.firstOrNull { it.id == chosen } ?: if (chosen == null) options.firstOrNull() else null
        return option?.takeIf { it.isConnected }?.let { File(it.id) }
    }

    override suspend fun start(video: Video): DownloadStart = withContext(ioDispatcher) {
        val existing = records.records.first().firstOrNull { it.videoId == video.id }
        if (existing != null && existing.state != DownloadState.FAILED) return@withContext DownloadStart.ALREADY_THERE
        // Streams are playlists of many small files, not one file that can be saved.
        if (video.format == "HLS" || video.format == "DASH") return@withContext DownloadStart.NOT_DOWNLOADABLE
        val directory = targetDirectory() ?: return@withContext DownloadStart.STORAGE_NOT_CONNECTED
        val needed = video.sizeBytes
        val free = runCatching { StatFs(directory.path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        if (needed != null && free < needed + SPACE_RESERVE_BYTES) return@withContext DownloadStart.NOT_ENOUGH_SPACE

        val path = File(directory, fileName(video)).path
        records.update(video.id) {
            DownloadRecord(video.id, video.sourceUrl, video.title, path, video.sizeBytes, DownloadState.RUNNING)
        }
        enqueue(video.id)
        DownloadStart.STARTED
    }

    private fun enqueue(videoId: String) {
        val request =
            OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(DownloadWorker.KEY_VIDEO_ID to videoId))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_SECONDS, TimeUnit.SECONDS)
                .build()
        // REPLACE: a resume must not be swallowed by a worker that is still winding down.
        WorkManager.getInstance(context).enqueueUniqueWork(DownloadWorker.workName(videoId), ExistingWorkPolicy.REPLACE, request)
    }

    override suspend fun pause(videoId: String) {
        records.update(videoId) { it?.takeIf { record -> record.state == DownloadState.RUNNING }?.copy(state = DownloadState.PAUSED) }
        WorkManager.getInstance(context).cancelUniqueWork(DownloadWorker.workName(videoId))
    }

    override suspend fun resume(videoId: String) {
        var resumed = false
        records.update(videoId) { record ->
            record
                ?.takeIf { it.state == DownloadState.PAUSED || it.state == DownloadState.FAILED }
                ?.copy(state = DownloadState.RUNNING)
                ?.also { resumed = true }
        }
        if (resumed) enqueue(videoId)
    }

    override suspend fun remove(videoId: String) {
        withContext(ioDispatcher) {
            WorkManager.getInstance(context).cancelUniqueWork(DownloadWorker.workName(videoId))
            records.records.first().firstOrNull { it.videoId == videoId }?.let { record ->
                File(record.path).delete()
                File(record.path + DownloadWorker.PART_SUFFIX).delete()
            }
            records.remove(videoId)
        }
    }

    /** Live status of every download, refreshed about once a second while collected. */
    override fun statuses(): Flow<Map<String, DownloadStatus>> = flow {
        while (true) {
            emit(records.records.first().associate { it.videoId to statusOf(it) })
            delay(REFRESH_MS)
        }
    }.flowOn(ioDispatcher)

    private fun statusOf(record: DownloadRecord): DownloadStatus {
        val fraction =
            record.totalBytes?.takeIf { it > 0 }?.let { total ->
                (File(record.path + DownloadWorker.PART_SUFFIX).length().toFloat() / total).coerceIn(0f, 1f)
            }
        return when (record.state) {
            DownloadState.RUNNING -> DownloadStatus.Running(fraction)

            DownloadState.PAUSED -> DownloadStatus.Paused(fraction)

            DownloadState.FAILED -> DownloadStatus.Failed

            // A finished file on a drive that was unplugged simply isn't there right now.
            DownloadState.DONE ->
                File(record.path).takeIf { it.exists() }?.let { DownloadStatus.Done(Uri.fromFile(it).toString()) }
                    ?: DownloadStatus.Failed
        }
    }

    private fun fileName(video: Video): String {
        val safe =
            video.title
                .replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .trim('.')
                .take(MAX_NAME_LENGTH)
                .ifBlank { "video" }
        val extension = video.format?.lowercase()?.takeIf { it.all(Char::isLetterOrDigit) } ?: "mp4"
        // The id keeps two videos with the same name from overwriting each other.
        return "$safe ${video.id.takeLast(ID_SUFFIX_LENGTH)}.$extension"
    }

    private companion object {
        const val REFRESH_MS = 1_000L
        const val RETRY_SECONDS = 15L
        const val SPACE_RESERVE_BYTES = 50L * 1024 * 1024
        const val MAX_NAME_LENGTH = 80
        const val ID_SUFFIX_LENGTH = 6
    }
}
