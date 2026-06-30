package com.medtroniclabs.microcoaching.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Consistent blue top-bar used across the coaching module flow:
 * modules list, module detail, lesson player, and quiz screens.
 *
 * Mirrors the style of the `ModulesScreenHeader` composable inside
 * [ModuleReadyScreen] so all screens share the same visual language.
 *
 * @param title Centred title text. Pass an empty string to show only the back arrow.
 * @param subtitle Optional smaller line rendered directly below the [title]
 *               (e.g. "Last synced 2 Jun, 14:30"). Shares the title's alignment.
 * @param onBack Called when the back arrow is tapped.
 * @param onHome When non-null, renders a Home icon on the trailing edge that
 *               exits the SDK back to the host app (SPICE). Coexists with the
 *               [trailing] slot — the trailing content should add an end
 *               padding of ~52.dp so it sits to the left of the Home icon.
 * @param trailing Optional content rendered on the trailing edge (e.g. an action icon).
 *                 The slot already lives inside the header's [Box] — align it with
 *                 `Modifier.align(Alignment.CenterEnd)`.
 */
@Composable
fun SdkScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onHome: (() -> Unit)? = null,
    largeTitle: Boolean = false,
    titleAtStart: Boolean = false,
    trailing: @Composable (BoxScope.() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(SpiceBlue)
            .statusBarsPadding(),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 4.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint = Color.White,
            )
        }
        if (title.isNotBlank()) {
            // Reserve symmetric padding so the title stays centred whether or
            // not the Home icon and/or trailing slot are present.
            val endReserve = if (onHome != null) 104.dp else 56.dp
            // Trim the vertical padding slightly when a subtitle is present so the
            // two lines stay within the bar's existing height.
            val verticalPadding = if (subtitle.isNullOrBlank()) 14.dp else 8.dp
            Column(
                modifier = Modifier
                    .align(if (titleAtStart) Alignment.CenterStart else Alignment.Center)
                    .padding(start = 56.dp, top = verticalPadding, end = endReserve, bottom = verticalPadding),
                horizontalAlignment = if (titleAtStart) Alignment.Start else Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = (if (largeTitle) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                        .copy(fontWeight = FontWeight.SemiBold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (titleAtStart) TextAlign.Start else TextAlign.Center,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (titleAtStart) TextAlign.Start else TextAlign.Center,
                    )
                }
            }
        }
        trailing?.invoke(this)
        if (onHome != null) {
            IconButton(
                onClick = onHome,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Home,
                    contentDescription = stringResource(R.string.common_home),
                    tint = Color.White,
                )
            }
        }
    }
}
