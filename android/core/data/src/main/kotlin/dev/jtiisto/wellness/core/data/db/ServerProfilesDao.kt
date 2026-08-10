package dev.jtiisto.wellness.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The address book's storage.
 *
 * Ascending id order everywhere: it is insertion order, it never changes under
 * the user while they are looking at it, and any "smarter" ordering — by
 * nickname, by last used — would move rows around a list whose whole purpose is
 * to be tapped accurately. Duplicate nicknames and duplicate URLs are both
 * allowed; two profiles pointing at the same host with different names is a
 * reasonable thing to want, and de-duplicating would mean guessing which one
 * the user meant to keep.
 */
@Dao
abstract class ServerProfilesDao {

    @Query("SELECT * FROM server_profiles ORDER BY id")
    abstract fun observeAll(): Flow<List<ServerProfileEntity>>

    @Query("SELECT * FROM server_profiles ORDER BY id")
    abstract suspend fun listAll(): List<ServerProfileEntity>

    @Query("SELECT * FROM server_profiles WHERE id = :id")
    abstract suspend fun find(id: Long): ServerProfileEntity?

    /**
     * Every active row, not the first one.
     *
     * Boot resolution has to be able to tell "one" from "more than one" — the
     * second case is a corrupted invariant and must fail closed, which a
     * `LIMIT 1` would quietly hide by picking a winner.
     */
    @Query("SELECT * FROM server_profiles WHERE isActive = 1 ORDER BY id")
    abstract suspend fun listActive(): List<ServerProfileEntity>

    @Insert
    abstract suspend fun insert(row: ServerProfileEntity): Long

    @Query("UPDATE server_profiles SET nickname = :nickname, url = :url WHERE id = :id")
    abstract suspend fun rename(id: Long, nickname: String, url: String)

    @Query("DELETE FROM server_profiles WHERE id = :id")
    abstract suspend fun delete(id: Long)

    /**
     * Make [id] the active profile — **one statement**, deliberately.
     *
     * A clear-then-set pair would leave a window with no active row, and a
     * crash inside it would boot the app against the built-in server holding
     * the previous one's data. One `CASE` update can only commit or not, so the
     * at-most-one invariant holds across any failure.
     */
    @Query("UPDATE server_profiles SET isActive = CASE WHEN id = :id THEN 1 ELSE 0 END")
    abstract suspend fun activate(id: Long)

    /** Return to the built-in server: no row is active. */
    @Query("UPDATE server_profiles SET isActive = 0")
    abstract suspend fun clearActive()
}
