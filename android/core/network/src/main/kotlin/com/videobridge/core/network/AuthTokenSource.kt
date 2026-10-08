package com.videobridge.core.network

/**
 * What the HTTP layer needs from whoever owns the session (core:data). All calls come from
 * OkHttp threads and must return quickly.
 */
interface AuthTokenSource {
    fun accessToken(): String?

    fun refreshToken(): String?

    fun onTokensRefreshed(accessToken: String, refreshToken: String)

    /** The backend says this sign-in is over (signed out elsewhere, removed, expired). */
    fun onSessionEnded()
}
