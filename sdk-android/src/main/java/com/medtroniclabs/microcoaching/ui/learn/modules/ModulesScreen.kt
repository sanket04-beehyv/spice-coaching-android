package com.medtroniclabs.microcoaching.ui.learn.modules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.KnowledgeDocument
import com.medtroniclabs.microcoaching.ui.learn.LearnModule
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion
import com.medtroniclabs.microcoaching.ui.learn.modules.components.KnowledgeRow
import com.medtroniclabs.microcoaching.ui.learn.modules.components.QuizRefresherCard
import com.medtroniclabs.microcoaching.ui.learn.modules.components.RefresherList
import com.medtroniclabs.microcoaching.ui.learn.modules.components.TrainingRow

/**
 * Fixed height of the home Refreshers section. The section stays at this height
 * whether or not refreshers are present (so the rest of the screen layout doesn't
 * shift); tiles scroll vertically within it when they overflow. Sized to ~2.5
 * tiles to match the approved prototype — adjust here if the design height changes.
 */
private val REFRESHER_SECTION_HEIGHT = 232.dp

/**
 * Orchestrator composable for the v0.3.2 modules screen. Renders, top to
 * bottom: [QuickLearnCard] · [RefresherList] · [TrainingGrid] · [KnowledgeRow].
 *
 * Sheet launches are hoisted out — the caller (`CoachingNavGraph`) holds the
 * activity reference needed for `FragmentManager` and passes lambdas down.
 * This keeps `ModulesScreen` free of any Compose context-walking and
 * eliminates the "FragmentManager is null" failure mode reported in pilot.
 *
 * Refreshers / training / the featured pick are read from the shared
 * [com.medtroniclabs.microcoaching.domain.refresher.CoachingModuleStore] via
 * [QuickLearnViewModel] — the same source the home MorningCard uses — so the two
 * surfaces always agree on what's featured.
 *
 * @param chwId Forwarded into the [QuickLearnViewModel] factory.
 * @param onShowQuickLearn Open the Quick learn bottom sheet (no-op when no
 *   morning module is surfaced; the banner hides automatically in that case).
 * @param onShowRefresherQuiz Open the Refresher quiz bottom sheet. The caller
 *   must have already primed the LearnViewModel via `selectModuleForQuiz`.
 * @param onTrainingSelect Tap target for Training cards — host routes through
 *   the existing `ModuleReady → LessonContent → Quiz` chain.
 * @param onRefresherStart Fired *before* [onShowRefresherQuiz] so the host
 *   can prime `LearnViewModel.selectModuleForQuiz(module)`.
 * @param onSeeAllTraining Opens the full training-module grid (`AllModulesScreen`).
 *   Only surfaced by [TrainingRow] when there are more than fit on screen.
 * @param onSeeAllRefreshers Retained for API compatibility; the home Refreshers
 *   section now scrolls in place at a fixed height instead of linking to a
 *   full-screen list, so this is no longer surfaced.
 * @param knowledgeDocuments Deduped source documents for the Knowledge section.
 * @param onKnowledgeDocSelect Tap target for a Knowledge document — host
 *   downloads + previews it.
 * @param onSeeAllKnowledge Opens the full Knowledge document grid.
 * @param cachedDocIds Source-document IDs already on disk — drives the per-card
 *   download vs "view" (eye) affordance in [KnowledgeRow].
 */
