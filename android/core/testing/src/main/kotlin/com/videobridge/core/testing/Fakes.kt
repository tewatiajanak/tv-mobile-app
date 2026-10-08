package com.videobridge.core.testing

import com.videobridge.core.common.AppResult
import com.videobridge.core.data.HealthRepository
import com.videobridge.core.model.HealthStatus
import com.videobridge.core.network.HealthApi
import com.videobridge.core.network.HealthDto
import kotlinx.coroutines.CompletableDeferred

val okHealthStatus =
    HealthStatus(status = "ok", env = "development", version = "0.1.0", time = "2026-10-07T16:26:52.123Z")

class FakeHealthApi(var response: () -> HealthDto = { HealthDto("ok", "development", "0.1.0", "2026-10-07T16:26:52.123Z") }) : HealthApi {
    var calls = 0
        private set

    override suspend fun health(): HealthDto {
        calls++
        return response()
    }
}

/**
 * Answers each check() with the next queued result. With nothing queued the call suspends until
 * [complete] is called, which lets a test observe the Loading state.
 */
class FakeHealthRepository : HealthRepository {
    private val queued = ArrayDeque<AppResult<HealthStatus>>()
    private var pending: CompletableDeferred<AppResult<HealthStatus>>? = null
    var calls = 0
        private set

    fun enqueue(result: AppResult<HealthStatus>) {
        queued.addLast(result)
    }

    fun complete(result: AppResult<HealthStatus>) {
        checkNotNull(pending) { "no check() is waiting" }.complete(result)
    }

    override suspend fun check(): AppResult<HealthStatus> {
        calls++
        queued.removeFirstOrNull()?.let { return it }
        return CompletableDeferred<AppResult<HealthStatus>>().also { pending = it }.await()
    }
}
