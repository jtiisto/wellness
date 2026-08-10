package dev.jtiisto.wellness.core.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One saved server, in the address book behind the Tools tab.
 *
 * **At most one row may have [isActive] set**, and that is an invariant the
 * schema cannot express — it is held by the one `CASE` update that ever writes
 * the flag (see [ServerProfilesDao.activate]) and re-checked at boot, which
 * fails closed rather than picking one arbitrarily. Getting it wrong would mean
 * uploading one server's records to another.
 *
 * No active row is a legitimate state, not a broken one: it means the built-in
 * `BuildConfig` server, which the list renders as an undeletable first entry.
 *
 * [url] is stored in plain text. These are tailnet addresses — reachable only
 * from inside the tailnet, carrying no credentials, and the app has no secret
 * store to put them in that would be any better protected than the database
 * they would be protecting.
 */
@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nickname: String,
    val url: String,
    @ColumnInfo(defaultValue = "0") val isActive: Boolean = false,
)
