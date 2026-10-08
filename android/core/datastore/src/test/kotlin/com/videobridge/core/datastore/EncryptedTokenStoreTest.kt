package com.videobridge.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EncryptedTokenStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val sample =
        StoredSession(
            accessToken = "access-token-value",
            refreshToken = "vbr_refresh-token-value",
            userId = "u1",
            deviceId = "d1",
            sessionId = "s1",
            displayName = "Janak",
            phoneMasked = "+91******3210",
        )

    // The same AEAD as production, minus the Android Keystore (not available on the JVM).
    private fun newAead(): Aead {
        AeadConfig.register()
        return KeysetHandle
            .generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    private fun newDataStore(name: String): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { tmp.newFile("$name.preferences_pb").also { it.delete() } }

    @Test
    fun `saves and reads back a session`() = runTest {
        val store = EncryptedTokenStore(newDataStore("a"), newAead())

        assertNull(store.session.first())
        store.save(sample)

        assertEquals(sample, store.session.first())
    }

    @Test
    fun `nothing readable is written to disk`() = runTest {
        val dataStore = newDataStore("b")
        EncryptedTokenStore(dataStore, newAead()).save(sample)

        val raw = dataStore.data.first()[stringPreferencesKey("auth_session")].orEmpty()

        assertFalse(raw.isEmpty())
        listOf("access-token-value", "vbr_refresh", "Janak").forEach { assertFalse(raw.contains(it)) }
    }

    @Test
    fun `clear signs out`() = runTest {
        val store = EncryptedTokenStore(newDataStore("c"), newAead())
        store.save(sample)

        store.clear()

        assertNull(store.session.first())
    }

    @Test
    fun `a blob that cannot be decrypted reads as signed out`() = runTest {
        val dataStore = newDataStore("d")
        EncryptedTokenStore(dataStore, newAead()).save(sample)

        // A different key, as after a restore onto another device.
        assertNull(EncryptedTokenStore(dataStore, newAead()).session.first())

        dataStore.edit { it[stringPreferencesKey("auth_session")] = "not base64 !!" }
        assertNull(EncryptedTokenStore(dataStore, newAead()).session.first())
    }
}
