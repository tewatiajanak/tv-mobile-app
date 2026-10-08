package com.videobridge.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Which app plays a video. */
enum class PlayerChoice {
    /** Dekho's own player: remembers where you stopped. */
    DEKHO,

    /** Another video app installed on the device. */
    OTHER_APP,
}

/** What opening a video does about downloading. */
enum class DownloadMode {
    /** Just play. Downloading is a separate, explicit action on the card. */
    MANUAL,

    /** Ask each time: play or download. */
    ASK,

    /** Play, and also start downloading it if it isn't downloaded yet. */
    ALWAYS,
}

data class AppSettings(
    val player: PlayerChoice = PlayerChoice.DEKHO,
    /** With [PlayerChoice.OTHER_APP]: the chosen app's package, or null to let the device ask. */
    val playerPackage: String? = null,
    val downloadMode: DownloadMode = DownloadMode.MANUAL,
    /** Id of the chosen storage (see StorageOption); null means "use internal storage". */
    val storageId: String? = null,
)

enum class DownloadState { RUNNING, PAUSED, FAILED, DONE }

/** One download this device knows about. The file itself is at [path] (or `path.part` until done). */
@Serializable
data class DownloadRecord(
    val videoId: String,
    val url: String,
    val title: String,
    val path: String,
    val totalBytes: Long? = null,
    val state: DownloadState = DownloadState.RUNNING,
)

/** The device settings as the rest of the app sees them (and as tests fake them). */
interface DeviceSettings {
    val settings: Flow<AppSettings>

    suspend fun setPlayer(value: PlayerChoice, packageName: String? = null)

    suspend fun setDownloadMode(value: DownloadMode)

    suspend fun setStorageId(value: String)
}

/** The list of downloads, kept across restarts. */
interface DownloadRecords {
    val records: Flow<List<DownloadRecord>>

    /** Adds the record, or changes the one with the same video id. Returns null to leave it be. */
    suspend fun update(videoId: String, change: (DownloadRecord?) -> DownloadRecord?)

    suspend fun remove(videoId: String)
}

/**
 * Non-secret state of this device: settings (per device on purpose — a TV and a phone have
 * different storage and players) and the list of downloads. Tokens never go in this store.
 */
@Singleton
class AppSettingsDataStore
@Inject
constructor(private val dataStore: DataStore<Preferences>) :
    DeviceSettings,
    DownloadRecords {
    private val json = Json { ignoreUnknownKeys = true }
    private val recordList = ListSerializer(DownloadRecord.serializer())

    override val settings: Flow<AppSettings> =
        dataStore.data.map { prefs ->
            AppSettings(
                player = prefs[PLAYER].toEnum(PlayerChoice.DEKHO),
                playerPackage = prefs[PLAYER_PACKAGE]?.takeIf { it.isNotEmpty() },
                downloadMode = prefs[DOWNLOAD_MODE].toEnum(DownloadMode.MANUAL),
                storageId = prefs[STORAGE_ID],
            )
        }

    override suspend fun setPlayer(value: PlayerChoice, packageName: String?) {
        dataStore.edit {
            it[PLAYER] = value.name
            it[PLAYER_PACKAGE] = packageName.orEmpty()
        }
    }

    override suspend fun setDownloadMode(value: DownloadMode) {
        dataStore.edit { it[DOWNLOAD_MODE] = value.name }
    }

    override suspend fun setStorageId(value: String) {
        dataStore.edit { it[STORAGE_ID] = value }
    }

    override val records: Flow<List<DownloadRecord>> = dataStore.data.map { decode(it[DOWNLOADS]) }

    override suspend fun update(videoId: String, change: (DownloadRecord?) -> DownloadRecord?) {
        // edit() is atomic, so the worker and the screen cannot overwrite each other's change.
        dataStore.edit { prefs ->
            val current = decode(prefs[DOWNLOADS])
            val changed = change(current.firstOrNull { it.videoId == videoId }) ?: return@edit
            prefs[DOWNLOADS] = json.encodeToString(recordList, current.filter { it.videoId != videoId } + changed)
        }
    }

    override suspend fun remove(videoId: String) {
        dataStore.edit { prefs ->
            prefs[DOWNLOADS] = json.encodeToString(recordList, decode(prefs[DOWNLOADS]).filter { it.videoId != videoId })
        }
    }

    private fun decode(raw: String?): List<DownloadRecord> = try {
        raw?.let { json.decodeFromString(recordList, it) }.orEmpty()
    } catch (_: SerializationException) {
        emptyList()
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T = enumValues<T>().firstOrNull { it.name == this } ?: default

    private companion object {
        val PLAYER = stringPreferencesKey("player")
        val PLAYER_PACKAGE = stringPreferencesKey("player_package")
        val DOWNLOAD_MODE = stringPreferencesKey("download_mode")
        val STORAGE_ID = stringPreferencesKey("storage_id")
        val DOWNLOADS = stringPreferencesKey("download_records")
    }
}
