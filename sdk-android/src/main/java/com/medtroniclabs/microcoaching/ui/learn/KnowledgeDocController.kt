package com.medtroniclabs.microcoaching.ui.learn

import android.content.Context
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.data.asset.AssetKind
import com.medtroniclabs.microcoaching.network.SourceDocumentPresignedUrlRequest
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.ui.document.DocumentFormat
import com.medtroniclabs.microcoaching.ui.document.formatFromExtension
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Owns the Knowledge section's document list and its download → preview flow.
 * Extracted from `LearnViewModel` so the (already large) ViewModel keeps only
 * the lesson/quiz/navigation concern.
 *
 * Scope: takes the host ViewModel's `viewModelScope`. The download/preview flow
 * is UI-lifecycle-bound — if the screen's VM is cleared mid-download, the
 * coroutine should cancel rather than leak or emit into a dead collector — so it
 * deliberately does NOT use the process-wide `sdkScope`.
 *
 * The document **list** is built reactively here (not in the SDK store) because
 * it's a modules-screen-only surface and needs a [Context] for the localised
 * default title; the cross-Activity sharing that justifies the store doesn't
 * apply. Reads the `published_source_document` table — the durable mirror of
 * `GET /sync/source-documents/published` — so the Knowledge grid lists **every**
 * published source document, not only those a module happens to reference.
 */
