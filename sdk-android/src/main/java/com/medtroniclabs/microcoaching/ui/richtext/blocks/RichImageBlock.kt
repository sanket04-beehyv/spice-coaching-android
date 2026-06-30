package com.medtroniclabs.microcoaching.ui.richtext.blocks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.asset.rememberCachedImageFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import androidx.compose.foundation.Image
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.content.richtext.RichBlock
import com.medtroniclabs.microcoaching.network.MediaUrlResolver
import com.medtroniclabs.microcoaching.ui.theme.SpiceBlueContainer

/**
 * Renders a TipTap image node. Resolves the loadable URL via [MediaUrlResolver]
 * (direct `url` or presigned `object_name`), then loads with Coil. Shows a themed
 * placeholder while resolving/loading and an "unavailable" box on failure so a
 * broken media reference never collapses the card silently.
 */
@Composable
internal fun RichImageBlock(image: RichBlock.Image, modifier: Modifier = Modifier) {
    // Stable cache key: prefer the media object_name; else the presigned URL's
    // path (signature-independent). Resolves to a locally-cached file so the
    // image renders offline after the first online view.
    val key = remember(image.objectName, image.src) {
        (image.objectName?.takeIf { it.isNotBlank() }
            ?: MicroCoachingSDK.getInstance().assetCache.stableKeyForUrl(image.src))
            .also {
                Log.d(
                    "RichImageBlock",
                    "resolve start: objectName=${image.objectName} src=${image.src} -> key=$it",
                )
            }
    }
    val file by rememberCachedImageFile(key) {
        MediaUrlResolver.resolve(image.src, image.objectName)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(SpiceBlueContainer.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        if (file == null) {
            CircularProgressIndicator()
            return@Box
        }
        val painter = rememberAsyncImagePainter(model = file)
        when (val state = painter.state) {
            is AsyncImagePainter.State.Loading -> CircularProgressIndicator()
            is AsyncImagePainter.State.Error -> {
                Log.w(
                    "RichImageBlock",
                    "Coil failed to load cached image file=$file: ${state.result.throwable}",
                    state.result.throwable,
                )
                Text(
                    text = stringResource(R.string.rich_media_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is AsyncImagePainter.State.Success ->
                Log.d("RichImageBlock", "Coil loaded cached image file=$file")
            else -> Unit
        }
        Image(
            painter = painter,
            contentDescription = image.alt ?: stringResource(R.string.rich_image_content_description),
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.Fit,
        )
    }
}
