package com.mxmmsk.ffnotifyimporter.prefs

import android.content.Context

/**
 * Which installed apps' notifications we actually look at. This is app configuration, not
 * notification content, so unlike captured messages it is fine to persist it across restarts.
 */
class MonitoredAppsPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getMonitoredPackages(): Set<String> =
        prefs.getStringSet(KEY_MONITORED_PACKAGES, emptySet()).orEmpty().toSet()

    fun isMonitored(packageName: String): Boolean = packageName in getMonitoredPackages()

    fun setMonitored(packageName: String, monitored: Boolean) {
        val updated = getMonitoredPackages().toMutableSet()
        if (monitored) updated.add(packageName) else updated.remove(packageName)
        prefs.edit().putStringSet(KEY_MONITORED_PACKAGES, updated).apply()
    }

    companion object {
        private const val PREFS_NAME = "monitored_apps"
        private const val KEY_MONITORED_PACKAGES = "monitored_packages"
    }
}
