package com.videobridge.core.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import javax.inject.Inject

/** Decodes `{ "error": { code, message, details?, requestId? } }` into an [ApiException]. */
class ApiErrorParser
@Inject
constructor(private val json: Json) {
    fun parse(httpStatus: Int, body: String?): ApiException {
        val error =
            body?.takeIf { it.isNotBlank() }?.let {
                try {
                    json.decodeFromString<ErrorEnvelopeDto>(it).error
                } catch (_: SerializationException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        return if (error != null) {
            ApiException(error.code, error.message, httpStatus, error.details)
        } else {
            ApiException(
                ApiException.CODE_UNKNOWN,
                "Unexpected response from the server ($httpStatus).",
                httpStatus,
            )
        }
    }

    fun parse(exception: HttpException): ApiException = parse(exception.code(), exception.response()?.errorBody()?.string())
}
