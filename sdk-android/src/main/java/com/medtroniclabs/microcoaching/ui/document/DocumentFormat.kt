package com.medtroniclabs.microcoaching.ui.document

import java.io.File

/**
 * Format routing for the in-app source-document viewer. Drives the switch in
 * [DocumentPreviewActivity]:
 *
 *  - [Pdf] → in-app `PdfPagerScreen` (supports page deep-link).
 *  - [Image] → in-app `ImageZoomScreen` (Coil + pinch-zoom).
 *  - [Video] / [Audio] → in-app ExoPlayer, **streamed** from the presigned URL.
 *  - [External] → hand off to the device browser / app chooser — covers Office
 *    docs (pptx/docx) and any extension we haven't built a native viewer for.
 */
internal enum class DocumentFormat { Pdf, Image, Video, Audio, External }

/**
 * Pure extension-based format for the **pre-download** decision (the streamable
 * media types are detected and streamed before any cache write). Returns null
 * when [nameOrPath] has no usable extension. Reuses [extensionFormat].
 */
internal fun formatFromExtension(nameOrPath: String?): DocumentFormat? =
    nameOrPath?.lowercase()?.substringAfterLast('.', "")?.ifBlank { null }?.let { extensionFormat(it) }

/** Maps a bare lowercase extension (no dot) to a [DocumentFormat], or null if unknown. */
private fun extensionFormat(ext: String): DocumentFormat? = when (ext) {
    "pdf" -> DocumentFormat.Pdf
    "jpg", "jpeg", "png", "webp", "gif", "bmp" -> DocumentFormat.Image
    "mp4", "mov", "mkv" -> DocumentFormat.Video
    // Per backend support_source_file_types.json, .webm is classified audio.
    "mp3", "wav", "m4a", "flac", "ogg", "webm" -> DocumentFormat.Audio
    else -> null
}

/**
 * Detect the format of a locally-cached document. Prefers the `storage_path`
 * extension when known (the online path has it from the presigned entry); falls
 * back to sniffing the file's magic bytes so it still works **offline** (cache
 * hit, no presigned fetch, no extension hint).
 */
internal fun detectFormat(file: File, storagePathHint: String?): DocumentFormat {
    storagePathHint?.lowercase()?.let { p ->
        when {
            p.endsWith(".pdf") -> return DocumentFormat.Pdf
            p.endsWith(".jpg") || p.endsWith(".jpeg") ||
                p.endsWith(".png") || p.endsWith(".webp") ||
                p.endsWith(".gif") -> return DocumentFormat.Image
        }
    }
    return sniffFormat(file)
}

/** Magic-byte sniff for offline format detection (no extension available). */
private fun sniffFormat(file: File): DocumentFormat {
    val header = ByteArray(12)
    val read = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
    if (read <= 0) return DocumentFormat.External

    fun matches(vararg bytes: Int): Boolean =
        read >= bytes.size && bytes.withIndex().all { (i, b) -> header[i].toInt() and 0xFF == b }

    return when {
        matches(0x25, 0x50, 0x44, 0x46) -> DocumentFormat.Pdf // "%PDF"
        matches(0xFF, 0xD8, 0xFF) -> DocumentFormat.Image // JPEG
        matches(0x89, 0x50, 0x4E, 0x47) -> DocumentFormat.Image // PNG
        matches(0x47, 0x49, 0x46, 0x38) -> DocumentFormat.Image // "GIF8"
        // WEBP: "RIFF"???? "WEBP"
        matches(0x52, 0x49, 0x46, 0x46) && read >= 12 &&
            header[8].toInt() and 0xFF == 0x57 && header[9].toInt() and 0xFF == 0x45 &&
            header[10].toInt() and 0xFF == 0x42 && header[11].toInt() and 0xFF == 0x50 -> DocumentFormat.Image
        else -> DocumentFormat.External
    }
}
