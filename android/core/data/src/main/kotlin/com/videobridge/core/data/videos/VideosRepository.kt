package com.videobridge.core.data.videos

import com.videobridge.core.common.AppResult
import com.videobridge.core.common.IoDispatcher
import com.videobridge.core.common.map
import com.videobridge.core.data.apiCall
import com.videobridge.core.data.requireSuccess
import com.videobridge.core.model.Video
import com.videobridge.core.network.ApiErrorParser
import com.videobridge.core.network.CreateVideoRequestDto
import com.videobridge.core.network.PlaybackRequestDto
import com.videobridge.core.network.VideoDto
import com.videobridge.core.network.VideosApi
import kotlinx.coroutines.CoroutineDispatcher
import java.util.UUID
import javax.inject.Inject

interface VideosRepository {
    suspend fun list(): AppResult<List<Video>>

    suspend fun add(url: String, title: String? = null): AppResult<Video>

    suspend fun remove(id: String): AppResult<Unit>

    suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?): AppResult<Unit>
}

class DefaultVideosRepository
@Inject
constructor(
    private val api: VideosApi,
    private val errorParser: ApiErrorParser,
    private val probe: VideoProbe,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : VideosRepository {
    override suspend fun list(): AppResult<List<Video>> = apiCall(ioDispatcher, errorParser) {
        api.list()
    }.map { it.items.map(VideoDto::toModel) }

    override suspend fun add(url: String, title: String?): AppResult<Video> {
        // Size and format are for display only, so a failed look-up never blocks saving.
        val info = probe.inspect(url.trim())
        // The id is made here so that a retry after a lost reply cannot save the link twice.
        val request =
            CreateVideoRequestDto(
                id = UUID.randomUUID().toString(),
                sourceUrl = url.trim(),
                title = title?.trim()?.takeIf { it.isNotEmpty() },
                sizeBytes = info.sizeBytes,
                format = info.format,
                thumbnailUrl = info.thumbnailUrl,
            )
        return apiCall(ioDispatcher, errorParser) { api.create(request) }.map(VideoDto::toModel)
    }

    override suspend fun remove(id: String): AppResult<Unit> = apiCall(ioDispatcher, errorParser) { api.remove(id).requireSuccess() }

    override suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?): AppResult<Unit> =
        apiCall(ioDispatcher, errorParser) {
            api.savePlayback(id, PlaybackRequestDto(positionMs.coerceAtLeast(0), durationMs?.takeIf { it > 0 })).requireSuccess()
        }
}

private fun VideoDto.toModel() = Video(id, title, sourceUrl, sourceDomain, sizeBytes, format, positionMs, durationMs, thumbnailUrl)
