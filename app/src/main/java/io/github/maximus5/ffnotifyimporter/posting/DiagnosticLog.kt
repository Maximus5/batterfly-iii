package io.github.maximus5.ffnotifyimporter.posting

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small on-device log of what the automatic paths decided (which rule matched, whether a review
 * notification was posted, suppressed or superseded, what the shade sweep saw), so a report like "the
 * bank notification stayed" can be diagnosed from the real phone instead of guessed at. The user
 * copies it from About and pastes it into a bug report.
 *
 * Deliberately free of transaction data: no amounts, descriptions, counterparties or account names
 * - only rule labels (the user's own), flags, counts, time offsets and the first characters of dedup
 * hashes (enough to tell "same" from "different"). Capped at [MAX_BYTES]; the older half is dropped
 * when it fills. Never throws: logging must not be able to break the code it observes.
 */
object DiagnosticLog {

    private const val FILE_NAME = "diagnostic.log"
    private const val MAX_BYTES = 48 * 1024

    private val lock = Any()

    fun log(context: Context, line: String) {
        try {
            synchronized(lock) {
                val file = File(context.applicationContext.filesDir, FILE_NAME)
                if (file.exists() && file.length() > MAX_BYTES) {
                    val text = file.readText()
                    file.writeText(text.substring(text.length / 2).substringAfter('\n'))
                }
                val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText("$stamp $line\n")
            }
        } catch (e: Exception) {
            // Nothing sensible to do - see the class KDoc.
        }
    }

    fun read(context: Context): String = try {
        synchronized(lock) {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else ""
        }
    } catch (e: Exception) {
        ""
    }

    fun clear(context: Context) {
        try {
            synchronized(lock) { File(context.applicationContext.filesDir, FILE_NAME).delete() }
        } catch (e: Exception) {
            // see above
        }
    }

    /** First characters of a hash - enough to compare two, nothing that reveals the inputs. */
    fun short(hash: String): String = hash.take(6).ifEmpty { "-" }
}
