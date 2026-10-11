package io.github.maximus5.ffnotifyimporter.posting

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/**
 * [this] with the app's own language applied (Android's per-app language, 13+). Background code -
 * the workers that post notifications - runs on the plain application context, and on at least
 * one real device (Samsung) that context stayed on the *system* language: with the app
 * set to German, "Transaction created" still came out in English. Reading the app locales straight
 * from [LocaleManager] and wrapping the context in them makes those strings follow the app
 * language like the screens do. A no-op below Android 13 (no per-app language there) or when the
 * app follows the system language.
 */
fun Context.withAppLocale(): Context {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val locales = getSystemService(LocaleManager::class.java)?.applicationLocales ?: return this
    if (locales.isEmpty) return this
    val configuration = Configuration(resources.configuration).apply { setLocales(locales) }
    return createConfigurationContext(configuration)
}
