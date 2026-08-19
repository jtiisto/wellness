package dev.jtiisto.wellness.feature.trends.chart

/**
 * What each series on each chart is drawn in — the whole Trends colour
 * vocabulary, in one pure file.
 *
 * Two rules govern it, and they are the round's own resolution:
 *
 * 1. **A judgment never takes a hue.** Met, partial, missed, below-floor,
 *    flagged, off-plan, paused, in-progress — every one answers in the ink ramp,
 *    so nothing on a chart can be read as good or bad by its colour.
 * 2. **A plate identifies a series, assigned positionally *per chart*.** The
 *    first extra series on a chart takes plate 1, the second plate 2, exactly as
 *    coach assigns a plate per workout — and a mandatory legend decodes it.
 *
 * Rule 2 is why this file is keyed by chart. [PlotTone] is a *role* vocabulary,
 * not a palette: eight charts spend [PlotTone.PRIMARY] or [PlotTone.ALT], and on
 * five of them they are a rolling mean (weight, value-vs-target, HRV, resting
 * HR, body) while on three they are the chart's own subject (progression's top
 * set, sleep's hours bars, the aerobic proxy's sessions). A single global
 * tone→style map cannot say both, so the default below says the common case and
 * a [ChartInk] plan says what a given chart actually draws. The builders keep
 * emitting tones and their tests keep pinning tones; only this layer decides
 * what a tone *looks like*.
 *
 * Everything here is pure and JVM-testable — inks are tokens rather than
 * colours, so a rule about what a series means never needs a device to assert.
 */

/** A palette token. Resolved to a real colour by `PlotColors` at draw time. */
enum class SeriesInk { INK, INK_SOFT, INK_FAINT, RULE, PLATE_1, PLATE_2, PLATE_3, INK_LID }

/**
 * How a series is marked on the page — and therefore in its legend.
 *
 * One enum for both because they must never disagree: the swatch beside a word
 * is a promise about what the reader will find above it.
 */
enum class SeriesMark {
    /** A solid polyline: a real series the chart is *of*. */
    LINE,

    /** A 7-day mean. Dashed, because a mean is an annotation of a series. */
    DASHED_LINE,

    /** A 28-day mean: the same statement, further back. */
    FAINT_DASHED_LINE,

    /** A filled point. */
    DOT,

    /**
     * A hollow point — a scan, or a reading the source itself flagged. The
     * journal's open dot, turned outward: judgment is a shape here, never a hue.
     */
    RING,

    /** A bar or a ribbon cell. */
    BAR,

    /** A band behind the series: a wash between two hairlines. */
    WASH,

    /** Not a series at all — a footnote in the legend, with no key to give. */
    NOTE,
}

data class SeriesStyle(val ink: SeriesInk, val mark: SeriesMark)

/**
 * The common case, for every chart that does not say otherwise.
 *
 * [PlotTone.PRIMARY] and [PlotTone.ALT] default to the rolling means they are on
 * five of their eight charts; the readings beneath them ([PlotTone.VALUE]) recede
 * further still, which is what lets a mean be read across a dense scatter.
 */
fun defaultSeriesStyle(tone: PlotTone): SeriesStyle = when (tone) {
    PlotTone.PRIMARY -> SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.DASHED_LINE)
    PlotTone.ALT -> SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.FAINT_DASHED_LINE)
    PlotTone.SECONDARY -> SeriesStyle(SeriesInk.PLATE_2, SeriesMark.DOT)
    // Scans and lab observations on their own strips: one series, so no colour.
    PlotTone.SCAN -> SeriesStyle(SeriesInk.INK, SeriesMark.LINE)
    PlotTone.VALUE -> SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.DOT)
    PlotTone.WARN -> SeriesStyle(SeriesInk.INK, SeriesMark.RING)
    PlotTone.MUTED -> SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.NOTE)
    PlotTone.BAND -> SeriesStyle(SeriesInk.RULE, SeriesMark.WASH)
    PlotTone.BAR -> SeriesStyle(SeriesInk.INK, SeriesMark.BAR)
    PlotTone.MET -> SeriesStyle(SeriesInk.INK, SeriesMark.BAR)
    PlotTone.PARTIAL -> SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.BAR)
    PlotTone.MISSED -> SeriesStyle(SeriesInk.RULE, SeriesMark.BAR)
    PlotTone.IN_PROGRESS -> SeriesStyle(SeriesInk.INK_LID, SeriesMark.BAR)
    PlotTone.STACK_0 -> SeriesStyle(SeriesInk.PLATE_1, SeriesMark.BAR)
    PlotTone.STACK_1 -> SeriesStyle(SeriesInk.PLATE_2, SeriesMark.BAR)
    PlotTone.STACK_2 -> SeriesStyle(SeriesInk.PLATE_3, SeriesMark.BAR)
    PlotTone.STACK_OTHER -> SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.BAR)
}

/**
 * The charts whose series are not the default ones.
 *
 * Every chart that carries a plate is named here even where the default already
 * lands right, so the set of coloured charts is a list somebody can read rather
 * than something to be inferred from a map. Each plan is **complete** for the
 * series that chart draws — which is what lets one test walk every plan and
 * prove no two series on a chart resolve to the same colour.
 *
 * Charts absent from this enum spend no colour at all and take the default:
 * weight, value-vs-target, HRV, resting HR, usage, adherence, the composition
 * strips and the lab strips.
 */
