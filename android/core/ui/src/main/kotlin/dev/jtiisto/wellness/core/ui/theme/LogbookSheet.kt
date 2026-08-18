package dev.jtiisto.wellness.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * A Logbook bottom sheet's top edge: a `ruleStrong` hairline, then a short ink
 * rule to drag.
 *
 * M3's handle is a rounded grey lozenge on a raised surface. Neither survives
 * here — the sheet is the same paper as the page, so the boundary has to be
 * drawn, and the handle is a mark rather than a pill.
 *
 * Hoisted from the coach feature for the journal's accumulator and tracker-form
 * sheets: every Logbook sheet has the same edge, and a second copy of it in
 * another feature is how two sheets start looking like two apps.
 */
@Composable
fun LogbookSheetHandle() {
    val palette = LogbookTheme.palette
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalDivider(thickness = LogbookSpace.hairline, color = palette.ruleStrong)
        Box(
            modifier = Modifier
                .padding(vertical = LogbookSpace.grid * 2.5f)
                .width(HANDLE_WIDTH)
                .height(HANDLE_HEIGHT)
                .clip(LogbookShapes.soft)
                .background(palette.ink),
        )
    }
}

private val HANDLE_WIDTH = 28.dp
private val HANDLE_HEIGHT = 2.dp
