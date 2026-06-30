package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueDark

/**
 * Embeddable coaching card for the SPICE home screen — replaces the Learn FAB.
 *
 * Shows the active morning coaching module in a blue gradient card with
 * "Start" and "Skip" actions. The host renders this in its home screen
 * layout using a `ComposeView` (no Activity launch needed until the user taps
 * "Start").
 *
 * **Host integration:**
 * ```kotlin
 * binding.coachingCardComposeView.setContent {
 *     LearnCard(
 *         moduleTitle = sdk.morningModules.firstOrNull()?.title ?: "",
 *         questionCount = sdk.morningModules.firstOrNull()?.inlineQuestions?.size ?: 0,
 *         estimatedMinutes = 3,
 *         onStart = { CoachingFlowActivity.launchLearn(requireContext(), chwId) },
 *         onSkip = { sdk.skipMorningModule() },
 *     )
 * }
 * ```
 *
 * @param moduleTitle Module title shown prominently.
 * @param questionCount Number of questions (drives the pluralised meta chip).
 * @param estimatedMinutes Estimated duration.
 * @param languageLabel Localised language name shown in the meta row (default "Bangla").
 * @param onStart Invoked when the CHW taps "Start".
 * @param onSkip Invoked when the CHW taps "Skip today".
 */
@Composable
fun LearnCard(
    moduleTitle: String,
    questionCount: Int,
    estimatedMinutes: Int,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    languageLabel: String = stringResource(R.string.banner_meta_language_bn),
) {
    SdkLocalizedTheme {
        val gradient = Brush.linearGradient(listOf(SpiceBlue, SpiceBlueDark))

        Column(
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(gradient)
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.banner_eyebrow),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp,
                ),
                color = Color.White.copy(alpha = 0.75f),
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = moduleTitle,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.banner_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.80f),
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaChip(
                    icon = Icons.Outlined.AccessTime,
                    label = stringResource(R.string.banner_meta_minutes, estimatedMinutes),
                )
                Spacer(Modifier.width(8.dp))
                MetaChip(
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    label = pluralStringResource(R.plurals.banner_meta_questions, questionCount, questionCount),
                )
                Spacer(Modifier.width(8.dp))
                MetaChip(
                    icon = Icons.Outlined.Language,
                    label = languageLabel,
                )
            }

            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onStart,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = SpiceBlue,
                    ),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Text(
                        text = stringResource(R.string.banner_action_start),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(16.dp))
                TextButton(onClick = onSkip) {
                    Text(
                        text = stringResource(R.string.banner_action_skip),
                        color = Color.White.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.75f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF5F5F5)
@Composable
private fun LearnCardPreview() {
    MicroCoachingTheme {
        LearnCard(
            moduleTitle = "HTN Referral — do you know when to send to UHC vs CC?",
            questionCount = 1,
            estimatedMinutes = 3,
            onStart = {},
            onSkip = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
