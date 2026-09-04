package dev.jtiisto.wellness.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jtiisto.wellness.core.data.trends.SleepTonightModel
import dev.jtiisto.wellness.core.data.trends.TonightJudgment
import dev.jtiisto.wellness.core.ui.theme.INK_BANG
import dev.jtiisto.wellness.core.ui.theme.InkJudgment
import dev.jtiisto.wellness.core.ui.theme.InkJudgmentGlyph
import dev.jtiisto.wellness.core.ui.theme.LogbookSpace
import dev.jtiisto.wellness.core.ui.theme.LogbookTheme

/**
 * Tonight's sleep need: one number, and what stands behind it.
 *
 * A leaf on purpose. Everything it says was decided by
 * `sleepTonightModel` in `:core:data`, which is what lets the same card be
 * lifted onto a start screen or rendered into a Glance widget without a
 * decision travelling with it. It lives here rather than in `:feature:trends`
 * for the same reason.
 *
 * The judgment is drawn as the system's ink mark and, when it wants attention,
 * as the mono `!` beside the number — never as a colour. A sleep debt is not a
 * failing grade, and the one thing being called out (a night the watch missed)
 * is a hole in the record rather than a verdict about the reader.
 */
@Composable
fun SleepTonightCard(model: SleepTonightModel, modifier: Modifier = Modifier) {
    val palette = LogbookTheme.palette

    // The whole card is one spoken node: read mark-by-mark it would announce a
    // glyph, a bare number and three unattached fragments. StatTile does the
    // same for the same reason.
    val spoken = buildString {
        append("Tonight's sleep need ${model.needText.replace(':', 'h')}m")
        append(". ${model.cardDebtLine}")
        append(". ${model.strainLine}")
        model.freshnessLine?.let { append(". $it") }
        model.cachedLine?.let { append(". $it") }
    }

    val ink = when (model.judgment) {
        TonightJudgment.SETTLED -> InkJudgment.SETTLED
        TonightJudgment.PARTIAL -> InkJudgment.PARTIAL
        TonightJudgment.ATTENTION -> InkJudgment.ATTENTION
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(LogbookSpace.grid + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkJudgmentGlyph(ink)
            Text(
                text = "TONIGHT'S SLEEP NEED",
                style = LogbookTheme.type.eyebrow,
                color = palette.inkSoft,
            )
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (model.flagged) {
                Text(
                    text = INK_BANG,
                    style = LogbookTheme.type.data.copy(fontWeight = FontWeight.Medium),
                    color = palette.ink,
                    modifier = Modifier.padding(end = LogbookSpace.grid),
                )
            }
            // StatTile's treatment, deliberately: a headline number in this app
            // is 24sp mono at Medium, and a second size for a second headline
            // would read as a second kind of thing.
            Text(
                text = model.needText,
                style = LogbookTheme.type.data.copy(
                    fontSize = 24.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = palette.ink,
            )
            Text(
                text = "h:mm",
                style = LogbookTheme.type.meta,
                color = palette.inkSoft,
                modifier = Modifier.padding(start = 3.dp, bottom = 3.dp),
            )
        }
        // The card's own line, not the widget's: it has the width to name the
        // nap credit that tonight's need was already reduced by.
        Text(model.cardDebtLine, style = LogbookTheme.type.meta, color = palette.ink)
        Text(model.strainLine, style = LogbookTheme.type.meta, color = palette.inkSoft)
        model.freshnessLine?.let {
            Text(it, style = LogbookTheme.type.meta, color = palette.inkSoft)
        }
        model.cachedLine?.let {
            Text(it.uppercase(), style = LogbookTheme.type.eyebrow, color = palette.inkSoft)
        }
    }
}
