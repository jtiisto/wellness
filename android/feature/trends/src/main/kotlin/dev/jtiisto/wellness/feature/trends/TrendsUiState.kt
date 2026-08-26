package dev.jtiisto.wellness.feature.trends

import dev.jtiisto.wellness.core.data.trends.CardioDto
import dev.jtiisto.wellness.core.data.trends.CompositionDto
import dev.jtiisto.wellness.core.data.trends.ExerciseDetailDto
import dev.jtiisto.wellness.core.data.trends.ExerciseSummary
import dev.jtiisto.wellness.core.data.trends.LabsDto
import dev.jtiisto.wellness.core.data.trends.OverviewDto
import dev.jtiisto.wellness.core.data.trends.RecoveryDto
import dev.jtiisto.wellness.core.data.trends.SleepDebtDto
import dev.jtiisto.wellness.core.data.trends.TrackerDetailDto
import dev.jtiisto.wellness.core.data.trends.TrackerSummary
import dev.jtiisto.wellness.core.data.trends.TrendsPrefs
import dev.jtiisto.wellness.core.data.trends.VolumeDto
import dev.jtiisto.wellness.core.data.trends.WeightDto

/**
 * One fetched thing on a screen.
 *
 * Every slice goes back to [Loading] the moment its own fetch starts, which is
 * what stops 12-week data from being rendered under a toolbar that already says
 * 4w. [Ready] carries the freshness of the copy it holds rather than pointing
 * at a shared staleness map, so two screens reading the same cache key each
 * know the age of *their* copy.
 */
sealed interface Slice<out T> {

    data object Loading : Slice<Nothing>

    data class Error(val text: String) : Slice<Nothing>

    /**
     * Covariant like [Slice] itself, so smart-casting a `Slice<T>` to this
     * branch keeps `T` instead of widening it to a star projection.
     */
    data class Ready<out T>(val value: T, val staleFetchedAt: Long?) : Slice<T>
}

val <T> Slice<T>.valueOrNull: T?
    get() = (this as? Slice.Ready)?.value

/** The stamp a stale badge counts, or null when this slice is fresh or absent. */
val <T> Slice<T>.staleFetchedAt: Long?
    get() = (this as? Slice.Ready)?.staleFetchedAt

/** The oldest stamp among the slices a screen is actually rendering. */
fun staleStamps(vararg slices: Slice<*>): List<Long> = slices.mapNotNull { it.staleFetchedAt }

data class TrendsShellState(
    val range: String = TrendsPrefs.DEFAULT_RANGE,
    val screen: String = TrendsPrefs.DEFAULT_SCREEN,
)

data class OverviewUiState(
    val range: String = TrendsPrefs.DEFAULT_RANGE,
    val overview: Slice<OverviewDto> = Slice.Loading,
    val weight: Slice<WeightDto> = Slice.Loading,
)

data class StrengthUiState(
    val range: String = TrendsPrefs.DEFAULT_RANGE,
    val exercises: Slice<List<ExerciseSummary>> = Slice.Loading,
    val volume: Slice<VolumeDto> = Slice.Loading,
    val detail: Slice<ExerciseDetailDto> = Slice.Loading,
    val selected: String? = null,
    val showRpe: Boolean = true,
)

data class CardioUiState(
    val range: String = TrendsPrefs.DEFAULT_RANGE,
    val cardio: Slice<CardioDto> = Slice.Loading,
)

data class JournalTrendsUiState(
    val range: String = TrendsPrefs.DEFAULT_RANGE,
    val trackers: Slice<List<TrackerSummary>> = Slice.Loading,
    val detail: Slice<TrackerDetailDto> = Slice.Loading,
    val selected: String? = null,
)

data class HealthUiState(
    val range: String = TrendsPrefs.DEFAULT_RANGE,
    val recovery: Slice<RecoveryDto> = Slice.Loading,
    val sleep: Slice<SleepDebtDto> = Slice.Loading,
    val weight: Slice<WeightDto> = Slice.Loading,
    val composition: Slice<CompositionDto> = Slice.Loading,
    val labs: Slice<LabsDto> = Slice.Loading,
    val labPanel: String? = null,
)
