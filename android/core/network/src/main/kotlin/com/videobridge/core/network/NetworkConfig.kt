package com.videobridge.core.network

/** Supplied by each app from its BuildConfig, so this module knows nothing about flavors. */
data class NetworkConfig(
    /** Must end with "/". */
    val apiBaseUrl: String,
    val wsUrl: String,
    val appVersion: String,
    /** "android-phone" or "android-tv". */
    val platform: String,
    /** Request logging (method, path, status) is on only in dev builds. */
    val logRequests: Boolean,
)
