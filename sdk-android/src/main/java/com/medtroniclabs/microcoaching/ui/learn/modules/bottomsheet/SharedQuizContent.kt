package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AnswerCard
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer
import com.medtroniclabs.microcoaching.ui.common.AnswerCardState
import com.medtroniclabs.microcoaching.ui.common.InlineAnswerFeedback
import com.medtroniclabs.microcoaching.ui.common.XpRewardBurst
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel
import com.medtroniclabs.microcoaching.ui.learn.QuizQuestion

/**
 * Shared quiz-in-progress composable used by both:
 * - [RefresherQuizContent] (inside the bottom sheet)
 * - Module-end quiz flow triggered from [LessonPlayerScreen]
 *
 * Renders a sequential question flow: top nav (back / next / optional close) →
 * progress bar → question text → answer cards → [AnswerFeedbackOverlay] on
 * selection → advances to next question or calls [onAllAnswered] when the last
 * question is dismissed.
 *
 * Once an answer is selected for a question, navigating back to that question
 * shows the explanation in read-only mode — the answer cards no longer accept
 * taps. The CHW cannot re-answer a question in the same session.
 *
 * @param onClose Optional close handler. When non-null, an X icon is rendered
 *   in the top nav (used by the bottom-sheet callers). Pass `null` from
 *   full-screen callers to hide the close button.
 */
@Composable
fun SharedQuizInProgressContent(
    questions: List<QuizQuestion>,
    viewModel: LearnViewModel,
    onAllAnswered: () -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    optionContainerColor: Color = Color.White,
    lastQuestionFooter: (@Composable () -> Unit)? = null,
) {
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    // questionIndex -> selectedAnswerIndex. Preserved across in-session navigation
    // so previously answered questions stay locked in review mode.
    val answers = remember { mutableStateMapOf<Int, Int>() }

    if (questions.isEmpty()) return

    val total = questions.size
    val safeIndex = currentIndex.coerceIn(0, total - 1)
    val question = questions[safeIndex]
    val selectedForCurrent = answers[safeIndex]
    val canGoBack = safeIndex > 0
    val canGoNext = selectedForCurrent != null && safeIndex < total - 1

    val scrollState = rememberScrollState()
    val showingFeedback = selectedForCurrent != null

    // Per-correct-answer reward from the shared learning-points config
    // (quiz_score_multiplier), not the legacy per-question pointValue.
    val learningPoints by MicroCoachingSDK.getInstance().learningPoints.collectAsState()

    // Drives the celebratory XP burst. A fresh key flips on every first
    // correct tap; the burst self-dismisses after ~1.3 s. Resets per question
    // via `remember(safeIndex)` so navigating back/forward doesn't replay it.
    var xpBurstKey by remember(safeIndex) { mutableStateOf<Any?>(null) }

    LaunchedEffect(safeIndex) {
        scrollState.scrollTo(0)
    }
    LaunchedEffect(showingFeedback) {
        scrollState.scrollTo(0)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        QuizTopNav(
            canGoBack = canGoBack,
            canGoNext = canGoNext,
            onBack = { if (canGoBack) currentIndex = safeIndex - 1 },
            onNext = { if (canGoNext) currentIndex = safeIndex + 1 },
            onClose = onClose,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.quiz_question_counter, safeIndex + 1, total),
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFF6B7280),
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { (safeIndex + 1f) / total },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(rememberNestedScrollInteropConnection())
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Case setup / "Context" block intentionally hidden per product
            // direction — keep the data + composable, just don't render it.
            // if (question.caseSetup.isNotBlank()) {
            //     RefresherCaseSetupBox(caseSetup = question.caseSetup)
            // }
            Text(
                text = question.questionText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
            question.answers.forEachIndexed { index, answerText ->
                val cardState = resolveAnswerCardState(
                    index = index,
                    selected = selectedForCurrent,
                    correctIndex = question.correctIndex,
                )
                AnswerCard(
                    text = answerText,
                    state = cardState,
                    onClick = {
                        // Lock the question once answered — no re-do in the same session.
                        if (selectedForCurrent != null) return@AnswerCard
                        answers[safeIndex] = index
                        viewModel.selectAnswer(safeIndex, index)
                        // Celebratory burst on first correct tap only — the
                        // lock above guarantees we never re-fire on revisit.
                        if (index == question.correctIndex) {
                            xpBurstKey = System.nanoTime()
                        }
                    },
                    index = index,
                    unselectedContainerColor = optionContainerColor,
                )
            }

            // Inline reveal directly below the option cards — no more pinned
            // bottom overlay that hid scrollable content and complicated layout.
            // On the last question a caller-supplied footer (e.g. the refresher
            // "Next refresher / Done" actions) replaces the default Next button.
            val isLastQuestion = safeIndex + 1 >= total
            InlineAnswerFeedback(
                visible = showingFeedback,
                explanation = question.explanation,
                onNext = {
                    if (isLastQuestion) onAllAnswered()
                    else currentIndex = safeIndex + 1
                },
                footerOverride = if (isLastQuestion) lastQuestionFooter else null,
            )
        }

        // Celebratory XP burst overlay — sits above the scrolling content so
        // it pops in at the top of the answer area without displacing layout.
        // Points/XP display temporarily disabled — UI only; burst trigger + scoring logic retained.
        // XpRewardBurst(
        //     triggerKey = xpBurstKey,
        //     pointValue = learningPoints.quizScoreMultiplier,
        //     modifier = Modifier
        //         .align(Alignment.TopCenter)
        //         .padding(top = 16.dp),
        // )
        }
    }
}

@Composable
private fun QuizTopNav(
    canGoBack: Boolean,
    canGoNext: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onClose: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavCircleButton(
            enabled = canGoBack,
            onClick = onBack,
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.quiz_nav_previous),
        )
        NavCircleButton(
            enabled = canGoNext,
            onClick = onNext,
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(R.string.quiz_nav_next),
        )
        Spacer(Modifier.weight(1f))
        if (onClose != null) {
            NavCircleButton(
                enabled = true,
                onClick = onClose,
                icon = Icons.Filled.Close,
                contentDescription = stringResource(R.string.quiz_nav_close),
            )
        }
    }
}

@Composable
private fun NavCircleButton(
    enabled: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
) {
    val bg = if (enabled) SpiceBlueContainer else Color(0xFFF2F4F7)
    val tint = if (enabled) SpiceBlue else Color(0xFFB0B7C3)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(36.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
internal fun RefresherCaseSetupBox(caseSetup: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = SpiceBlueContainer,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.quiz_case_context_label),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = SpiceBlue,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = caseSetup,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

internal fun resolveAnswerCardState(
    index: Int,
    selected: Int?,
    correctIndex: Int,
): AnswerCardState = when {
    selected == null -> AnswerCardState.Unselected
    index == correctIndex -> AnswerCardState.CorrectRevealed
    index == selected -> AnswerCardState.WrongRevealed
    else -> AnswerCardState.Unselected
}
