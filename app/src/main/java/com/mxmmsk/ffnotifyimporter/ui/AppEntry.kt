package com.mxmmsk.ffnotifyimporter.ui

import android.graphics.drawable.Drawable

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    val monitored: Boolean,
)
