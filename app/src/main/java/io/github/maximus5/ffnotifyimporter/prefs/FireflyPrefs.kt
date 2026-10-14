package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient

/**
 * Posting-behavior settings (tags, default account/currency, auto-create, dedup, built-in rules).
 * Plain, unencrypted values with no secrets - they are part of both the manual JSON backup and
 * Android Auto Backup (own file, [PREFS_NAME]).
 *
 * The connection (base URL, token, cleartext opt-in) is [SecureSettings] and is never backed up.
 * The accessors for it below only *delegate*, so the many call sites that already hold a
 * `FireflyPrefs` (and pass it on to `apiClient()` consumers) did not have to change; they add no
 * storage of their own. Anything backup-related must read the settings accessors here, never the
 * connection ones.
 *
 * Until v0.241 these settings shared the `firefly_connection` file with the secrets (so the whole
 * file had to stay out of every backup); [migrateFromConnectionFile] moves them over once.
 */
class FireflyPrefs(context: Context) {

    private val secure = SecureSettings(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateFromConnectionFile(context)
    }

    fun getBaseUrl(): String = secure.getBaseUrl()
    fun setBaseUrl(url: String) = secure.setBaseUrl(url)

    fun getToken(): String = secure.getToken()
    fun setToken(token: String) = secure.setToken(token)
    fun clearToken() = secure.clearToken()

    fun isConfigured(): Boolean = secure.isConfigured()

    fun isAllowCleartext(): Boolean = secure.isAllowCleartext()
    fun setAllowCleartext(allow: Boolean) = secure.setAllowCleartext(allow)

    fun apiClient(): FireflyApiClient = secure.apiClient()

    /** Copies the settings that used to live next to the secrets into this file, then removes
     * them from the old one. Idempotent and crash-safe: a key already present here is never
     * overwritten, and leftovers in the old file are removed on the next run. */
    private fun migrateFromConnectionFile(context: Context) {
        val old = context.getSharedPreferences(SecureSettings.PREFS_NAME, Context.MODE_PRIVATE)
        val toMove = settingsToMigrate(old.all.mapValues { it.value })
        if (toMove.isEmpty()) return
        val target = prefs.edit()
        for ((key, value) in toMove) {
            if (prefs.contains(key)) continue
            when (value) {
                is String -> target.putString(key, value)
                is Boolean -> target.putBoolean(key, value)
                is Int -> target.putInt(key, value)
            }
        }
        target.commit()
        val source = old.edit()
        toMove.keys.forEach { source.remove(it) }
        source.apply()
    }

    /** Tag applied to a transaction whose amount was computed from a fetched exchange rate
     * rather than read straight off the notification - a plain, visible Firefly tag the user
     * names themselves, not hardcoded text this app invents. */
    fun getApproxConversionTag(): String = prefs.getString(KEY_APPROX_TAG, "").orEmpty()
    fun setApproxConversionTag(tag: String) = prefs.edit().putString(KEY_APPROX_TAG, tag).apply()

    /** Used as `ParseRule.homeCurrency` whenever a rule's *own* currency is left blank - the
     * user's own request. A user-authored rule is still free to set its own `homeCurrency`
     * (unchanged, still the common case - most rules are scoped to one real account, already in a
     * known currency); this exists for the case where it can't be known in advance, most notably
     * `rules.BuiltInRules`'s generic fallback rules, which aren't scoped to any one account at
     * all. Merged in by [rules.RuleEngine] itself (a plain `String` constructor parameter, same
     * "external config injected as data, not a `Context`/`FireflyPrefs` dependency" shape its own
     * `currencySymbols`/`allTransactionsTags` already use) - `rules` stays free of any Android/
     * Firefly dependency either way. */
    fun getDefaultAccountCurrency(): String = prefs.getString(KEY_DEFAULT_ACCOUNT_CURRENCY, "").orEmpty()
    fun setDefaultAccountCurrency(currencyCode: String) =
        prefs.edit().putString(KEY_DEFAULT_ACCOUNT_CURRENCY, currencyCode).apply()

    /** The user's own account, used by `rules.RuleEngine` (`defaultSourceAccount`) whenever a rule
     * leaves *both* account sides blank: as the source of a withdrawal/transfer, as the destination
     * of a deposit. Applied when the draft is built, so the create screen, the duplicate checks
     * and the test-match dialog all see the same account (v0.206 - it used to be only a create-
     * screen pre-fill, which left the tap-time duplicate check with nothing to search on).
     * Onboarding-focused, the user's own reasoning: most users monitor one app, mostly card
     * payments from one account, so most `rules.BuiltInRules` matches can be one tap away from
     * "Create". Automatic creation still isn't affected: the draft keeps
     * `DraftIssue.ACCOUNT_SELECTION_REQUIRED` (the *rule* named no account), so
     * `posting.AutoCreateWorker` still stops at a review notification. */
    fun getDefaultSourceAccount(): String = prefs.getString(KEY_DEFAULT_SOURCE_ACCOUNT, "").orEmpty()
    fun setDefaultSourceAccount(accountName: String) =
        prefs.edit().putString(KEY_DEFAULT_SOURCE_ACCOUNT, accountName).apply()

