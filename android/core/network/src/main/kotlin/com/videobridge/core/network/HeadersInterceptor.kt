package com.videobridge.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/** Adds the headers every backend request carries (conventions §7). */
class HeadersInterceptor(private val config: NetworkConfig) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val request =
            original
                .newBuilder()
                .header(HEADER_APP_VERSION, config.appVersion)
                .header(HEADER_PLATFORM, config.platform)
                .apply {
                    if (original.header(HEADER_REQUEST_ID) == null) {
                        header(HEADER_REQUEST_ID, UUID.randomUUID().toString())
                    }
                }.build()
        return chain.proceed(request)
    }

    companion object {
        const val HEADER_APP_VERSION = "X-App-Version"
        const val HEADER_PLATFORM = "X-Platform"
        const val HEADER_REQUEST_ID = "X-Request-Id"
    }
}
