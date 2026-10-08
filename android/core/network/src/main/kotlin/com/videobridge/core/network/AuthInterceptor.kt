package com.videobridge.core.network

import okhttp3.Interceptor
import okhttp3.Response

/** Attaches the access token. Sign-in endpoints go out without one. */
class AuthInterceptor(private val tokens: AuthTokenSource) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = tokens.accessToken()
        if (token == null || request.header(AUTHORIZATION) != null || isPublic(request.url.encodedPath)) {
            return chain.proceed(request)
        }
        return chain.proceed(request.newBuilder().header(AUTHORIZATION, "Bearer $token").build())
    }

    companion object {
        const val AUTHORIZATION = "Authorization"
        private val PUBLIC_SUFFIXES =
            listOf("/auth/register", "/auth/login", "/auth/refresh", "/health", "/health/ready")

        // A signed-out TV starts and polls pairing; a stale token must not ride along.
        fun isPublic(path: String): Boolean = PUBLIC_SUFFIXES.any(path::endsWith) || "/pairing/sessions" in path
    }
}