internal class KnowledgeDocController(
    private val scope: CoroutineScope,
    private val context: Context,
) {

    /** Deduped union of every module's `source_documents` — the Knowledge grid. */
    private val _knowledgeDocuments = MutableStateFlow<List<KnowledgeDocument>>(emptyList())
    val knowledgeDocuments: StateFlow<List<KnowledgeDocument>> = _knowledgeDocuments.asStateFlow()

    /**
     * Source-document ids already in the durable asset cache — drives the "view"
     * (cached) vs "download" affordance. Recomputed whenever the list changes and
     * after a successful download.
     */
    private val _cachedDocIds = MutableStateFlow<Set<String>>(emptySet())
    val cachedDocIds: StateFlow<Set<String>> = _cachedDocIds.asStateFlow()

    /** One-shot events for the download → preview flow (see [openKnowledgeDocument]). */
    private val _docEvents = MutableSharedFlow<DocEvent>(extraBufferCapacity = 8)
    val docEvents: SharedFlow<DocEvent> = _docEvents.asSharedFlow()

    /**
     * Live download progress for the Knowledge bottom progress surface. Non-null
     * while a document is downloading; null otherwise. (Streamable media — video/
     * audio — skips the download, so it never sets this.)
     */
    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    init {
        // Rebuild the document list + cached-id set on every catalogue change.
        scope.launch {
            val sdk = MicroCoachingSDK.getInstance()
            // First open after install/upgrade: the published catalogue may be
            // empty until the next inbound sync lands. Nudge a sync now (online
            // only) so the grid populates promptly instead of waiting for the
            // periodic worker tick.
            runCatching {
                if (sdk.database.publishedSourceDocumentDao().count() == 0 &&
                    sdk.config.backendUrl.isNotBlank() && sdk.isNetworkAvailable()
                ) {
                    sdk.syncCoordinator.triggerNow()
                }
            }
            sdk.database.publishedSourceDocumentDao().getAllOrdered()
                .collectLatest { entities ->
                    val docs = buildKnowledgeDocuments(entities)
                    _knowledgeDocuments.value = docs
                    _cachedDocIds.value = computeCachedDocIds(docs)
                }
        }
    }

    /**
     * Resolves [doc] to a local file via the durable
     * [com.medtroniclabs.microcoaching.data.asset.AssetCache] (fetching a presigned
     * URL on cache miss while online), emitting [DocEvent]s so the host shows
     * "Downloading…" then opens the preview — or reports offline-unavailable. The
     * cached file lives under `filesDir`, so a previously-opened document opens
     * offline. Keyed on the stable `sourceDocumentId` (dedup across modules).
     */
    fun openKnowledgeDocument(doc: KnowledgeDocument) {
        if (doc.sourceDocumentId.isBlank()) return

        // Streamable media (video/audio) is NOT downloaded — the preview fetches a
        // presigned URL and streams it via ExoPlayer. Open the preview immediately;
        // it routes by the filename extension.
        val fmt = formatFromExtension(doc.fileName)
        if (fmt == DocumentFormat.Video || fmt == DocumentFormat.Audio) {
            scope.launch {
                _docEvents.emit(DocEvent.Ready(doc.sourceDocumentId, doc.title, doc.fileName))
            }
            return
        }

        scope.launch {
            val label = doc.fileName ?: doc.title
            _downloadProgress.value = DownloadProgress(label, percent = null)
            val sdk = MicroCoachingSDK.getInstance()
            val file = runCatching {
                sdk.assetCache.localFile(
                    key = doc.sourceDocumentId,
                    kind = AssetKind.DOCUMENT,
                    onProgress = { downloaded, total ->
                        val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null
                        _downloadProgress.value = DownloadProgress(label, pct, downloaded, total)
                    },
                ) {
                    presignedUrlFor(sdk, doc.sourceDocumentId)
                }
            }.getOrNull()
            _downloadProgress.value = null
            if (file != null) {
                _docEvents.emit(DocEvent.Ready(doc.sourceDocumentId, doc.title, doc.fileName))
                // Newly downloaded → flip its grid affordance to "view".
                _cachedDocIds.value = computeCachedDocIds(_knowledgeDocuments.value)
            } else {
                _docEvents.emit(DocEvent.Unavailable)
            }
        }
    }

    /** Which of [docs] are already in the durable asset cache (view vs download). */
    private suspend fun computeCachedDocIds(docs: List<KnowledgeDocument>): Set<String> {
        val sdk = MicroCoachingSDK.getInstance()
        return docs.map { it.sourceDocumentId }
            .filter { runCatching { sdk.assetCache.isCached(it) }.getOrDefault(false) }
            .toSet()
    }

    /**
     * Map the published-catalogue rows into UI [KnowledgeDocument]s, preserving
     * the server-supplied order. Title falls back to filename → localised
     * default; thumbnail / filename come straight from the row (presigned URLs
     * fetched inline by the catalogue sync).
     */
    private fun buildKnowledgeDocuments(
        entities: List<com.medtroniclabs.microcoaching.data.db.entity.PublishedSourceDocumentEntity>,
    ): List<KnowledgeDocument> {
        val default = localized(R.string.chat_source_default)
        return entities.map { row ->
            KnowledgeDocument(
                sourceDocumentId = row.sourceDocumentId,
                title = row.title?.takeIf { it.isNotBlank() }
                    ?: row.originalFilename?.takeIf { it.isNotBlank() }
                    ?: default,
                fileName = row.originalFilename,
                thumbnailUrl = row.thumbnailUrl,
            )
        }
    }

    /**
     * A presigned GET URL for one source document. Prefers the URL already
     * persisted by the catalogue sync when it's still fresh (no extra round
     * trip); otherwise resolves a fresh one via the on-demand presigned
     * endpoint. Returns null if neither is available.
     */
    private suspend fun presignedUrlFor(sdk: MicroCoachingSDK, id: String): String? {
        val nowSec = System.currentTimeMillis() / 1000L
        val cached = runCatching { sdk.database.publishedSourceDocumentDao().getById(id) }.getOrNull()
        val stillFresh = cached?.presignedUrl?.takeIf {
            it.isNotBlank() && (cached.presignedExpiresAt ?: 0L) > nowSec
        }
        if (stillFresh != null) return stillFresh
        return runCatching {
            sdk.apiService.getSourceDocumentPresignedUrls(
                SourceDocumentPresignedUrlRequest(listOf(id)),
            ).body()?.urls?.firstOrNull { it.sourceDocumentId == id }?.presignedUrl
        }.getOrNull()
    }

    private fun localized(@androidx.annotation.StringRes resId: Int): String {
        val sdkLanguage = MicroCoachingSDK.getInstance().language
        val ctx = SdkLocaleHelper.wrap(context, sdkLanguage)
        return ctx.getString(resId)
    }
}
