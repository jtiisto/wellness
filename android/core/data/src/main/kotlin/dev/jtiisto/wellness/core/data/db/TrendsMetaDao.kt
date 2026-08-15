package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Key/value storage for the Trends tab's preferences.
 *
 * An abstract class rather than an interface so [put] is real code a JVM fake
 * inherits — the tests then exercise the same write path the app does.
 */
@Dao
abstract class TrendsMetaDao {

    @Query("SELECT value FROM trends_meta WHERE key = :key")
    abstract fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM trends_meta WHERE key = :key")
    abstract suspend fun get(key: String): String?

    @Upsert
    abstract suspend fun upsert(row: TrendsMetaEntity)

    open suspend fun put(key: String, value: String) = upsert(TrendsMetaEntity(key, value))
}