@Composable
fun ModulesScreen(
    chwId: String,
    onShowQuickLearn: (moduleFamilyId: String?, queueFamilyIds: List<String>) -> Unit,
    onShowRefresherQuiz: (queueFamilyIds: List<String>) -> Unit,
    onTrainingSelect: (LearnModule) -> Unit,
    onRefresherStart: (LearnModule) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onSeeAllTraining: () -> Unit = {},
    onSeeAllRefreshers: () -> Unit = {},
    knowledgeDocuments: List<KnowledgeDocument> = emptyList(),
    onKnowledgeDocSelect: (KnowledgeDocument) -> Unit = {},
    onSeeAllKnowledge: () -> Unit = {},
    cachedDocIds: Set<String> = emptySet(),
) {
    val context = LocalContext.current
    val quickLearnViewModel: QuickLearnViewModel = viewModel(
        factory = QuickLearnViewModel.factory(context.applicationContext, chwId),
    )

    // Refreshers / training / featured pick come from the shared SDK store (the
    // single source of truth, also driving the home MorningCard) — categorisation
    // + the drop rules + the featured selection all happen there. `featured`
    // already excludes skipped families and requires a quiz: swiping it marks the
    // module skipped → the store advances `featured` to the next refresher (or
    // null when all are skipped).
    val refreshers by quickLearnViewModel.refresherModules.collectAsState()
    val training by quickLearnViewModel.trainingModules.collectAsState()
    val featured by quickLearnViewModel.featuredCard.collectAsState()
    val skipped by quickLearnViewModel.skippedRefresherIds.collectAsState()

    // The RefresherList shows ONLY skipped refreshers. The featured pick lives in
    // the banner; the rest of the active queue is reached by working through the
    // banner's "Next refresher" chain (not listed here). Skipping the banner drops
    // that module into this list. Empty (the common case) → "no skipped" message.
    val listModules = refreshers.filter { it.moduleFamilyId in skipped }
    // Shared queue for the sheet = the FULL active pool, so "Next refresher" chains
    // through every active refresher (banner + queued + skipped) regardless of
    // entry point — the CHW can complete the whole active set in order.
    val poolFamilyIds = refreshers.map { it.moduleFamilyId }

    // First still-to-reinforce question of the featured module — the banner preview.
    val bannerQuestion by produceState<QuizQuestion?>(initialValue = null, featured?.moduleFamilyId) {
        value = featured?.moduleFamilyId?.let { quickLearnViewModel.firstReinforceQuestionFor(it) }
    }

    val learningPoints by MicroCoachingSDK.getInstance().learningPoints.collectAsState()

    // Keep the skipped-refresher badge count to unique, still-active refreshers.
    LaunchedEffect(poolFamilyIds) {
        quickLearnViewModel.reconcileActiveSkipped(poolFamilyIds)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        // Slim indeterminate progress bar — fades in while modules are still
        // loading, fades out the moment they arrive. Lives on the same surface
        // as the modules list so there's no route-transition flicker between
        // a separate Loading screen and ModulesScreen.
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        featured?.let { f ->
            bannerQuestion?.let { q ->
                QuizRefresherCard(
                    questionText = q.questionText,
                    participantCount = 12, // Placeholder — backend count endpoint deferred.
                    xpReward = learningPoints.quizScoreMultiplier,
                    // Open the featured refresher; the sheet chains through the whole
                    // visible pool (featured first, then the list).
                    onClick = { onShowQuickLearn(f.moduleFamilyId, poolFamilyIds) },
                    // Swipe-away → skip: the module drops into the RefresherList, the
                    // banner hides for the session, and the home "Coaching" tile badge
                    // increments.
                    onDismiss = { quickLearnViewModel.skipQuickRefresher(f.moduleFamilyId) },
                    dismissKey = f.moduleFamilyId,
                )
            }
        }

        RefresherList(
            modules = listModules,
            onSelect = { module ->
                onRefresherStart(module)
                // Full active pool → "Next refresher" chains the whole active set.
                onShowRefresherQuiz(poolFamilyIds)
            },
            // Fixed-height, always-visible section: it stays at a constant height
            // whether or not refreshers are present (empty → "No refreshers yet."),
            // and tiles scroll vertically inside it when they overflow — so the rest
            // of the screen layout is unaffected by the refresher count. No "See all".
            fixedHeight = REFRESHER_SECTION_HEIGHT,
            emptyMessage = stringResource(R.string.refresher_empty_none),
        )

        TrainingRow(
            modules = training,
            onSelect = onTrainingSelect,
            onSeeAll = onSeeAllTraining,
        )

        KnowledgeRow(
            documents = knowledgeDocuments,
            onSelect = onKnowledgeDocSelect,
            onSeeAll = onSeeAllKnowledge,
            cachedDocIds = cachedDocIds,
        )

        Spacer(Modifier.height(80.dp)) // Breathing room for FABs
    }
}
