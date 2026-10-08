package com.videobridge.core.datastore

import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tokens are stored only as ciphertext (Tink AEAD; the key lives in the Android Keystore).
 * A blob that can no longer be decrypted (restored backup, wiped keystore) reads as "signed out".
 */
@Singleton
class EncryptedTokenStore
@Inject
constructor(private val dataStore: DataStore<Preferences>, private val aead: Aead) : TokenStore {
    private val json = Json { ignoreUnknownKeys = true }

    override val session: Flow<StoredSession?> = dataStore.data.map { prefs -> prefs[KEY]?.let(::decrypt) }

    override suspend fun save(session: StoredSession) {
        val plaintext = json.encodeToString(StoredSession.serializer(), session).toByteArray()
        val blob = Base64.encodeToString(aead.encrypt(plaintext, ASSOCIATED_DATA), Base64.NO_WRAP)
        dataStore.edit { it[KEY] = blob }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(KEY) }
    }

    private fun decrypt(blob: String): StoredSession? = try {
        val plaintext = aead.decrypt(Base64.decode(blob, Base64.NO_WRAP), ASSOCIATED_DATA)
        json.decodeFromString(StoredSession.serializer(), String(plaintext))
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        val KEY = stringPreferencesKey("auth_session")
        val ASSOCIATED_DATA = "videobridge.auth_session.v1".toByteArray()
    }
}
