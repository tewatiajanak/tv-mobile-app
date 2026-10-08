package com.videobridge.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A random id for this app install, created on first use. The backend keys devices on it. */
@Singleton
class InstallIdProvider
@Inject
constructor(private val dataStore: DataStore<Preferences>) {
    suspend fun get(): String {
        var result = ""
        dataStore.edit { prefs ->
            result = prefs[KEY] ?: UUID.randomUUID().toString().also { prefs[KEY] = it }
        }
        return result
    }

    private companion object {
        val KEY = stringPreferencesKey("install_id")
    }
}
