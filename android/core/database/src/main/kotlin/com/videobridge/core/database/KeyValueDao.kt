package com.videobridge.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface KeyValueDao {
    @Upsert
    suspend fun upsert(entity: KeyValueEntity)

    @Query("SELECT * FROM key_value WHERE `key` = :key")
    suspend fun get(key: String): KeyValueEntity?

    @Query("SELECT * FROM key_value WHERE `key` = :key")
    fun observe(key: String): Flow<KeyValueEntity?>

    @Query("DELETE FROM key_value WHERE `key` = :key")
    suspend fun delete(key: String)
}
