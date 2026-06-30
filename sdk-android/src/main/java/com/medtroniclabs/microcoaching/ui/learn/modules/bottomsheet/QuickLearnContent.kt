package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.common.AnswerCard
import com.medtroniclabs.microcoaching.ui.common.AnswerCardState
import com.medtroniclabs.microcoaching.ui.common.AnswerFeedbackOverlay
import com.medtroniclabs.microcoaching.ui.learn.modules.AnswerOutcome
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel

/**
 * Composable hosted inside [QuickLearnBottomSheet]. Renders the current
 * quick-learn question, drives answer selection, then defers to
 * [AnswerFeedbackOverlay] which auto-dismisses the sheet after a short
 * delay.
 *
 * @param onDismiss Called when the feedback overlay's auto-close timer fires
 *   — implemented by [QuickLearnBottomSheet] to call `dismiss()` on the
 *   underlying DialogFragment.
 */
@Composable
fun QuickLearnContent(
    viewModel: QuickLearnViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val question by viewModel.quickQuestion.collectAsState()
    val answerState by viewModel.answerState.collectAsState()
    val payload = question
    // Per-correct-answer reward from the shared learning-points config.
    val learningPoints by MicroCoachingSDK.getInstance().learningPoints.collectAsState()

    if (payload == null) {
        // Module sync hasn't populated morningModules yet — render an empty
        // placeholder rather than crashing. Sheet will close on next outside
        // tap.
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = payload.question.questionText,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )
        payload.question.answers.forEachIndexed { index, answerText ->
            val cardState = resolveCardState(
                index = index,
                outcome = answerState,
                correctIndex = payload.question.correctIndex,
            )
            AnswerCard(
                text = answerText,
                state = cardState,
                onClick = {
                    if (answerState == null) viewModel.submitAnswer(index)
                },
                index = index,
            )
        }
        Spacer(Modifier.height(8.dp))

        answerState?.let { outcome ->
            AnswerFeedbackOverlay(
                isCorrect = outcome.isCorrect,
                pointValue = learningPoints.quizScoreMultiplier,
                correctAnswerText = payload.question.answers.getOrNull(payload.question.correctIndex) ?: "",
                explanation = payload.question.explanation,
                onDismiss = {
                    viewModel.reset()
                    onDismiss()
                },
            )
        }
    }
}

private fun resolveCardState(
    index: Int,
    outcome: AnswerOutcome?,
    correctIndex: Int,
): AnswerCardState = when {
    outcome == null -> AnswerCardState.Unselected
    index == correctIndex -> AnswerCardState.CorrectRevealed
    index == outcome.selectedIndex -> AnswerCardState.WrongRevealed
    else -> AnswerCardState.Unselected
}
