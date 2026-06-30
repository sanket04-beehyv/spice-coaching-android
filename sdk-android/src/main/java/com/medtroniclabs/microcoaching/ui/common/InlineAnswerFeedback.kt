package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Inline answer-feedback block shown directly below the answer cards once
 * the CHW has picked an option. Replaces the pinned-bottom
 * [AnswerFeedbackOverlay] for the main module quiz and the refresher quiz —
 * the option-card state already signals correct/wrong via colour, so the
 * dedicated "Correct / Incorrect" headline is dropped and only the
 * "Why this matters" callout + a "Next Question" CTA remain.
 *
 * Reveal is animated: vertical expand + fade. `visible` should be driven by
 * whether the current question has been answered.
 *
 * @param footerOverride When non-null, renders in place of the default
 *   "Next Question" button (the explanation box above is unchanged). The
 *   refresher sheet uses this to surface the "Next refresher / Done" actions on
 *   the last question; [onNext] is then ignored. Default null preserves the
 *   standard button everywhere else.
 */
@Composable
fun InlineAnswerFeedback(
    visible: Boolean,
    explanation: String,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    footerOverride: (@Composable () -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + expandVertically(tween(220)),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(160)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (explanation.isNotBlank()) {
                WhyThisMattersBox(explanation = explanation)
            }
            if (footerOverride != null) {
                footerOverride()
            } else {
                Button(
                    onClick = onNext,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpiceBlue),
                ) {
                    Text(
                        text = stringResource(R.string.quiz_next_question),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
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
