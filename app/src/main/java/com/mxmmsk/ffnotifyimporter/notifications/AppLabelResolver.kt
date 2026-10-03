package com.mxmmsk.ffnotifyimporter.notifications

import android.content.Context
import android.content.pm.PackageManager

/** Resolves a package name to its current display label, falling back to the package name. */
object AppLabelResolver {

    fun resolve(context: Context, packageName: String): String {
        val packageManager = context.packageManager
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
