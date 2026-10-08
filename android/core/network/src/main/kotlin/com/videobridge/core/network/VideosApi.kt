package com.videobridge.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

@Serializable
data class VideoDto(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val sourceDomain: String,
    val sizeBytes: Long? = null,
    val format: String? = null,
    val thumbnailUrl: String? = null,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
)

@Serializable
data class VideoListDto(val items: List<VideoDto>)

@Serializable
data class CreateVideoRequestDto(
    val id: String,
    val sourceUrl: String,
    val title: String? = null,
    val sizeBytes: Long? = null,
    val format: String? = null,
    val thumbnailUrl: String? = null,
)

@Serializable
data class PlaybackRequestDto(val positionMs: Long, val durationMs: Long? = null)

interface VideosApi {
    @GET("api/v1/videos")
    suspend fun list(): VideoListDto

    @POST("api/v1/videos")
    suspend fun create(@Body body: CreateVideoRequestDto): VideoDto

    @DELETE("api/v1/videos/{id}")
    suspend fun remove(@Path("id") id: String): Response<Unit>

    @PUT("api/v1/videos/{id}/playback")
    suspend fun savePlayback(@Path("id") id: String, @Body body: PlaybackRequestDto): Response<Unit>
}
