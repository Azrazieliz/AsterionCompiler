package com.asterion.compiler.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.asterion.compiler.worksheet.WorksheetReference
import com.asterion.compiler.worksheet.WorksheetRole
import com.asterion.compiler.worksheet.WorksheetSelection
import com.asterion.compiler.utilities.ImageFilePolicy
import java.util.Locale

interface PromptClipboard {
    fun copy(label: String, text: String)
}

class AndroidPromptClipboard(context: Context) : PromptClipboard {
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun copy(label: String, text: String) {
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

class RecordingPromptClipboard : PromptClipboard {
    var copiedLabel: String? = null
        private set
    var copiedText: String? = null
        private set

    override fun copy(label: String, text: String) {
        copiedLabel = label
        copiedText = text
    }
}

interface WorksheetSourceReader {
    fun read(selection: WorksheetSelection): Result<Map<String, ByteArray>>

    fun persistReadPermission(uri: Uri)

    fun createReference(uri: Uri, role: WorksheetRole): WorksheetReference
}

class AndroidWorksheetSourceReader(
    private val contentResolver: ContentResolver,
) : WorksheetSourceReader {
    override fun read(selection: WorksheetSelection): Result<Map<String, ByteArray>> = runCatching {
        selection.allSheets.associate { worksheet ->
            val uri = Uri.parse(worksheet.sourceIdentifier)
            val bytes = requireNotNull(contentResolver.openInputStream(uri)) {
                "Unable to open ${worksheet.displayName}."
            }.use { stream -> stream.readBytes() }
            worksheet.sourceIdentifier to bytes
        }
    }

    override fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun createReference(uri: Uri, role: WorksheetRole): WorksheetReference {
        val displayName = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "worksheet.png"

        return WorksheetReference(
            id = "${role.name.lowercase(Locale.US)}-$uri",
            role = role,
            displayName = displayName,
            sourceIdentifier = uri.toString(),
            mimeType = contentResolver.getType(uri) ?: ImageFilePolicy.PngMimeType,
        )
    }
}

interface WorksheetImageLoader {
    fun loadThumbnail(worksheet: WorksheetReference, targetPixels: Int): Bitmap?
}

class AndroidWorksheetImageLoader(
    private val contentResolver: ContentResolver,
) : WorksheetImageLoader {
    override fun loadThumbnail(worksheet: WorksheetReference, targetPixels: Int): Bitmap? = runCatching {
        val uri = Uri.parse(worksheet.sourceIdentifier)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image dimensions." }
        val sampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetPixels)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, options) }
    }.getOrNull()

    private fun calculateSampleSize(width: Int, height: Int, targetPixels: Int): Int {
        var sampleSize = 1
        while (width / sampleSize > targetPixels * 2 || height / sampleSize > targetPixels * 2) {
            sampleSize *= 2
        }
        return sampleSize
    }
}