package com.medtroniclabs.microcoaching.ui.video

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.network.MediaUrlResolver
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fullscreen Media3/ExoPlayer playback for a rich-card video node.
 *
 * Lifecycle mirrors
 * [com.medtroniclabs.microcoaching.ui.document.DocumentPreviewActivity]:
 *  1. Read `src` / `object_name` from the launching Intent.
 *  2. Resolve a loadable URL via [MediaUrlResolver] (direct or presigned).
 *  3. Drive an [ExoPlayer] surface; network/resolve failures fall through to a
 *     localised "media unavailable" message.
 */
class VideoPlayerActivity : ComponentActivity() {

    internal sealed interface PlaybackState {
        data object Loading : PlaybackState
        data class Ready(val url: String) : PlaybackState
        data object Error : PlaybackState
    }

    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Loading)
    private val state: StateFlow<PlaybackState> = _state.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val src = intent.getStringExtra(EXTRA_SRC)
        val objectName = intent.getStringExtra(EXTRA_OBJECT_NAME)

        if (src.isNullOrBlank() && objectName.isNullOrBlank()) {
            finish()
            return
        }

        setContent {
            SdkLocalizedTheme {
                val current by state.collectAsState()
                VideoPlayerScreen(state = current, onBack = ::finish)
            }
        }

        lifecycleScope.launch {
            val url = MediaUrlResolver.resolve(src, objectName)
            _state.value = if (url.isNullOrBlank()) PlaybackState.Error else PlaybackState.Ready(url)
        }
    }

    companion object {
        private const val EXTRA_SRC = "extra_video_src"
        private const val EXTRA_OBJECT_NAME = "extra_video_object_name"

        /** Launch fullscreen playback for a video node ([src] or [objectName]). */
        fun start(context: Context, src: String?, objectName: String?) {
            val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                putExtra(EXTRA_SRC, src)
                putExtra(EXTRA_OBJECT_NAME, objectName)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun VideoPlayerScreen(
    state: VideoPlayerActivity.PlaybackState,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { SdkScreenHeader(title = stringResource(R.string.rich_video_title), onBack = onBack) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is VideoPlayerActivity.PlaybackState.Ready -> ExoPlayerSurface(url = state.url)
                VideoPlayerActivity.PlaybackState.Error -> Text(
                    text = stringResource(R.string.rich_media_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                )
                VideoPlayerActivity.PlaybackState.Loading -> CircularProgressIndicator()
            }
        }
    }
}
