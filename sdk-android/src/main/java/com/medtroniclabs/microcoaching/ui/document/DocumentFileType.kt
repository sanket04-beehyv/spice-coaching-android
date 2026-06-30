package com.medtroniclabs.microcoaching.ui.document

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.ui.graphics.vector.ImageVector
import com.medtroniclabs.microcoaching.R

/** Broad file-type category inferred from a filename extension. */
enum class DocumentFileType {
    PDF, PRESENTATION, SPREADSHEET, DOCUMENT, IMAGE, GENERIC;

    companion object {
        fun fromFilename(filename: String?): DocumentFileType {
            val ext = filename?.substringAfterLast('.', "")?.lowercase()?.ifBlank { null }
                ?: return GENERIC
            return fromExtension(ext)
        }

        fun fromExtension(ext: String): DocumentFileType = when (ext) {
            "pdf" -> PDF
            "ppt", "pptx", "odp", "key" -> PRESENTATION
            "xls", "xlsx", "ods", "csv" -> SPREADSHEET
            "doc", "docx", "odt", "txt", "rtf" -> DOCUMENT
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tiff", "tif" -> IMAGE
            else -> GENERIC
        }
    }
}

/** Material icon that best represents this file type. */
val DocumentFileType.icon: ImageVector
    get() = when (this) {
        DocumentFileType.PDF -> Icons.Outlined.PictureAsPdf
        DocumentFileType.PRESENTATION -> Icons.Outlined.Slideshow
        DocumentFileType.SPREADSHEET -> Icons.Outlined.TableChart
        DocumentFileType.DOCUMENT -> Icons.Outlined.Description
        DocumentFileType.IMAGE -> Icons.Outlined.Image
        DocumentFileType.GENERIC -> Icons.Outlined.InsertDriveFile
    }

/** Short localised label for this file type (e.g. "PDF", "Slides"). */
@get:StringRes
val DocumentFileType.labelRes: Int
    get() = when (this) {
        DocumentFileType.PDF -> R.string.doc_type_pdf
        DocumentFileType.PRESENTATION -> R.string.doc_type_presentation
        DocumentFileType.SPREADSHEET -> R.string.doc_type_spreadsheet
        DocumentFileType.DOCUMENT -> R.string.doc_type_document
        DocumentFileType.IMAGE -> R.string.doc_type_image
        DocumentFileType.GENERIC -> R.string.doc_type_file
    }
