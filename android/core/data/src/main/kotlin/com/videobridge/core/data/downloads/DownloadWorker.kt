package com.videobridge.core.data.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.videobridge.core.common.IoDispatcher
import com.videobridge.core.datastore.DownloadRecords
import com.videobridge.core.datastore.DownloadState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads one video straight from its source into a `.part` file, renamed when complete.
 * Pausing cancels this work and keeps the `.part`; resuming starts new work that continues it.
 * A dropped connection ends in `retry`, so WorkManager runs it again when the network is back.
 */
@HiltWorker
class DownloadWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val records: DownloadRecords,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val record = records.records.first().firstOrNull { it.videoId == videoId } ?: return Result.failure()
        if (record.state != DownloadState.RUNNING) return Result.success()
        showNotification(record.title)

        val target = File(record.path)
        val part = File(record.path + PART_SUFFIX)
        return withContext(ioDispatcher) {
            try {
                target.parentFile?.mkdirs()
                val outcome =
                    downloadInto(CLIENT, record.url, part, isStopped = { isStopped }) { total ->
                        // Written once, so the card can show a percentage.
                        if (record.totalBytes !=
                            total
                        ) {
                            kotlinx.coroutines.runBlocking { records.update(videoId) { it?.copy(totalBytes = total) } }
                        }
                    }
                when (outcome) {
                    DownloadOutcome.Completed -> {
                        if (!part.renameTo(target)) throw IOException("could not finish the file")
                        records.update(videoId) { it?.copy(state = DownloadState.DONE, totalBytes = target.length()) }
                        Result.success()
                    }

                    // Paused or removed by the user; the record already says so.
                    DownloadOutcome.Stopped -> Result.success()

                    is DownloadOutcome.Refused -> {
                        records.update(videoId) { it?.copy(state = DownloadState.FAILED) }
                        Result.failure()
                    }
                }
            } catch (_: IOException) {
                // Network dropped or the drive was pulled: keep the bytes and try again later.
                if (runAttemptCount >= MAX_ATTEMPTS) {
                    records.update(videoId) { it?.copy(state = DownloadState.FAILED) }
                    Result.failure()
                } else {
                    Result.retry()
                }
            }
        }
    }

    /** A foreground notification keeps the system from stopping a long download. */
    private suspend fun showNotification(title: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val notification =
            NotificationCompat
                .Builder(applicationContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOngoing(true)
                .setProgress(0, 0, true)
                .build()
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(id.hashCode(), notification)
            }
        try {
            setForeground(info)
        } catch (_: IllegalStateException) {
            // Started while the app is in the background on Android 12+: the download still
            // runs, just without the guarantee; it resumes when the app is next opened.
        }
    }

    companion object {
        const val KEY_VIDEO_ID = "videoId"
        const val PART_SUFFIX = ".part"
        private const val CHANNEL = "downloads"
        private const val MAX_ATTEMPTS = 20
        private const val TIMEOUT_SECONDS = 30L

        // No account headers: the source server learns nothing about the Dekho account.
        private val CLIENT =
            OkHttpClient
                .Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()

        fun workName(videoId: String) = "download-$videoId"
    }
}
