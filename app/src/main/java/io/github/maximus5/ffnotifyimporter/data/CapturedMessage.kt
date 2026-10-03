package io.github.maximus5.ffnotifyimporter.data

data class CapturedMessage(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val timestampMillis: Long,
)
