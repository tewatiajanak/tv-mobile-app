package com.videobridge.core.data.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** A video app installed on this device, as offered in Settings. */
data class VideoApp(val packageName: String, val label: String)

/** The video apps on this device (faked in tests). */
fun interface InstalledVideoApps {
    fun installed(): List<VideoApp>
}

/** Finds the apps on this device that say they can play a video link. */
class VideoApps
@Inject
constructor(@ApplicationContext private val context: Context) : InstalledVideoApps {
    override fun installed(): List<VideoApp> {
        val probe = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("https://example.com/video.mp4"), "video/*")
        return context.packageManager
            .queryIntentActivities(probe, 0)
            .map { VideoApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
