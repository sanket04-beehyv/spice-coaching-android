package com.medtroniclabs.microcoaching.ui.learn.modules.components

import android.content.Context
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ui.learn.DownloadProgress

/**
 * Bottom progress surface shown while a Knowledge document downloads. Determinate
 * when [DownloadProgress.percent] is known (server sent a Content-Length), else
 * an indeterminate bar. Auto-hidden by the caller when progress is null.
 *
 * Hosted by `CoachingNavGraph`, driven by `LearnViewModel.downloadProgress`.
 */
@Composable
internal fun KnowledgeDownloadBar(
    progress: DownloadProgress,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        val context = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.knowledge_downloading, progress.fileName),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress.percent != null) {
                LinearProgressIndicator(
                    progress = { progress.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            // Secondary line: "1.2 MB / 4.5 MB  ·  45%" — Formatter auto-picks the
            // significant unit (KB/MB/GB). Omitted parts are dropped gracefully.
            downloadMetaLine(context, progress)?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.End),
                )
            }
        }
    }
}

/**
 * Builds the size/percent line, e.g. "1.2 MB / 4.5 MB  ·  45%". Uses
 * [Formatter.formatShortFileSize] so the unit (KB/MB/GB) tracks the magnitude
 * and locale. Returns null at the very start (no bytes yet, no total).
 */
private fun downloadMetaLine(context: Context, p: DownloadProgress): String? {
    val size = when {
        p.totalBytes > 0L ->
            "${Formatter.formatShortFileSize(context, p.downloadedBytes)} / " +
                Formatter.formatShortFileSize(context, p.totalBytes)
        p.downloadedBytes > 0L -> Formatter.formatShortFileSize(context, p.downloadedBytes)
        else -> null
    }
    val pct = p.percent?.let { "$it%" }
    return listOfNotNull(size, pct).joinToString("  ·  ").ifBlank { null }
}
