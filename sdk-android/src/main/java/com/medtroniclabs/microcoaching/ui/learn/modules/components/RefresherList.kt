package com.medtroniclabs.microcoaching.ui.learn.modules.components

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.parseLessonCards
import kotlinx.coroutines.launch

private const val TAG = "RefresherList"

/**
 * Vertical list of refresher tiles driven by the morning-card cache.
 *
 * Caller ([ModulesScreen]) is responsible for filtering by [LearnModule.source];
 * this composable only sorts gap-first within the supplied list.
 *
 * Two layout modes:
 *  - **Fixed-height section** ([fixedHeight] non-null, the home `ModulesScreen`):
 *    the "Refreshers" section is ALWAYS rendered at a constant height (no "See
 *    all"); tiles scroll vertically inside it when they overflow, a subtle
 *    "More ⌄" hint overlays the bottom while there's content below the fold, and an
 *    empty list shows [emptyMessage] centred. This keeps the screen layout stable
 *    whether or not refreshers are present.
 *  - **Flow** ([fixedHeight] null, the full-screen [RefreshersScreen]): renders all
 *    tiles inline; collapses to nothing when empty unless [emptyMessage] is set,
 *    and supports the legacy [maxVisible] cap + "See all" link.
 *
 * @param modules Refresher modules (already filtered by source != null).
 * @param onSelect Invoked when the CHW taps a tile; opens [RefresherBottomSheet].
 * @param fixedHeight When non-null, the constant height of the section body; tiles
 *   scroll within it and an empty list shows [emptyMessage]. Disables [maxVisible]
 *   and "See all".
 * @param maxVisible (flow mode) When non-null, render at most this many tiles.
 * @param onSeeAll (flow mode) When non-null *and* more refreshers exist than
 *   [maxVisible], a "See all" link is shown in the section header.
 * @param showHeader Whether to render the "Refreshers" section header.
 * @param emptyMessage Message rendered for an empty list (centred in fixed-height
 *   mode; under the header in flow mode). Null in flow mode collapses the section.
 */
@Composable
fun RefresherList(
    modules: List<LearnModule>,
    onSelect: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
    fixedHeight: Dp? = null,
    maxVisible: Int? = null,
    onSeeAll: (() -> Unit)? = null,
    showHeader: Boolean = true,
    emptyMessage: String? = null,
) {
    val refreshers = modules
        .sortedWith(compareBy { if (it.source == "gap") 0 else 1 })

    Log.d(TAG, "input=${modules.size} " +
        "(gap=${refreshers.count { it.source == "gap" }} " +
        "fallback=${refreshers.count { it.source == "fallback" }})")

    // ── Fixed-height section mode (home ModulesScreen) ──────────────────────────
    // Always visible; constant height; tiles scroll inside when they overflow; an
    // empty list shows the message centred. No "See all" (the section scrolls).
    if (fixedHeight != null) {
        Column(modifier = modifier) {
            if (showHeader) {
                SectionHeader(
                    title = stringResource(R.string.modules_section_refreshers),
                    seeAllLabel = null,
                    onSeeAllClick = null,
                )
            }
            if (refreshers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(fixedHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyMessage ?: stringResource(R.string.refresher_empty_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                val scrollState = rememberScrollState()
                val scope = rememberCoroutineScope()
                val density = LocalDensity.current
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(fixedHeight),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                    ) {
                        refreshers.forEach { module -> RefresherTileItem(module, onSelect) }
                    }
                    // Subtle "More ⌄" hint, overlaid at the bottom only while there's
                    // content below the fold (hides once scrolled to the end). Tapping
                    // it nudges the list down by ~one viewport.
                    if (scrollState.value < scrollState.maxValue) {
                        MoreScrollHint(
                            modifier = Modifier.align(Alignment.BottomCenter),
                            onClick = {
                                scope.launch {
                                    val delta = with(density) { fixedHeight.toPx() } * 0.8f
                                    scrollState.animateScrollTo((scrollState.value + delta).toInt())
                                }
                            },
                        )
                    }
                }
            }
        }
        return
    }

    // ── Flow mode (full-screen RefreshersScreen) ────────────────────────────────
    if (refreshers.isEmpty()) {
        if (emptyMessage == null) return
        Column(modifier = modifier) {
            if (showHeader) {
                SectionHeader(
                    title = stringResource(R.string.modules_section_refreshers),
                    seeAllLabel = null,
                    onSeeAllClick = null,
                )
            }
            Text(
                text = emptyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
        return
    }

    val visible = if (maxVisible != null) refreshers.take(maxVisible) else refreshers
    val hasMore = maxVisible != null && refreshers.size > maxVisible

    Column(modifier = modifier) {
        if (showHeader) {
            val showSeeAll = onSeeAll != null && hasMore
            SectionHeader(
                title = stringResource(R.string.modules_section_refreshers),
                seeAllLabel = if (showSeeAll) stringResource(R.string.modules_see_all) else null,
                onSeeAllClick = if (showSeeAll) onSeeAll else null,
            )
        }
        visible.forEach { module -> RefresherTileItem(module, onSelect) }
    }
}

/**
 * Small, low-opacity "More ⌄" scroll affordance overlaid at the bottom of the
 * fixed-height refresher section. Deliberately tiny + faint so it hints at more
 * content without competing with the tiles.
 */
@Composable
private fun MoreScrollHint(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .padding(bottom = 4.dp)
            .alpha(0.7f)
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.refresher_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** One refresher tile, with the drill-size meta resolved from the module. */
@Composable
private fun RefresherTileItem(module: LearnModule, onSelect: (LearnModule) -> Unit) {
    // Show the refresher drill size = "to-reinforce" count (wrong + never-answered),
    // which matches what primeRefresherQuiz presents. NOT wrongQuestionCount
    // (wrong-only) — that's 0 for a never-attempted gap card and would render "0
    // questions" while the quiz has N. Fall back to the total when not computed yet
    // or when it resolves to 0.
    val count = module.reinforceQuestionCount?.takeIf { it > 0 }
        ?: module.inlineQuestions?.size ?: 0
    RefresherTile(
        category = stringResource(refresherTypeLabelFor(module)),
        title = module.title,
        meta = pluralStringResource(R.plurals.refresher_meta_quiz, count, count),
        isCritical = module.clinicalDomain.equals("emergency", ignoreCase = true),
        isGap = module.source == "gap",
        severity = module.severity,
        thumbnailUrl = module.thumbnailUrl,
        onClick = { onSelect(module) },
    )
}

/**
 * Content-type label for a refresher, derived from what the module actually
 * carries: lesson cards + quiz → Microcoaching; cards only → Learning card;
 * quiz only → Quiz. (Today every refresher ships both, so this resolves to
 * Microcoaching, but the cards-only / quiz-only shapes are handled too.)
 */
private fun refresherTypeLabelFor(module: LearnModule): Int {
    val hasCards = parseLessonCards(module.cardsJson).isNotEmpty()
    val hasQuiz = module.inlineQuestions?.isNotEmpty() == true
    return when {
        hasCards && hasQuiz -> R.string.refresher_type_microcoaching
        hasCards -> R.string.refresher_type_learning_card
        else -> R.string.refresher_type_quiz
    }
}
