package com.videobridge.core.network

import retrofit2.Call
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApi {
    @POST("api/v1/auth/register")
    suspend fun register(@Body body: RegisterRequestDto): AuthResponseDto

    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequestDto): AuthResponseDto

    @POST("api/v1/auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("api/v1/users/me")
    suspend fun me(): UserDto
}

/**
 * Served by a client without the authenticator, and synchronous: it is called from inside
 * OkHttp's authenticator, which must never re-enter the authenticated client.
 */
interface RefreshApi {
    @POST("api/v1/auth/refresh")
    fun refresh(@Body body: RefreshRequestDto): Call<TokenPairDto>
}
