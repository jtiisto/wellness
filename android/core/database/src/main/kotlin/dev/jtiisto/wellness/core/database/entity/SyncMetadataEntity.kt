package dev.jtiisto.wellness.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey val moduleId: String,
    val clientId: String,
    val clientName: String,
    val lastServerSyncTime: String?,
    val registeredAt: String?,
)
