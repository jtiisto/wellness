package dev.jtiisto.wellness.core.data.trends

import dev.jtiisto.wellness.core.data.network.DateString
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Tonight's sleep need, reduced to the handful of strings a surface draws.
 *
 * This lives in `:core:data` rather than beside the Trends charts on purpose.
 * The card it feeds is planned to move — to a start screen, and to a home-screen
 * widget rendered by a `CoroutineWorker` that has no Compose tree and no
 * `:feature:trends` on its classpath. Everything the widget will need to say the
 * same sentence as the app (the DTO, the fetch, this reduction) therefore lives
 * on the module both can reach, exactly as `JournalUiLogic` does for the
 * journal's own display rules.
 *
 * Nothing here fetches, judges the *user*, or reaches for a theme: the judgment
 * is about the state of the **data** — settled, partial, or wanting attention —
 * and a composable maps it to ink.
 */

/**
 * How much of tonight's number can be believed.
 *
 * [SETTLED] is the whole truth as of today. [PARTIAL] means the number is
 * standing on something incomplete — a cached copy, a watch that has not synced,
 * or a payload naming a different night. [ATTENTION] is reserved for the one
 * thing a reader must not miss: the ledger reset because a night went
 * unrecorded, so today's debt is zero for a reason that is not "you slept
 * enough".
 */
enum class TonightJudgment { SETTLED, PARTIAL, ATTENTION }

/**
 * The card, as strings.
 *
 * [needText] is the headline — `h:mm`, because a sleep target is a duration and
 * "8.7 hours" is not how anyone sets an alarm. [freshnessLine] and [cachedLine]
 * are separate because they answer different questions: one is about the *data*
 * the server had, the other about the *copy* this device is holding.
 */
data class SleepTonightModel(
    val needText: String,
    val debtLine: String,
    val strainLine: String,
    val freshnessLine: String?,
    val cachedLine: String?,
    val judgment: TonightJudgment,
    /** Draw the system's mono `!` beside the number. Never a colour. */
    val flagged: Boolean,
)

/**
 * Reduce a sleep payload to tonight's card, or null when there is no card to
 * draw.
 *
 * Null covers three states that are all the same to a surface: nothing fetched
 * yet, a source this install does not have (`available: false`), and a payload
 * that somehow carries no `tonight`. In each case the card is *absent* rather
 * than empty — an "unavailable" placeholder above the recovery charts would be
 * a permanent apology on every phone without a Garmin database.
 *
 * [today] is the **device's** calendar day, passed in rather than read, so this
 * stays a pure function and a widget rendering at 03:00 gets the same answer as
 * a test. [now] is epoch millis, used only for the cached-copy age.
 */
fun sleepTonightModel(
    dto: SleepDebtDto?,
    staleFetchedAt: Long?,
    now: Long,
    today: DateString,
): SleepTonightModel? {
    if (dto == null || !dto.available) return null
    val tonight = dto.tonight ?: return null

    // The trailing row is the night just past — the one tonight's debt was
    // carried out of. A gap there is what makes tonight's zero debt a hole in
    // the record rather than a clean slate.
    val resetLastNight = dto.days.lastOrNull()?.gap == true

    val debtLine = buildString {
        append(if (tonight.debtMin == 0.0) "no sleep debt" else "debt ${hoursMinutes(tonight.debtMin)}")
        if (resetLastNight) append(" · reset — missing night")
    }

    val strainLine = buildString {
        append("strain ${strainText(tonight.strainEst)}")
        if (tonight.strainPartial) append(" · so far")
    }

    // Precedence, not concatenation: when the payload is for another night that
    // is the whole correction, and the lag behind it is implied. Dates are
    // opaque strings compared for equality only — never parsed.
    val freshnessLine = when {
        tonight.date != today -> "for ${tonight.date}"
        dto.asOf == null -> NO_SCORED_NIGHTS_TEXT
        dto.asOf != today -> "data through ${dto.asOf}"
        else -> null
    }

    val cachedLine = staleFetchedAt?.let { cachedText(it, now) }

    val judgment = when {
        resetLastNight -> TonightJudgment.ATTENTION
        cachedLine != null || freshnessLine != null -> TonightJudgment.PARTIAL
        else -> TonightJudgment.SETTLED
    }

    return SleepTonightModel(
        needText = hoursMinutes(tonight.needMin),
        debtLine = debtLine,
        strainLine = strainLine,
        freshnessLine = freshnessLine,
        cachedLine = cachedLine,
        judgment = judgment,
        flagged = judgment == TonightJudgment.ATTENTION,
    )
}

/**
 * Minutes as `H:MM`.
 *
 * Rounded with [Double.roundToInt], whose ties go up toward +∞ — the same
 * semantics the rest of this codebase's number handling has (see CLAUDE.md:
 * `kotlin.math.round` rounds half to even and is the one to avoid). Rounding
 * happens **once**, on the total, so 59.6 minutes reads `1:00` rather than
 * `0:60`.
 *
 * A negative duration cannot happen — the server guarantees a non-negative
 * debt and the other two are elapsed time — so it clamps at zero rather than
 * inventing a sign convention for a number that would be a server bug.
 */
fun hoursMinutes(min: Double): String {
    val total = max(0, min.roundToInt())
    return String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}

/**
 * Strain, at the one decimal the server computes it to.
 *
 * Deliberately *not* the integer-collapsing rule numbers take elsewhere in the
 * app: this is an instrument reading on a fixed 0–21 scale, and `strain 4`
 * beside `strain 4.6` would read as a different kind of measurement rather than
 * as the same one landing on a whole number.
 */
private fun strainText(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/**
 * The age of the cached copy, in the badge's own words.
 *
 * **Duplicated deliberately** from `TrendsScreenLogic.staleBadgeText` in
 * `:feature:trends`, which stays where it is: this module cannot depend on a
 * feature, and the future widget cannot depend on one either. Six lines copied
 * is the cheaper of the two prices — the other being a shared "trends text"
 * seam in `:core:data` that exists to hold one string rule. If the wording
 * changes, it changes in both places; the comment on that function says so too.
 */
private fun cachedText(staleFetchedAt: Long, now: Long): String {
    val minutes = max(1L, ((now - staleFetchedAt) / 60_000.0).roundToLong())
    val age = if (minutes < 60) "${minutes}m" else "${(minutes / 60.0).roundToLong()}h"
    return "cached · $age ago"
}

/**
 * What a payload with no scored night has to say for itself.
 *
 * `as_of` is omitted exactly then, and the alternative — printing nothing —
 * would leave a headline need standing over no history at all, looking settled.
 */
const val NO_SCORED_NIGHTS_TEXT = "no scored nights yet"
