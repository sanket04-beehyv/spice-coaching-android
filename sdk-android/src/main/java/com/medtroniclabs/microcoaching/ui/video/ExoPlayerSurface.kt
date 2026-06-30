package com.medtroniclabs.microcoaching.ui.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Shared Media3/ExoPlayer surface — plays a video **or** audio [url] (the
 * `PlayerView` shows transport controls over a blank surface for audio-only
 * media). Auto-plays and releases the player on dispose.
 *
 * Used by [VideoPlayerActivity] (rich-card video) and
 * [com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity]
 * (Knowledge source-document video/audio, streamed from the presigned URL).
 */
@Composable
internal fun ExoPlayerSurface(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
    )
}
