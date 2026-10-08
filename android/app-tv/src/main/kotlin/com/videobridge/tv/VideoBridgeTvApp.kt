package com.videobridge.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.videobridge.core.common.initLogging
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * WorkManager is initialised on demand with Hilt's factory (the default initializer is removed
 * in the manifest), so workers can have injected dependencies.
 */
@HiltAndroidApp
class VideoBridgeTvApp :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        initLogging(BuildConfig.DEBUG)
    }
}
