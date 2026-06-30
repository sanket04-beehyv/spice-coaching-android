package com.medtroniclabs.microcoaching.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Horizontal "swipe-away" gesture shared by the morning refresher surfaces
 * ([MorningCard], [com.medtroniclabs.microcoaching.ui.learn.modules.components.QuizRefresherCard]).
 *
 * The card follows the finger left or right; as it travels it tilts and dips
 * slightly so the motion reads as a shallow **arc** rather than a flat slide.
 * Past a **breaking point** — either [dismissFraction] of the card width or a
 * fast fling ([velocityThreshold]) — it flies off-screen and [onDismiss] fires.
 * Short drags spring back to centre.
 *
 * @param onDismiss invoked once, after the off-screen animation completes.
 * @param enabled when false the gesture is inert (card is static).
 * @param resetKey when this value changes the gesture state resets to centre —
 *   pass the content identity (e.g. the module id) so a recycled card that now
 *   shows a different item starts from the centre instead of off-screen.
 */
fun Modifier.swipeToDismiss(
    onDismiss: () -> Unit,
    enabled: Boolean = true,
    resetKey: Any? = null,
    dismissFraction: Float = 0.35f,
    velocityThreshold: Float = 1000f,
    maxRotationDeg: Float = 12f,
    arcDipPx: Float = 56f,
): Modifier = composed {
    if (!enabled) return@composed this

    val scope = rememberCoroutineScope()
    val offsetX = remember(resetKey) { Animatable(0f) }
    var width by remember(resetKey) { mutableStateOf(1) }
    var dismissed by remember(resetKey) { mutableStateOf(false) }

    this
        .onSizeChanged { width = it.width.coerceAtLeast(1) }
        .graphicsLayer {
            val o = offsetX.value
            val fraction = (o / width).coerceIn(-1f, 1f)
            translationX = o
            // Tilt + dip proportional to travel → shallow arc instead of a flat slide.
            rotationZ = fraction * maxRotationDeg
            translationY = abs(fraction) * arcDipPx
        }
        .pointerInput(resetKey) {
            val tracker = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onHorizontalDrag = { change, dragAmount ->
                    change.consume()
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { offsetX.snapTo(offsetX.value + dragAmount) }
                },
                onDragEnd = {
                    if (dismissed) return@detectHorizontalDragGestures
                    val velocity = tracker.calculateVelocity().x
                    val travelled = offsetX.value
                    val pastBreakpoint = abs(travelled) > width * dismissFraction ||
                        abs(velocity) > velocityThreshold
                    if (pastBreakpoint) {
                        dismissed = true
                        val dir = if (travelled != 0f) sign(travelled) else sign(velocity).takeIf { it != 0f } ?: 1f
                        scope.launch {
                            offsetX.animateTo(dir * width * 1.5f, tween(durationMillis = 240))
                            onDismiss()
                        }
                    } else {
                        scope.launch { offsetX.animateTo(0f, spring()) }
                    }
                },
                onDragCancel = {
                    scope.launch { offsetX.animateTo(0f, spring()) }
                },
            )
        }
}
