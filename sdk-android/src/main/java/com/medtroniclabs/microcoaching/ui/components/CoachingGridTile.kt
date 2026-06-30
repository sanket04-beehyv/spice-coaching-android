package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlue

/**
 * Self-contained "Coaching" tile for the SPICE home menu grid. The host drops this
 * into its grid via a `ComposeView` and only forwards [onClick]; the skipped-refresher
 * count badge is observed internally from [MicroCoachingSDK.skippedRefresherCount].
 * Visuals mirror the host's other grid tiles so it blends in.
 */
@Composable
fun CoachingGridTile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.coaching_tile_label),
) {
    val skippedCount by remember(Unit) {
        runCatching { MicroCoachingSDK.getInstance().skippedRefresherCount }
            .getOrDefault(kotlinx.coroutines.flow.MutableStateFlow(0))
    }.collectAsState()

    SdkLocalizedTheme {
        // `propagateMinConstraints` carries the parent's *min* width down so the tile
        // matches its sibling tiles in both layouts: an exact width (phone grid column,
        // tablet fixed-width flex item) arrives as min == max so the card fills it; a
        // content spec arrives as min == 0 so the card wraps. Outer padding leaves room
        // for the elevation shadow (mirrors the host tiles' cardUseCompatPadding).
        Box(modifier = modifier.padding(6.dp), propagateMinConstraints = true) {
            Card(
                onClick = onClick,
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(vertical = 16.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.School,
                        contentDescription = null,
                        tint = SpiceBlue,
                        modifier = Modifier.size(72.dp),
                    )
                    if (skippedCount > 0) {
                        SkipBadge(
                            count = skippedCount,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = SpiceBlue,
                    textAlign = TextAlign.Center,
                )
                }
            }
        }
    }
}

@Composable
private fun SkipBadge(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(0xFFD0342C)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (count > 9) "9+" else count.toString(),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            fontSize = 11.sp,
        )
    }
}
