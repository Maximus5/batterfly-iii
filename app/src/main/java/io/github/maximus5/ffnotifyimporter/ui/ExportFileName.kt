package io.github.maximus5.ffnotifyimporter.ui

import android.content.Context
import io.github.maximus5.ffnotifyimporter.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun fileNameSlug(appName: String): String =
    appName.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')

internal fun exportFileName(context: Context, kind: String): String {
    val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    return "${fileNameSlug(context.getString(R.string.app_name))}-$kind-$date.json"
}
