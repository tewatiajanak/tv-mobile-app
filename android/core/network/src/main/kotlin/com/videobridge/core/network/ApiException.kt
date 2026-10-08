package com.videobridge.core.network

import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** A non-2xx response decoded from the backend's error envelope. */
class ApiException(val code: String, override val message: String, val httpStatus: Int, val details: JsonObject? = null) :
    IOException(message) {
    companion object {
        /** Used when the body is not a valid envelope (proxy error page, empty body, ...). */
        const val CODE_UNKNOWN = "UNKNOWN"
    }
}
