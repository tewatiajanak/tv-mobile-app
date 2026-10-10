package com.videobridge.core.data.downloads

import android.content.Context
import android.content.Intent
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The apps on this device that can be handed a link to download (faked in tests). */
fun interface InstalledDownloadApps {
    fun installed(): List<VideoApp>
}

/**
 * Finds the apps on this device that open a plain web link to a file: download managers and
 * browsers. Android has no "download this" request, so this is the closest honest list.
 */
class DownloadApps
@Inject
constructor(@ApplicationContext private val context: Context) : InstalledDownloadApps {
    override fun installed(): List<VideoApp> {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/video.mp4")).addCategory(Intent.CATEGORY_BROWSABLE)
        return context.packageManager
            .queryIntentActivities(probe, 0)
            .map { VideoApp(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }
}
