package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.layout.PaddingValues
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Overlay shown after the CHW selects an answer.
 *
 * Correct → green banner + floating "+N pts" animation + optional "Why this matters" blue box.
 * Wrong → red banner + show the correct answer text + optional "Why this matters" blue box.
 *
 * Triggers [onDismiss] after a fixed delay so the nav graph can advance to the next question.
 *
 * @param isCorrect Whether the selected answer was correct.
 * @param pointValue Points awarded for a correct answer (only shown when correct).
 * @param correctAnswerText Text of the correct answer (only shown when wrong).
 * @param explanation Bangla explanation shown in a "Why this matters" blue box (optional).
 * @param onDismiss Called after the reveal animation completes.
 */
@Composable
/**
 * @param onNext When non-null, renders a "Next Question" button at the bottom
 *   of the overlay so the CHW can advance manually. When null the overlay
 *   is display-only and the caller manages navigation.
 */
fun AnswerFeedbackOverlay(
    isCorrect: Boolean,
    pointValue: Int,
    correctAnswerText: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    explanation: String = "",
    onNext: (() -> Unit)? = null,
) {
    val backgroundColor = if (isCorrect) Color(0xFFD7F0E5) else Color(0xFFFFEBEE)
    val textColor = if (isCorrect) Color(0xFF0A3D27) else Color(0xFF7F0014)
    val headline = if (isCorrect) stringResource(R.string.quiz_correct) else stringResource(R.string.quiz_incorrect)

    // Floating "+N pts" animation (starts immediately on render)
    var floatOffset by remember { mutableFloatStateOf(0f) }
    var showPoints by remember { mutableStateOf(isCorrect) }

    val animatedOffset by animateFloatAsState(
        targetValue = floatOffset,
        animationSpec = tween(durationMillis = 800),
        label = "pts_float",
    )

    // No auto-dismiss timer — the CHW taps "Next Question" to advance manually.

    Box(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .padding(20.dp),
        ) {
            Text(
                text = headline,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = textColor,
            )

            if (!isCorrect) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.quiz_correct_answer, correctAnswerText),
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                )
            }

            if (explanation.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                WhyThisMattersBox(explanation = explanation)
            }

            if (onNext != null) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding(),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SpiceBlue,
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = androidx.compose.ui.res.stringResource(R.string.quiz_next_question),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
            }
        }

        // Floating "+N pts" text — only for correct answers
        // Points/XP display temporarily disabled — UI only; [pointValue] + scoring logic retained.
        // if (isCorrect) {
        //     AnimatedVisibility(
        //         visible = showPoints,
        //         enter = fadeIn(tween(200)) + scaleIn(),
        //         exit = fadeOut(tween(400)),
        //         modifier = Modifier
        //             .align(Alignment.TopEnd)
        //             .offset(y = animatedOffset.dp),
        //     ) {
        //         Text(
        //             text = stringResource(R.string.quiz_points, pointValue),
        //             color = Color(0xFF1B6B4A),
        //             fontWeight = FontWeight.Bold,
        //             fontSize = 18.sp,
        //             modifier = Modifier.padding(end = 16.dp),
        //         )
        //     }
        // }
    }
}

@Composable
private fun WhyThisMattersBox(explanation: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFDCEEFF), shape = RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = null,
                tint = Color(0xFF004B87),
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.feedback_why_this_matters),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = Color(0xFF004B87),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = explanation,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF1A3A5C),
        )
    }
}
