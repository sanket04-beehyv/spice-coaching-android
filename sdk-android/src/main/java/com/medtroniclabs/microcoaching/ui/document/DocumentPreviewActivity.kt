package com.medtroniclabs.microcoaching.ui.document

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.data.asset.AssetKind
import com.medtroniclabs.microcoaching.data.asset.InsufficientStorageException
import com.medtroniclabs.microcoaching.network.SourceDocumentPresignedUrlRequest
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.common.SdkScreenHeader
import com.medtroniclabs.microcoaching.ui.video.ExoPlayerSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * In-app preview for source documents cited from chat citation chips.
 *
 * Resolves the document to a local file via the durable
 * [com.medtroniclabs.microcoaching.data.asset.AssetCache] (cache hit → works
 * offline; online miss → fetch a presigned URL, download, store under
 * `filesDir`), then routes by format ([detectFormat], extension hint + magic
 * bytes):
 *  - **PDF** → [PdfPagerScreen] — scrolls to [EXTRA_START_PAGE], per-page pinch-zoom.
 *  - **Image** → [ImageZoomScreen] — Coil + pinch-zoom.
 *  - **External** (Office, unknown) → `Intent.ACTION_VIEW` to the device
 *    browser (online only), then `finish()`.
 *
 * Used by both the chat citation chips and the Knowledge section. The cached
 * file is keyed on the stable `sourceDocumentId`, so a once-opened document
 * opens again **offline**. The presigned URL itself is still short-lived and
 * re-fetched only on a cache miss while online.
 */
class DocumentPreviewActivity : ComponentActivity() {

    internal sealed interface PreviewState {
        object Loading : PreviewState
        data class LoadedPdf(val file: File, val startPage: Int?) : PreviewState
        data class LoadedImage(val file: File) : PreviewState
        /** Streamable media (video/audio) — played by ExoPlayer from the presigned [url]. */
        data class LoadedMedia(val url: String) : PreviewState
        object HandedOffExternal : PreviewState
        /** Generic failure (offline miss, network error, presign failure). */
        data class Error(val hasExternalUrl: Boolean = false) : PreviewState
        /** Device is out of disk space — download cannot complete. */
        data class StorageFull(val hasExternalUrl: Boolean = false) : PreviewState
        /** File downloaded but not a PDF or image — offer to open with another app. */
        data class CannotPreview(val filename: String?, val hasExternalUrl: Boolean = false) : PreviewState
    }

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    private val state: StateFlow<PreviewState> = _state.asStateFlow()

    /** Last presigned URL we resolved; used by toolbar / failure-fallback to escape to a browser. */
    @Volatile private var lastUrl: String? = null

    /** Last `storage_path` from the presigned entry — the online format-detection hint. */
    @Volatile private var lastStoragePath: String? = null

    /** Local cached file — set once the asset resolves; used for FileProvider sharing when offline. */
    @Volatile private var localFile: File? = null

    /** MIME type inferred from the original filename extension; used when opening via FileProvider. */
    @Volatile private var localFileMime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceDocumentId = intent.getStringExtra(EXTRA_DOCUMENT_ID).orEmpty()
        val titleArg = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val startPage = intent.getIntExtra(EXTRA_START_PAGE, -1).takeIf { it > 0 }
        val originalFilename = intent.getStringExtra(EXTRA_ORIGINAL_FILENAME)

        if (sourceDocumentId.isBlank()) {
            Log.w(TAG, "DocumentPreviewActivity launched with blank documentId — finishing")
            finish()
            return
        }

        val sdkLang = runCatching { MicroCoachingSDK.getInstance().language }
            .getOrDefault(com.medtroniclabs.microcoaching.Language.ENGLISH)
        val localedContext = SdkLocaleHelper.wrap(this, sdkLang)
        val fallbackTitle = titleArg.takeIf { it.isNotBlank() }
            ?: localedContext.getString(R.string.chat_source_default)

        setContent {
            SdkLocalizedTheme {
                val current by state.collectAsState()
                DocumentPreviewScreen(
                    title = fallbackTitle,
                    state = current,
                    onBack = ::finish,
                    onOpenExternal = ::openLastUrlExternally,
                    onRetry = { retryResolve(sourceDocumentId, startPage, originalFilename) },
                )
            }
        }