    /** Comma-separated - one or more tags appended to *every* transaction this app creates,
     * regardless of which rule matched - the user's own request, so tags wanted on everything
     * don't have to be repeated in every single rule's own `tagsTemplate`. Raw storage shape
     * (comma-separated string, split by the caller) matches `ParseRule.tagsTemplate`'s own, not
     * a `List<String>` - merged in by [rules.RuleEngine] itself (a plain `String` constructor
     * parameter, same "external config injected as data, not a `Context`/`FireflyPrefs`
     * dependency" shape [rules.RuleEngine]'s own `currencySymbols` already uses) - `rules` stays
     * free of any Android/Firefly dependency either way. */
    fun getAllTransactionsTags(): String = prefs.getString(KEY_ALL_TRANSACTIONS_TAGS, "").orEmpty()
    fun setAllTransactionsTags(tags: String) = prefs.edit().putString(KEY_ALL_TRANSACTIONS_TAGS, tags).apply()

    /** Off by default - posting is a real side effect, the user opts in explicitly rather than
     * it happening the moment a connection is configured. */
    fun isAutoCreateEnabled(): Boolean = prefs.getBoolean(KEY_AUTO_CREATE, false)
    fun setAutoCreateEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_AUTO_CREATE, enabled).apply()

    /** On by default - duplicate transactions (the same payment reported by two different
     * monitored apps) are the more likely failure mode once posting exists, see TODO.md. */
    fun isDedupEnabled(): Boolean = prefs.getBoolean(KEY_DEDUP_ENABLED, true)
    fun setDedupEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DEDUP_ENABLED, enabled).apply()

    /** Off by default - the user's own call: most rules derive their description straight from
     * the notification text, which varies more (timestamps, formatting quirks across runs of the
     * same bank's app) than the amount/account/date fields dedup already compares without this,
     * so leaving it on by default caused more missed dedups (duplicates posted) than it prevented
     * false ones. Still available for a rule set where description actually is a stable, reliable
     * distinguishing field. */
    fun isDedupIncludeDescription(): Boolean = prefs.getBoolean(KEY_DEDUP_INCLUDE_DESCRIPTION, false)
    fun setDedupIncludeDescription(include: Boolean) =
        prefs.edit().putBoolean(KEY_DEDUP_INCLUDE_DESCRIPTION, include).apply()

    fun getDedupWindowMinutes(): Int = prefs.getInt(KEY_DEDUP_WINDOW_MINUTES, DEFAULT_DEDUP_WINDOW_MINUTES)
    fun setDedupWindowMinutes(minutes: Int) = prefs.edit().putInt(KEY_DEDUP_WINDOW_MINUTES, minutes).apply()

    /** On by default - the whole point of `rules.BuiltInRules` is to give a user with a blank (or
     * non-matching) Rules list something that still works without hand-authoring a regex, so the
     * default has to actually provide that out of the box rather than requiring a settings trip
     * first. One shared flag for both built-in rules, not a per-rule one - they always move
     * together (see `rules.BuiltInRules`'s own KDoc for why there are two at all), surfaced as a
     * single toggle at the bottom of `RulesListFragment`'s list rather than a `TransactionCreationFragment`
     * switch, since it's a property of the rules list, not a general creation/dedup setting. */
    fun isBuiltInRulesEnabled(): Boolean = prefs.getBoolean(KEY_BUILT_IN_RULES_ENABLED, true)
    fun setBuiltInRulesEnabled(enabled: Boolean) =
        prefs.edit().putBoolean(KEY_BUILT_IN_RULES_ENABLED, enabled).apply()

    companion object {
        const val DEFAULT_DEDUP_WINDOW_MINUTES = 5

        private const val PREFS_NAME = "firefly_settings"
        private const val KEY_APPROX_TAG = "approx_conversion_tag"
        private const val KEY_ALL_TRANSACTIONS_TAGS = "all_transactions_tag"
        private const val KEY_DEFAULT_ACCOUNT_CURRENCY = "default_account_currency"
        private const val KEY_DEFAULT_SOURCE_ACCOUNT = "default_source_account"
        private const val KEY_AUTO_CREATE = "auto_create"
        private const val KEY_DEDUP_ENABLED = "dedup_enabled"
        private const val KEY_DEDUP_INCLUDE_DESCRIPTION = "dedup_include_description"
        private const val KEY_DEDUP_WINDOW_MINUTES = "dedup_window_minutes"
        private const val KEY_BUILT_IN_RULES_ENABLED = "built_in_rules_enabled"

        /** Every key this class stores - also what [migrateFromConnectionFile] lifts out of the
         * old, secrets-bearing file. Anything else in that file (URL, token, cleartext flag)
         * stays where it is. */
        internal val MIGRATED_KEYS = setOf(
            KEY_APPROX_TAG, KEY_ALL_TRANSACTIONS_TAGS, KEY_DEFAULT_ACCOUNT_CURRENCY,
            KEY_DEFAULT_SOURCE_ACCOUNT, KEY_AUTO_CREATE, KEY_DEDUP_ENABLED,
            KEY_DEDUP_INCLUDE_DESCRIPTION, KEY_DEDUP_WINDOW_MINUTES, KEY_BUILT_IN_RULES_ENABLED,
        )

        /** The entries of the old `firefly_connection` file that belong in the settings file.
         * Pure, so the secrets-never-move rule is unit tested. */
        internal fun settingsToMigrate(oldFile: Map<String, Any?>): Map<String, Any> =
            oldFile.filterKeys { it in MIGRATED_KEYS }.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }
}
