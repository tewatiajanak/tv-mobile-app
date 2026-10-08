package com.videobridge.core.network

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit

private const val CONNECT_TIMEOUT_SECONDS = 15L
private const val READ_TIMEOUT_SECONDS = 30L

/** Unknown fields are ignored so an older app keeps working against a newer backend. */
fun createJson(): Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val QUERY_STRING = Regex("""\?\S*""")

/** Query strings often carry signed tokens; they are cut from every logged line. */
internal fun stripQueryStrings(message: String): String = QUERY_STRING.replace(message, "")

/**
 * [auth] is null only for the client that performs the token refresh itself, which must not
 * carry the authenticator (it would call itself).
 */
fun createOkHttpClient(config: NetworkConfig, auth: Pair<AuthTokenSource, RefreshApi>? = null): OkHttpClient = OkHttpClient
    .Builder()
    .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .addInterceptor(HeadersInterceptor(config))
    .apply {
        if (auth != null) {
            addInterceptor(AuthInterceptor(auth.first))
            authenticator(TokenAuthenticator(auth.first, auth.second))
        }
    }.apply {
        if (config.logRequests) {
            val logging =
                HttpLoggingInterceptor { message -> Timber.tag("http").d(stripQueryStrings(message)) }.apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                    redactHeader("Authorization")
                    redactHeader("Cookie")
                    redactHeader("Set-Cookie")
                }
            addInterceptor(logging)
        }
    }.build()

fun createRetrofit(baseUrl: String, client: OkHttpClient, json: Json): Retrofit = Retrofit
    .Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
    .build()
