package com.videobridge.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class CreatePairingRequestDto(val tv: DeviceRequestDto)

@Serializable
data class PairingSessionDto(
    val pairingId: String,
    val code: String,
    val qrPayload: String,
    val pollToken: String,
    val expiresInSeconds: Int,
    val pollIntervalMs: Long,
)

@Serializable
data class PairingStatusDto(val status: String, val auth: AuthResponseDto? = null)

@Serializable
data class ClaimRequestDto(val code: String)

@Serializable
data class ClaimedTvDto(val name: String, val manufacturer: String? = null, val model: String? = null)

@Serializable
data class ClaimResponseDto(val pairingId: String, val tv: ClaimedTvDto)

@Serializable
data class ApproveRequestDto(val name: String? = null)

@Serializable
data class DeviceDto(
    val id: String,
    val type: String,
    val name: String,
    val model: String? = null,
    val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class ApproveResponseDto(val device: DeviceDto)

@Serializable
data class DeviceListDto(val items: List<DeviceDto>)

@Serializable
data class RenameDeviceRequestDto(val name: String)

interface PairingApi {
    @POST("api/v1/pairing/sessions")
    suspend fun create(@Body body: CreatePairingRequestDto): PairingSessionDto

    @GET("api/v1/pairing/sessions/{id}/status")
    suspend fun status(@Path("id") id: String, @Header("X-Pairing-Poll-Token") pollToken: String): PairingStatusDto

    @POST("api/v1/pairing/claim")
    suspend fun claim(@Body body: ClaimRequestDto): ClaimResponseDto

    @POST("api/v1/pairing/{id}/approve")
    suspend fun approve(@Path("id") id: String, @Body body: ApproveRequestDto): ApproveResponseDto

    @POST("api/v1/pairing/{id}/reject")
    suspend fun reject(@Path("id") id: String): Response<Unit>
}

interface DevicesApi {
    @GET("api/v1/devices")
    suspend fun list(): DeviceListDto

    @PATCH("api/v1/devices/{id}")
    suspend fun rename(@Path("id") id: String, @Body body: RenameDeviceRequestDto): DeviceDto

    @DELETE("api/v1/devices/{id}")
    suspend fun remove(@Path("id") id: String): Response<Unit>
}
