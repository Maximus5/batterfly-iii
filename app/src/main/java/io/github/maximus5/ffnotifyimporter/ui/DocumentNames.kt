package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Best-effort human-readable file name for a Storage Access Framework `content://` [uri] (from
 * the `CreateDocument`/`OpenDocument` activity-result contracts used by `BackupFragment`/
 * `RulesListFragment`). SAF deliberately doesn't expose a real filesystem path for these - only a
 * display name, via [OpenableColumns.DISPLAY_NAME], and even that isn't guaranteed (a
 * `DocumentsProvider` can simply not report one) - null in that case rather than a half-guessed
 * path, so a completion toast can say "if we know it" and genuinely mean it.
 */
fun displayNameOf(context: Context, uri: Uri): String? {
    if (uri.scheme != "content") return uri.lastPathSegment
    return try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    } catch (e: Exception) {
        null
    }
}
