package com.videobridge.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Small bookkeeping values (later: sync cursors). Product tables arrive with their phases. */
@Entity(tableName = "key_value")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String,
    /** Epoch milliseconds, UTC. */
    val updatedAt: Long,
)