enum class ChartInk(val series: Map<PlotTone, SeriesStyle>) {

    /**
     * Top set, e1RM, RPE — three real series and not a mean among them, which
     * is exactly the case the global default cannot express. The subject is ink;
     * the two that follow take plates in the order they were added.
     */
    PROGRESSION(
        mapOf(
            PlotTone.PRIMARY to SeriesStyle(SeriesInk.INK, SeriesMark.LINE),
            PlotTone.ALT to SeriesStyle(SeriesInk.PLATE_1, SeriesMark.LINE),
            PlotTone.SECONDARY to SeriesStyle(SeriesInk.PLATE_2, SeriesMark.LINE),
        ),
    ),

    /**
     * Scale weight against DEXA total mass: the readings and their mean keep the
     * ink they have everywhere else, and the scans — the chart's one extra
     * series — take plate 1 as rings with a connecting line.
     */
    BODY(
        mapOf(
            PlotTone.VALUE to SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.DOT),
            PlotTone.PRIMARY to SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.DASHED_LINE),
            PlotTone.SCAN to SeriesStyle(SeriesInk.PLATE_1, SeriesMark.RING),
        ),
    ),

    /**
     * Hours as bars, score on its own fixed axis. The hours are the subject and
     * stay ink-soft — a night's sleep drawn in full ink would be the heaviest
     * thing on the screen — and the score is this chart's first extra series, so
     * it takes plate 1 rather than the plate 2 the global default would give it.
     */
    SLEEP(
        mapOf(
            PlotTone.PRIMARY to SeriesStyle(SeriesInk.INK_SOFT, SeriesMark.BAR),
            PlotTone.SECONDARY to SeriesStyle(SeriesInk.PLATE_1, SeriesMark.DOT),
        ),
    ),

    /**
     * Steady sessions: one series of raw readings, sized by duration. Its
     * PRIMARY is the subject rather than a mean, so it is ink and solid.
     */
    AEROBIC(
        mapOf(PlotTone.PRIMARY to SeriesStyle(SeriesInk.INK, SeriesMark.DOT)),
    ),

    /** Planned and extra Zone 2 minutes: two segments, two plates. */
    CARDIO_ZONE2(
        mapOf(
            PlotTone.STACK_0 to SeriesStyle(SeriesInk.PLATE_1, SeriesMark.BAR),
            PlotTone.SECONDARY to SeriesStyle(SeriesInk.PLATE_2, SeriesMark.BAR),
        ),
    ),

    /** The top three lifts by tonnage, and everything else folded into ink. */
    VOLUME(
        mapOf(
            PlotTone.STACK_0 to SeriesStyle(SeriesInk.PLATE_1, SeriesMark.BAR),
            PlotTone.STACK_1 to SeriesStyle(SeriesInk.PLATE_2, SeriesMark.BAR),
            PlotTone.STACK_2 to SeriesStyle(SeriesInk.PLATE_3, SeriesMark.BAR),
            PlotTone.STACK_OTHER to SeriesStyle(SeriesInk.INK_FAINT, SeriesMark.BAR),
        ),
    ),
}

/** How [tone] is drawn on [chart] — its own plan if it has one, the default otherwise. */
fun seriesStyle(tone: PlotTone, chart: ChartInk? = null): SeriesStyle =
    chart?.series?.get(tone) ?: defaultSeriesStyle(tone)

// ---- what the mark implies ------------------------------------------------------

/** The mark a legend entry draws beside its word. */
enum class LegendSwatch { DOT, OPEN_DOT, LINE, DASH, FAINT_DASH, SQUARE, BAND, NONE }

/**
 * The key for a series, taken from the mark it is actually drawn with.
 *
 * Derived rather than declared, so a chart that re-plans a series re-keys it in
 * the same edit: progression's top set is a solid line here because it is a
 * solid line there.
 *
 * [LegendSwatch.NONE] is [SeriesMark.NOTE]'s — `MUTED`'s four legend uses are
 * all absences (off-plan, paused, an interval count, "no scans in range"), and a
 * key for an absence is a key to nothing.
 */
fun legendSwatch(tone: PlotTone, chart: ChartInk? = null): LegendSwatch =
    when (seriesStyle(tone, chart).mark) {
        SeriesMark.LINE -> LegendSwatch.LINE
        SeriesMark.DASHED_LINE -> LegendSwatch.DASH
        SeriesMark.FAINT_DASHED_LINE -> LegendSwatch.FAINT_DASH
        SeriesMark.DOT -> LegendSwatch.DOT
        SeriesMark.RING -> LegendSwatch.OPEN_DOT
        SeriesMark.BAR -> LegendSwatch.SQUARE
        SeriesMark.WASH -> LegendSwatch.BAND
        SeriesMark.NOTE -> LegendSwatch.NONE
    }

/** Whether a point of this mark is drawn hollow. */
fun isOpenMark(mark: SeriesMark): Boolean = mark == SeriesMark.RING

/** Whether a line of this mark is dashed, and which of the two dashes it takes. */
fun isDashedMark(mark: SeriesMark): Boolean =
    mark == SeriesMark.DASHED_LINE || mark == SeriesMark.FAINT_DASHED_LINE
