package com.videobridge.tv.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.videobridge.core.common.IoDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Does nothing. It exists to prove that WorkManager builds workers through Hilt: with the
 * default factory this class could not be instantiated, because of the injected dispatcher.
 */
@HiltWorker
class NoopWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(ioDispatcher) { Result.success() }
}
