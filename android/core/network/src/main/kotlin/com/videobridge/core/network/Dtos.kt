package com.videobridge.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class HealthDto(val status: String, val env: String, val version: String, val time: String)

@Serializable
internal data class ErrorEnvelopeDto(val error: ErrorDto)

@Serializable
internal data class ErrorDto(val code: String, val message: String, val details: JsonObject? = null, val requestId: String? = null)
