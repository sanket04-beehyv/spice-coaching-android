package com.medtroniclabs.microcoaching.ui.learn

/**
 * A single source document surfaced in the Knowledge section — the deduped
 * union of every module's `source_documents` (see [LearnViewModel.knowledgeDocuments]).
 * Not persisted: derived from `module_cache` (`ModuleEntity.sourceDocuments`) on
 * every module-list emission.
 *
 * @param sourceDocumentId stable backend UUID — the key for presigned-URL fetch
 *   and [com.medtroniclabs.microcoaching.data.asset.AssetCache] dedup.
 * @param title display label (document title → original filename → default).
 * @param fileName original filename, shown in the "Downloading… <file>" snackbar.
 * @param thumbnailUrl best-effort thumbnail (the owning module's), nullable.
 */
data class KnowledgeDocument(
    val sourceDocumentId: String,
    val title: String,
    val fileName: String?,
    val thumbnailUrl: String?,
)

/**
 * One-shot UI events emitted by [LearnViewModel.openKnowledgeDocument] so the
 * host can launch the preview / show an error. Live download progress is a
 * separate continuous state ([LearnViewModel.downloadProgress]). Collected by
 * `CoachingNavGraph`.
 */
sealed interface DocEvent {
    /**
     * Document is ready to open (cached locally, or a streamable media type that
     * the preview will fetch+stream). [fileName] lets the preview route by
     * extension (video/audio → ExoPlayer stream; pdf/image → in-app; office → external).
     */
    data class Ready(val sourceDocumentId: String, val title: String, val fileName: String?) : DocEvent

    /** Offline and not cached (or fetch failed) — show "not available offline". */
    data object Unavailable : DocEvent
}

/**
 * Live download progress for the Knowledge bottom progress surface (see
 * [LearnViewModel.downloadProgress]).
 *
 * @param percent null = indeterminate (server sent no Content-Length).
 * @param downloadedBytes bytes received so far.
 * @param totalBytes total size, or ≤ 0 when unknown (no Content-Length).
 */
data class DownloadProgress(
    val fileName: String,
    val percent: Int?,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
)
