package com.medtroniclabs.microcoaching.ui.learn.modules.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule

/**
 * Bounded, lightly-scrollable row of training tiles that **fills the row width**.
 * The cards are sized from [tileWidth] as a target: when every module fits, they
 * stretch to fill the width (no trailing gap); when there are more than fit, one
 * extra card **peeks** at the right edge. Only those few are rendered, so the row
 * scrolls *just enough* to pull the peeked card fully into view — it does NOT
 * scroll through the whole catalogue (that's what "See all" is for). A single
 * module keeps its natural [tileWidth]. See [computeRowPeek].
 */
@Composable
fun TrainingRow(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    onSeeAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    tileWidth: Dp = 150.dp,
    tileSpacing: Dp = 12.dp,
    horizontalPadding: Dp = 16.dp,
) {
    if (modules.isEmpty()) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = maxWidth - horizontalPadding * 2
        val layout = computeRowPeek(available, modules.size, tileWidth, tileSpacing)
        val showSeeAll = onSeeAll != null && layout.hasMore

        Column(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(
                title = stringResource(R.string.modules_section_training),
                seeAllLabel = if (showSeeAll) stringResource(R.string.modules_see_all) else null,
                onSeeAllClick = if (showSeeAll) onSeeAll else null,
            )
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(tileSpacing),
            ) {
                modules.take(layout.visibleCount).forEach { module ->
                    TrainingCard(
                        title = module.title,
                        meta = stringResource(
                            R.string.training_meta_minutes_questions,
                            module.estimatedMinutes ?: 5,
                            module.inlineQuestions?.size ?: 0,
                        ),
                        progressFraction = progressFractionFor(module),
                        thumbnailUrl = module.thumbnailUrl,
                        onClick = { onSelect(module) },
                        modifier = Modifier.width(layout.cardWidth),
                    )
                }
            }
        }
    }
}

/** How many tiles to render, how wide, and whether more exist beyond the row. */
internal data class RowPeekLayout(val visibleCount: Int, val cardWidth: Dp, val hasMore: Boolean)

/**
 * Sizes a horizontal tile row so it always fills [available] width.
 *
 * - 1 item → natural [targetWidth] (no fill).
 * - all items fit at ~[targetWidth] → stretch them to fill the width exactly.
 * - more items than fit → [visibleCount] = `baseCount + 1`, cards sized so
 *   `baseCount` show fully and the extra one **peeks** ([peekFraction] of a card)
 *   at the right edge. The caller renders only those `visibleCount` cards in a
 *   scrollable row, so the row scrolls just enough to reveal the peeked card —
 *   not the whole catalogue ("See all" covers that).
 *
 * `baseCount` = `floor((available + spacing) / (targetWidth + spacing))`.
 */
internal fun computeRowPeek(
    available: Dp,
    itemCount: Int,
    targetWidth: Dp,
    spacing: Dp,
    peekFraction: Float = 0.4f,
): RowPeekLayout {
    if (itemCount <= 1) return RowPeekLayout(itemCount.coerceAtLeast(0), targetWidth, hasMore = false)
    val baseCount = ((available + spacing) / (targetWidth + spacing)).toInt().coerceIn(1, itemCount)
    return if (itemCount <= baseCount) {
        // Everything fits — stretch the cards to fill the row (no trailing gap).
        val cardWidth = (available - spacing * (itemCount - 1)) / itemCount
        RowPeekLayout(itemCount, cardWidth, hasMore = false)
    } else {
        // More than fit — baseCount full + one peeking card, sized to fill width.
        val cardWidth = (available - spacing * baseCount) / (baseCount + peekFraction)
        RowPeekLayout(baseCount + 1, cardWidth, hasMore = true)
    }
}
