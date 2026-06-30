package com.medtroniclabs.microcoaching.ui.quiz

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.common.AnswerCard
import com.medtroniclabs.microcoaching.ui.common.AnswerCardState
import com.medtroniclabs.microcoaching.ui.common.InlineAnswerFeedback
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.common.XpRewardBurst
import com.medtroniclabs.microcoaching.ui.learn.LearnUiState
import com.medtroniclabs.microcoaching.ui.theme.SurfaceBackground

/**
 * Single quiz question screen with animated answer reveal and a consistent
 * blue [SdkScreenHeader].
 *
 * @param uiState Must be [LearnUiState.QuizInProgress].
 * @param questionIndex Which question is currently displayed (0-based).
 * @param onAnswerSelected Called when the CHW selects an answer.
 * @param onNext Navigate to next question or result screen.
 * @param onBack Navigate back (to lesson player if arriving from a course,
 *   or to module detail for the direct "Do a Quiz" path). Pressing the
 *   system back button triggers this — preventing mid-quiz back-stack issues.
 * @param moduleTitle Shown in the header when provided.
 */
@Composable
fun QuizQuestionScreen(
    uiState: LearnUiState,
    questionIndex: Int,
    onAnswerSelected: (answerIndex: Int) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit = {},
    moduleTitle: String = "",
    onHome: () -> Unit = {},
) {
    val quizState = uiState as? LearnUiState.QuizInProgress ?: return
    val question = quizState.questions.getOrNull(questionIndex) ?: return
    val totalQuestions = quizState.questions.size

    // Once a question is answered, lock it for the remainder of the session.
    // If the CHW navigates back to a previously-answered question, restore the
    // prior selection from quiz state and open straight into the explanation
    // overlay — no re-answering, no duplicate telemetry, no second point award.
    // Matches the refresher (SharedQuizInProgressContent) behaviour.
    val priorAnswer = quizState.answers[questionIndex]
    var selectedIndex by remember(questionIndex) { mutableStateOf(priorAnswer ?: -1) }
    var showFeedback by remember(questionIndex) { mutableStateOf(priorAnswer != null) }

    // Drives the celebratory XP burst. A fresh key flips on every first
    // correct tap; the burst self-dismisses after ~1.3 s. Resets per question
    // via `remember(questionIndex)` so revisits don't replay the animation.
    var xpBurstKey by remember(questionIndex) { mutableStateOf<Any?>(null) }

    // Per-correct-answer reward comes from the shared learning-points config
    // (config_threshold → quiz_score_multiplier), not the legacy per-question
    // pointValue. Falls back to the documented default when unsynced.
    val learningPoints by MicroCoachingSDK.getInstance().learningPoints.collectAsState()

    // Intercept system back — go to lesson player or module detail rather
    // than stepping back through quiz questions one-by-one.
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceBackground),
    ) {
        SdkScreenHeader(
            title = if (moduleTitle.isNotBlank()) moduleTitle
            else stringResource(R.string.quiz_question_counter, questionIndex + 1, totalQuestions),
            onBack = onBack,
            onHome = onHome,
        )

        Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .navigationBarsPadding(),
        ) {
            // Case setup / "Context" block intentionally hidden per product
            // direction — keep the data + composable, just don't render it.
            // if (question.caseSetup.isNotBlank()) {
            //     CaseSetupBox(caseSetup = question.caseSetup)
            //     Spacer(Modifier.height(16.dp))
            // }

            Text(
                text = stringResource(R.string.quiz_question_counter, questionIndex + 1, totalQuestions),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = question.questionText,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                lineHeight = 26.sp,
            )

            Spacer(Modifier.height(24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                question.answers.forEachIndexed { index, answerText ->
                    val cardState = when {
                        !showFeedback && selectedIndex == index -> AnswerCardState.Selected
                        !showFeedback -> AnswerCardState.Unselected
                        index == question.correctIndex -> AnswerCardState.CorrectRevealed
                        index == selectedIndex -> AnswerCardState.WrongRevealed
                        else -> AnswerCardState.Unselected
                    }
                    AnswerCard(
                        text = answerText,
                        state = cardState,
                        onClick = {
                            if (selectedIndex == -1 && !showFeedback) {
                                selectedIndex = index
                                onAnswerSelected(index)
                                showFeedback = true
                                // Celebratory burst on first correct tap only.
                                // Revisit path never reaches here because of
                                // the (selectedIndex == -1) guard above.
                                if (index == question.correctIndex) {
                                    xpBurstKey = System.nanoTime()
                                }
                            }
                        },
                        index = index,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Pre-selection hint button (disabled). Replaced by the inline
            // explanation + Next button once the CHW picks an answer.
            if (!showFeedback) {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.quiz_select_answer),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                    )
                }
            }

            // Inline reveal: "Why this matters" callout + "Next Question"
            // button, animated in once the CHW has answered. No overlay /
            // popup — the answer cards already carry the correct/wrong
            // colouring, so the dedicated headline is unnecessary.
            InlineAnswerFeedback(
                visible = showFeedback,
                explanation = question.explanation,
                onNext = {
                    showFeedback = false
                    onNext()
                },
            )

            Spacer(Modifier.height(24.dp))
        }

        // Celebratory XP burst overlay — sits above the scrolling content so
        // it pops in at the top of the answer area without displacing layout.
        // Points/XP display temporarily disabled — UI only; burst trigger + scoring logic retained.
        // XpRewardBurst(
        //     triggerKey = xpBurstKey,
        //     pointValue = learningPoints.quizScoreMultiplier,
        //     modifier = Modifier
        //         .align(Alignment.TopCenter)
        //         .padding(top = 24.dp),
        // )
        }
    }
}

/**
 * Light-blue context/scenario box shown above the question when [caseSetup] is non-blank.
 * Provides clinical background so the CHW understands the scenario before answering.
 */
@Composable
private fun CaseSetupBox(caseSetup: String) {
    androidx.compose.foundation.layout.Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = stringResource(R.string.quiz_case_context_label),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = com.medtroniclabs.microcoaching.ui.theme.SpiceBlue,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = caseSetup,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
