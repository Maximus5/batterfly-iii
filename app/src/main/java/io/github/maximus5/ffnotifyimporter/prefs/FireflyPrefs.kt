package io.github.maximus5.ffnotifyimporter.prefs

import android.content.Context
import io.github.maximus5.ffnotifyimporter.network.FireflyApiClient
import io.github.maximus5.ffnotifyimporter.network.isCleartextUrl

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
    fun clearToken() = prefs.edit().remove(KEY_TOKEN).apply()

    fun isConfigured(): Boolean = getBaseUrl().isNotBlank() && getToken().isNotBlank()

    /** Off by default: the bearer token is never sent over plain `http://` unless the user opts in
     * on the connection screen. Installs that already had an `http://` URL saved before this
     * existed are migrated to on once ([migrateAllowCleartext]), so they keep working. */
    fun isAllowCleartext(): Boolean = prefs.getBoolean(KEY_ALLOW_CLEARTEXT, false)
    fun setAllowCleartext(allow: Boolean) = prefs.edit().putBoolean(KEY_ALLOW_CLEARTEXT, allow).apply()

    /** The one way to build an API client from saved settings, so no call site can forget the
     * cleartext opt-in. */
    fun apiClient(): FireflyApiClient = FireflyApiClient(getBaseUrl(), getToken(), isAllowCleartext())

    init {
        migrateAllowCleartext()
    }

    private fun migrateAllowCleartext() {
        if (prefs.contains(KEY_ALLOW_CLEARTEXT)) return
        setAllowCleartext(isCleartextUrl(getBaseUrl()))
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

    /** Pre-fills `ui.CreateTransactionFragment`'s source account field on create, but **only**
     * when a match leaves *both* account sides blank (the user's own explicit scoping, not "source
     * blank" alone - pre-filling source unconditionally could turn a legitimate DEPOSIT rule's own
     * already-filled destination into a wrongly-inferred TRANSFER once source also looked like a
     * real asset account, see `CreateTransactionFragment.inferKindFromAccounts`). Onboarding-
     * focused, the user's own reasoning: most users monitor one app, mostly card payments from one
     * account, so most `rules.BuiltInRules` matches can be one tap away from "Create" instead of
     * requiring the account to be picked by hand every single time. A *display-only* convenience -
     * deliberately never read by `rules.RuleEngine`/`posting.AutoCreateWorker`, so automatic
     * creation still requires a rule (built-in or user-authored) to actually resolve an account on
     * its own, exactly as before this existed. */
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
        private const val KEY_ALLOW_CLEARTEXT = "allow_cleartext"
        private const val KEY_APPROX_TAG = "approx_conversion_tag"
        private const val KEY_ALL_TRANSACTIONS_TAGS = "all_transactions_tag"
        private const val KEY_DEFAULT_ACCOUNT_CURRENCY = "default_account_currency"
        private const val KEY_DEFAULT_SOURCE_ACCOUNT = "default_source_account"
        private const val KEY_AUTO_CREATE = "auto_create"
        private const val KEY_DEDUP_ENABLED = "dedup_enabled"
        private const val KEY_DEDUP_INCLUDE_DESCRIPTION = "dedup_include_description"
        private const val KEY_DEDUP_WINDOW_MINUTES = "dedup_window_minutes"
        private const val KEY_BUILT_IN_RULES_ENABLED = "built_in_rules_enabled"
    }
}
