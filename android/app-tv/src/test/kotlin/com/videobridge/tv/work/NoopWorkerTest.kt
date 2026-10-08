package com.videobridge.tv.work

import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.videobridge.tv.VideoBridgeTvApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Runs a @HiltWorker through WorkManager using the application's own Configuration. */
@RunWith(RobolectricTestRunner::class)
class NoopWorkerTest {
    @Test
    fun `WorkManager creates workers through the Hilt factory`() {
        val app = ApplicationProvider.getApplicationContext<VideoBridgeTvApp>()
        val configuration =
            Configuration
                .Builder()
                .setWorkerFactory(app.workManagerConfiguration.workerFactory)
                .setExecutor(SynchronousExecutor())
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, configuration)
        val workManager = WorkManager.getInstance(app)
        val request = OneTimeWorkRequestBuilder<NoopWorker>().build()

        workManager.enqueue(request).result.get()

        assertEquals(WorkInfo.State.SUCCEEDED, workManager.getWorkInfoById(request.id).get()?.state)
    }
}
