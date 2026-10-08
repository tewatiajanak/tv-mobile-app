package com.videobridge.core.datastore

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Everything needed to stay signed in and to show who is signed in while offline. */
@Serializable
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val userId: String,
    val deviceId: String,
    val sessionId: String,
    val displayName: String,
    val phoneMasked: String,
)

interface TokenStore {
    val session: Flow<StoredSession?>

    suspend fun save(session: StoredSession)

    suspend fun clear()
}
