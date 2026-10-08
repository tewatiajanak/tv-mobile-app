package com.videobridge.core.network

import retrofit2.http.GET

interface HealthApi {
    @GET("api/v1/health")
    suspend fun health(): HealthDto
}
