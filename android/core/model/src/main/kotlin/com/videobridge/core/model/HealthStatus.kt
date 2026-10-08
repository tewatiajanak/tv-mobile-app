package com.videobridge.core.model

/** What the backend's liveness endpoint reports. */
data class HealthStatus(val status: String, val env: String, val version: String, val time: String) {
    val isOk: Boolean get() = status == "ok"
}
