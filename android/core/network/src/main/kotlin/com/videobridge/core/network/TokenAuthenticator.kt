package com.videobridge.core.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_CONFLICT = 409

/**
 * Runs when an authenticated request gets a 401: refreshes the tokens once and retries, so the
 * user never sees an expired access token. Whether a token is expired is decided by the server's
 * 401, never by the device clock (TV clocks are often wrong).
 */
class TokenAuthenticator(private val tokens: AuthTokenSource, private val refreshApi: RefreshApi) : Authenticator {
    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        val failedToken = response.request.header(AuthInterceptor.AUTHORIZATION)?.removePrefix("Bearer ")
        // No token was sent, or this is already the retry: give up rather than loop.
        if (failedToken == null || response.priorResponse != null) {
            return null
        }
        // Single flight: parallel 401s queue here, and all but the first find fresh tokens waiting.
        val newToken =
            synchronized(lock) {
                val current = tokens.accessToken()
                when {
                    current == null -> null
                    current != failedToken -> current
                    else -> refresh(failedToken)
                }
            } ?: return null
        return response.request
            .newBuilder()
            .header(AuthInterceptor.AUTHORIZATION, "Bearer $newToken")
            .build()
    }

    private fun refresh(failedToken: String): String? {
        val refreshToken = tokens.refreshToken() ?: return null
        val result =
            try {
                refreshApi.refresh(RefreshRequestDto(refreshToken)).execute()
            } catch (_: IOException) {
                // Offline: stay signed in and let the original request fail as a network error.
                return null
            }
        val pair = result.body()
        return when {
            result.isSuccessful && pair != null -> {
                tokens.onTokensRefreshed(pair.accessToken, pair.refreshToken)
                pair.accessToken
            }

            result.code() == HTTP_UNAUTHORIZED -> {
                tokens.onSessionEnded()
                null
            }

            // REFRESH_RACE: another request of this app just rotated the token.
            result.code() == HTTP_CONFLICT -> tokens.accessToken()?.takeIf { it != failedToken }

            else -> null
        }
    }
}
