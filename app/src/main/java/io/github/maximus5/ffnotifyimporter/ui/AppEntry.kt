package io.github.maximus5.ffnotifyimporter.ui

import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val monitored: Boolean,
)
