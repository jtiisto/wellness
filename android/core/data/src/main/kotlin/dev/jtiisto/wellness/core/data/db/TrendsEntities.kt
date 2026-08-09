package dev.jtiisto.wellness.core.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The Trends tab's own view state — active range, active sub-screen, and the
 * last picked exercise/tracker/lab panel.
 *
 * Device-local by definition, exactly like `journal_meta`'s `ui.` keys: the
 * server has no concept of which chart you were looking at. Trends never
 * uploads anything at all, so there is no path from this table to a request
 * body.
 */
@Entity(tableName = "trends_meta")
data class TrendsMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)
