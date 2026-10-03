package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context

/**
 * Firefly III connection + posting-behavior settings. Base URL and personal access token are the
 * only genuinely secret values in this app (see AGENTS.md's Secrets section) - encrypted via
 * [KeystoreCipher] before being stored in an otherwise-plain `SharedPreferences` (not
 * `EncryptedSharedPreferences` - deprecated, see [KeystoreCipher]'s KDoc). Every other field here
 * is a plain, unencrypted value, same as every other prefs class in this app.
 */
class FireflyPrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBaseUrl(): String = decryptedString(KEY_BASE_URL)
    fun setBaseUrl(url: String) = putEncrypted(KEY_BASE_URL, url)

    fun getToken(): String = decryptedString(KEY_TOKEN)
    fun setToken(token: String) = putEncrypted(KEY_TOKEN, token)

    fun isConfigured(): Boolean = getBaseUrl().isNotBlank() && getToken().isNotBlank()

    /** Tag applied to a transaction whose amount was computed from a fetched exchange rate
     * rather than read straight off the notification - a plain, visible Firefly tag the user
     * names themselves, not hardcoded text this app invents. */
    fun getApproxConversionTag(): String = prefs.getString(KEY_APPROX_TAG, "").orEmpty()
    fun setApproxConversionTag(tag: String) = prefs.edit().putString(KEY_APPROX_TAG, tag).apply()

    /** Off by default - posting is a real side effect, the user opts in explicitly rather than
     * it happening the moment a connection is configured. */
    fun isAutoCreateEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CREATE, false)
    fun setAutoCreateEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_CREATE, enabled).apply()

    /** On by default - duplicate transactions (the same payment reported by two different
     * monitored apps) are the more likely failure mode once posting exists, see TODO.md. */
    fun isDedupEnabled(): Boolean = prefs.getBoolean(KEY_DEDUP_ENABLED, true)
    fun setDedupEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DEDUP_ENABLED, enabled).apply()

    /** Also on by default - a missed dedup (duplicate posted) is just annoying to clean up; a
     * false dedup (a genuine second transaction silently dropped) is actual data loss, so the
     * default favors comparing more fields, not fewer. */
    fun isDedupIncludeDescription(): Boolean = prefs.getBoolean(KEY_DEDUP_INCLUDE_DESCRIPTION, true)
    fun setDedupIncludeDescription(include: Boolean) =
        prefs.edit().putBoolean(KEY_DEDUP_INCLUDE_DESCRIPTION, include).apply()

    fun getDedupWindowMinutes(): Int = prefs.getInt(KEY_DEDUP_WINDOW_MINUTES, DEFAULT_DEDUP_WINDOW_MINUTES)
    fun setDedupWindowMinutes(minutes: Int) = prefs.edit().putInt(KEY_DEDUP_WINDOW_MINUTES, minutes).apply()

    private fun decryptedString(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        return KeystoreCipher.decrypt(stored).orEmpty()
    }

    private fun putEncrypted(key: String, value: String) {
        prefs.edit().putString(key, KeystoreCipher.encrypt(value)).apply()
    }

    companion object {
        const val DEFAULT_DEDUP_WINDOW_MINUTES = 5

        private const val PREFS_NAME = "firefly_connection"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_APPROX_TAG = "approx_conversion_tag"
        private const val KEY_AUTO_CREATE = "auto_create"
        private const val KEY_DEDUP_ENABLED = "dedup_enabled"
        private const val KEY_DEDUP_INCLUDE_DESCRIPTION = "dedup_include_description"
        private const val KEY_DEDUP_WINDOW_MINUTES = "dedup_window_minutes"
    }
}