        resolveAndRoute(sourceDocumentId, startPage, originalFilename)
    }

    private fun resolveAndRoute(sourceDocumentId: String, startPage: Int?, originalFilename: String? = null) {
        val sdk = runCatching { MicroCoachingSDK.getInstance() }.getOrNull()
        if (sdk == null) {
            Log.w(TAG, "MicroCoachingSDK not initialised — cannot open document")
            _state.value = PreviewState.Error()
            return
        }
        lifecycleScope.launch {
            // Streamable media (video/audio) — stream from the presigned URL via
            // ExoPlayer instead of downloading the whole (large) file to the cache.
            // Detected from the original filename's extension before any download.
            val hintFormat = formatFromExtension(originalFilename)
            if (hintFormat == DocumentFormat.Video || hintFormat == DocumentFormat.Audio) {
                val url = fetchPresignedUrl(sdk, sourceDocumentId)
                if (url != null) {
                    Log.i(TAG, "Routing → media stream ($hintFormat)")
                    _state.value = PreviewState.LoadedMedia(url)
                } else {
                    Log.w(TAG, "Media stream unavailable (offline / fetch failed) for $sourceDocumentId")
                    _state.value = PreviewState.Error(hasExternalUrl = false)
                }
                return@launch
            }

            val file = try {
                // Durable, offline-capable: cache hit returns the local file (no
                // network); online miss fetches a presigned URL, downloads & stores
                // it under filesDir. Offline miss → null → "unavailable".
                sdk.assetCache.localFile(sourceDocumentId, AssetKind.DOCUMENT) {
                    fetchPresignedUrl(sdk, sourceDocumentId)
                }
            } catch (e: InsufficientStorageException) {
                Log.w(TAG, "Insufficient storage for $sourceDocumentId")
                _state.value = PreviewState.StorageFull(hasExternalUrl = lastUrl != null)
                return@launch
            }
            if (file == null) {
                Log.w(TAG, "Document unavailable (offline miss / fetch failed) for $sourceDocumentId")
                _state.value = PreviewState.Error(hasExternalUrl = lastUrl != null)
                return@launch
            }
            // Stash the resolved file so openLastUrlExternally() can fall back to
            // FileProvider sharing when no presigned URL is available (cache hit, offline).
            localFile = file
            localFileMime = mimeFromFilename(originalFilename ?: lastStoragePath)
            when (detectFormat(file, lastStoragePath ?: originalFilename)) {
                DocumentFormat.Pdf -> {
                    Log.i(TAG, "Routing → PDF viewer (startPage=$startPage)")
                    _state.value = PreviewState.LoadedPdf(file, startPage)
                }
                DocumentFormat.Image -> {
                    Log.i(TAG, "Routing → Image viewer")
                    _state.value = PreviewState.LoadedImage(file)
                }
                // Video/Audio are streamed pre-download (handled above) and never
                // reach this file-based detection — folded in for exhaustiveness.
                DocumentFormat.External, DocumentFormat.Video, DocumentFormat.Audio -> {
                    val displayName = originalFilename
                        ?: lastStoragePath?.substringAfterLast('/')
                    Log.i(TAG, "Routing → cannot-preview (filename=$displayName, hasUrl=${lastUrl != null})")
                    // hasExternalUrl=true: we always have the local file at this point,
                    // so we can always offer opening externally (URL or FileProvider).
                    _state.value = PreviewState.CannotPreview(
                        filename = displayName,
                        hasExternalUrl = true,
                    )
                }
            }
        }
    }

    private fun retryResolve(sourceDocumentId: String, startPage: Int?, originalFilename: String? = null) {
        _state.value = PreviewState.Loading
        resolveAndRoute(sourceDocumentId, startPage, originalFilename)
    }

    /**
     * Fetches a fresh presigned GET URL for [sourceDocumentId], stashing
     * `storagePath` (format hint) and the URL (browser fallback). Returns null
     * on any failure / missing id, which [com.medtroniclabs.microcoaching.data.asset.AssetCache]
     * treats as a cache miss.
     */
    private suspend fun fetchPresignedUrl(sdk: MicroCoachingSDK, sourceDocumentId: String): String? {
        val response = runCatching {
            sdk.apiService.getSourceDocumentPresignedUrls(
                SourceDocumentPresignedUrlRequest(listOf(sourceDocumentId)),
            )
        }.getOrNull()
        if (response == null || !response.isSuccessful) {
            Log.w(TAG, "Presigned URL fetch failed: code=${response?.code()}")
            return null
        }
        val body = response.body()
        val entry = body?.urls?.firstOrNull { it.sourceDocumentId == sourceDocumentId }
        if (entry == null || entry.presignedUrl.isBlank()) {
            Log.w(TAG, "Presigned URL not in response (missing_ids=${body?.missingIds}) for $sourceDocumentId")
            return null
        }
        lastStoragePath = entry.storagePath
        lastUrl = entry.presignedUrl
        Log.i(TAG, "Resolved presigned URL for $sourceDocumentId (expires in ${entry.expiresSeconds}s)")
        return entry.presignedUrl
    }

    /**
     * "Open externally" action used by the toolbar (for PDFs/images) and the
     * CannotPreview screen (for unsupported formats).
     *
     * Priority:
     *  1. Presigned URL → browser / any URL-capable app (works online).
     *  2. Local cached file via FileProvider → device app chooser (works offline
     *     when the file is already in the cache).
     */
    private fun openLastUrlExternally() {
        val url = lastUrl
        if (url != null && openInBrowser(url)) {
            _state.value = PreviewState.HandedOffExternal
            finish()
            return
        }
        val file = localFile
        if (file != null && openFileWithApp(file, localFileMime ?: "*/*")) {
            _state.value = PreviewState.HandedOffExternal
            finish()
        }
    }

    private fun openFileWithApp(file: File, mime: String): Boolean {
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.microcoaching.provider", file)
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider.getUriForFile failed: ${e.message}")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            startActivity(intent)
            Log.i(TAG, "openFileWithApp: dispatched app chooser for mime=$mime")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "openFileWithApp: no app handles mime=$mime — ${e.message}")
            false
        }
    }

    private fun mimeFromFilename(filename: String?): String {
        val ext = filename?.substringAfterLast('.', "")?.lowercase() ?: ""
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    private fun openInBrowser(url: String): Boolean {
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        if (uri == null || uri.scheme?.startsWith("http") != true) {
            Log.w(TAG, "openInBrowser: not an http(s) URL — scheme=${uri?.scheme}")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            startActivity(intent)
            Log.i(TAG, "openInBrowser: dispatched to system browser")
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "openInBrowser: no browser registered — ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "DocumentPreviewActivity"
        private const val EXTRA_DOCUMENT_ID = "extra_source_document_id"
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_START_PAGE = "extra_start_page"
        private const val EXTRA_ORIGINAL_FILENAME = "extra_original_filename"

        /**
         * Launch the preview for a single source document.
         *
         * @param title Chip label the user just tapped — surfaced in the
         *   toolbar while the presigned URL resolves so the screen isn't
         *   empty during the fetch.
         * @param startPage 1-indexed PDF page to land on. Ignored for image
         *   and external formats. Null falls back to page 1.
         */
        fun start(
            context: Context,
            sourceDocumentId: String,
            title: String,
            startPage: Int? = null,
            originalFilename: String? = null,
        ) {
            val intent = Intent(context, DocumentPreviewActivity::class.java).apply {
                putExtra(EXTRA_DOCUMENT_ID, sourceDocumentId)
                putExtra(EXTRA_TITLE, title)
                if (startPage != null && startPage > 0) putExtra(EXTRA_START_PAGE, startPage)
                if (!originalFilename.isNullOrBlank()) putExtra(EXTRA_ORIGINAL_FILENAME, originalFilename)
                if (context !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun DocumentPreviewScreen(
    title: String,
    state: DocumentPreviewActivity.PreviewState,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    onRetry: () -> Unit,
) {
    // Trailing toolbar action — "Open in browser" — visible once a URL has
    // resolved. Lets the user escape to Chrome for features the in-app viewer
    // doesn't have yet (zoom, search, etc.). Hidden during loading / error so
    // it doesn't appear over an empty surface.
    val showOpenExternal = state is DocumentPreviewActivity.PreviewState.LoadedPdf ||
        state is DocumentPreviewActivity.PreviewState.LoadedImage
    Scaffold(
        topBar = {
            SdkScreenHeader(
                title = title,
                onBack = onBack,
                trailing = if (showOpenExternal) {
                    {
                        IconButton(
                            onClick = onOpenExternal,
                            modifier = Modifier.align(Alignment.CenterEnd),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = stringResource(R.string.chat_source_open_externally),
                                tint = Color.White,
                            )
                        }
                    }
                } else null,
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                is DocumentPreviewActivity.PreviewState.LoadedPdf -> PdfPagerScreen(
                    file = state.file,
                    startPage = state.startPage,
                    externalFallback = onOpenExternal,
                )
                is DocumentPreviewActivity.PreviewState.LoadedImage -> ImageZoomScreen(file = state.file)
                is DocumentPreviewActivity.PreviewState.LoadedMedia -> ExoPlayerSurface(url = state.url)
                is DocumentPreviewActivity.PreviewState.Error -> ErrorContent(
                    message = stringResource(R.string.chat_source_unavailable),
                    onRetry = onRetry,
                    onOpenExternal = if (state.hasExternalUrl) onOpenExternal else null,
                )
                is DocumentPreviewActivity.PreviewState.StorageFull -> ErrorContent(
                    message = stringResource(R.string.doc_error_storage_full),
                    onRetry = null,
                    onOpenExternal = if (state.hasExternalUrl) onOpenExternal else null,
                )
                is DocumentPreviewActivity.PreviewState.CannotPreview -> CannotPreviewContent(
                    filename = state.filename,
                    onOpenExternal = if (state.hasExternalUrl) onOpenExternal else null,
                )
                DocumentPreviewActivity.PreviewState.Loading,
                DocumentPreviewActivity.PreviewState.HandedOffExternal,
                -> CircularProgressIndicator()
            }
        }
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: (() -> Unit)?,
    onOpenExternal: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.quiz_try_again))
            }
        }
        if (onOpenExternal != null) {
            OutlinedButton(onClick = onOpenExternal) {
                Text(stringResource(R.string.chat_source_open_externally))
            }
        }
    }
}

@Composable
private fun CannotPreviewContent(
    filename: String?,
    onOpenExternal: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.doc_cannot_preview),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (!filename.isNullOrBlank()) {
            Text(
                text = filename,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
        if (onOpenExternal != null) {
            Button(onClick = onOpenExternal) {
                Text(stringResource(R.string.doc_open_with))
            }
        }
    }
}
