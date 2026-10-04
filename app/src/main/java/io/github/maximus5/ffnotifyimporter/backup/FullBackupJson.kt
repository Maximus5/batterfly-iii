package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionRecord
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
import io.github.maximus5.ffnotifyimporter.rules.RuleKind
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Everything *except* the Firefly III connection (base URL + personal access token) -
 * deliberately excluded, not an oversight: it's the one genuinely secret value in this app
 * (AGENTS.md's Secrets section), `KeystoreCipher`-encrypted against a key that lives in this
 * device's Android Keystore and wouldn't even decrypt on a different device or after an app
 * reinstall, and a backup file is exactly the kind of thing that ends up in a cloud folder or
 * gets shared - not somewhere a bearer token belongs, encrypted or not. The user re-enters it
 * after a restore.
 *
 * [messages]' `fireflyTransactionId` *is* included, and so are [rules]'
 * `sourceAccountId`/`destinationAccountId` - unlike `RulesJson`'s deliberate exclusion of the
 * latter two. That exclusion is correct for `RulesJson` (a *sharing* format - a device-specific
 * Firefly account id is meaningless once it travels to a different person/instance), but this
 * file isn't that: a full backup is a same-device safety net (it doesn't even touch the Firefly
 * connection's base URL - restoring onto a materially different instance was never really this
 * feature's job) and `RulesDb.add` always assigns a fresh row id on restore regardless, so there
 * was never a good independent argument for *also* dropping the account ids here - carrying over
 * `RulesJson`'s exclusion by analogy, instead of re-deriving it for this different use case, was
 * the same mistake that dropped `fireflyTransactionId` in an earlier version of this file: a
 * restored message silently lost its "#1234" link even though the transaction genuinely still
 * existed in Firefly, and a restored rule silently lost a still-valid account id, forcing a
 * needless re-pick. (`RuleEngine.buildDraft` already sends `sourceAccountId`/`destinationAccountId`
 * to Firefly with no freshness check against the current connection either way - a rule that's
 * simply never been touched carries exactly the same staleness risk if the user points Settings
 * at a different Firefly instance, so this file isn't introducing a new category of risk by
 * keeping the ids, only removing an unforced loss of working data.) [createdTransactions] mirrors
 * `CreatedTransactionsDb` for the same reason - without it, the dedup log would come back empty
 * after a restore onto a fresh install, silently disabling duplicate detection for everything
 * created before the backup.
 */
data class FullBackupData(
    val monitoredPackages: Set<String>,
    val retentionDays: Int,
    val currencies: List<CurrencyEntry>,
    val rules: List<ParseRule>,
    val messages: List<CapturedMessage>,
    val createdTransactions: List<CreatedTransactionRecord> = emptyList(),
)

fun fullBackupToJson(data: FullBackupData): String {
    val root = JSONObject()
    root.put("monitoredPackages", JSONArray(data.monitoredPackages.toList()))
    root.put("retentionDays", data.retentionDays)
    root.put(
        "currencies",
        JSONArray().apply {
            data.currencies.forEach { put(JSONObject().put("code", it.code).put("symbol", it.symbol)) }
        },
    )
    // Not rulesToJson - that format deliberately drops sourceAccountId/destinationAccountId for
    // its own (correct, for a *sharing* format) reasons; this is a same-device safety net, so
    // those ids ride along here instead. Only `id` itself is excluded - RulesDb.add always
    // assigns a fresh one on restore regardless, so there'd be nothing to round-trip it for.
    root.put("rules", JSONArray().apply { data.rules.forEach { put(it.toBackupJson()) } })
    root.put(
        "messages",
        JSONArray().apply {
            data.messages.forEach { message ->
                put(
                    JSONObject()
                        .put("packageName", message.packageName)
                        .put("appLabel", message.appLabel)
                        .put("title", message.title)
                        .put("text", message.text)
                        .put("timestampMillis", message.timestampMillis)
                        .put("fireflyTransactionId", message.fireflyTransactionId),
                )
            }
        },
    )
    root.put(
        "createdTransactions",
        JSONArray().apply {
            data.createdTransactions.forEach { record ->
                put(
                    JSONObject()
                        .put("hash", record.hash)
                        .put("dateMillis", record.dateMillis)
                        .put("fireflyTransactionId", record.fireflyTransactionId),
                )
            }
        },
    )
    return root.toString(2)
}

/**
 * Null if [json] isn't shaped like a backup at all (missing `monitoredPackages`, the one section
 * that's always present even on a completely fresh install). A malformed *section* within an
 * otherwise-valid file falls back to empty for that section rather than failing the whole
 * restore - same "one bad part doesn't sink everything" reasoning as [rulesFromJson].
 */
fun fullBackupFromJson(json: String): FullBackupData? {
    val root = try {
        JSONObject(json)
    } catch (e: JSONException) {
        return null
    }
    val monitoredPackages = root.optJSONArray("monitoredPackages")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    } ?: return null

    val currencies = root.optJSONArray("currencies")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            val code = entry.optString("code").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            CurrencyEntry(code, entry.optString("symbol", ""))
        }
    }.orEmpty()

    val rules = root.optJSONArray("rules")?.let { arr ->
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.toBackupRuleOrNull() }
    }.orEmpty()

    val messages = root.optJSONArray("messages")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            val packageName = entry.optString("packageName").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val timestamp = entry.optLong("timestampMillis", -1L)
            if (timestamp < 0) return@mapNotNull null
            CapturedMessage(
                packageName = packageName,
                appLabel = entry.optString("appLabel", packageName),
                title = entry.optString("title", ""),
                text = entry.optString("text", ""),
                timestampMillis = timestamp,
                fireflyTransactionId = entry.optString("fireflyTransactionId").takeIf { it.isNotBlank() },
            )
        }
    }.orEmpty()

    val createdTransactions = root.optJSONArray("createdTransactions")?.let { arr ->
        (0 until arr.length()).mapNotNull { i ->
            val entry = arr.optJSONObject(i) ?: return@mapNotNull null
            val hash = entry.optString("hash").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val fireflyTransactionId = entry.optString("fireflyTransactionId").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dateMillis = entry.optLong("dateMillis", -1L)
            if (dateMillis < 0) return@mapNotNull null
            CreatedTransactionRecord(hash, dateMillis, fireflyTransactionId)
        }
    }.orEmpty()

    return FullBackupData(
        monitoredPackages = monitoredPackages,
        retentionDays = root.optInt("retentionDays", 0),
        currencies = currencies,
        rules = rules,
        messages = messages,
        createdTransactions = createdTransactions,
    )
}

/** A full backup's own rule shape - everything [RulesJson.kt]'s sharing format has, *plus*
 * `sourceAccountId`/`destinationAccountId` (see [FullBackupData]'s KDoc for why the two formats
 * disagree on that). Still excludes `id` - never round-tripped either way, `RulesDb.add` assigns
 * a fresh one on restore regardless. */
private fun ParseRule.toBackupJson(): JSONObject = JSONObject().apply {
    put("label", label)
    put("packageNames", JSONArray(packageNames.toList()))
    put("kind", kind.name)
    put("sourceAccountName", sourceAccountName)
    put("destinationAccountName", destinationAccountName)
    put("sourceAccountId", sourceAccountId)
    put("destinationAccountId", destinationAccountId)
    put("homeCurrency", homeCurrency)
    put("pattern", pattern)
    put("descriptionTemplate", descriptionTemplate)
    put("notesTemplate", notesTemplate)
    put("categoryTemplate", categoryTemplate)
    put("tagsTemplate", tagsTemplate)
    put("enabled", enabled)
    put("dateFormat", dateFormat)
}

private fun JSONObject.toBackupRuleOrNull(): ParseRule? {
    val kind = try {
        RuleKind.valueOf(optString("kind"))
    } catch (e: IllegalArgumentException) {
        return null
    }
    val pattern = optString("pattern").takeIf { it.isNotBlank() } ?: return null
    val homeCurrency = optString("homeCurrency").takeIf { it.isNotBlank() } ?: return null
    val packageNames = optJSONArray("packageNames")?.let { arr ->
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }.toSet()
    }.orEmpty()

    return ParseRule(
        id = "",
        label = optString("label", ""),
        packageNames = packageNames,
        kind = kind,
        sourceAccountName = optString("sourceAccountName", ""),
        destinationAccountName = optString("destinationAccountName", ""),
        sourceAccountId = optString("sourceAccountId").takeIf { it.isNotBlank() },
        destinationAccountId = optString("destinationAccountId").takeIf { it.isNotBlank() },
        homeCurrency = homeCurrency,
        pattern = pattern,
        descriptionTemplate = optString("descriptionTemplate", ""),
        notesTemplate = optString("notesTemplate", ""),
        categoryTemplate = optString("categoryTemplate", ""),
        tagsTemplate = optString("tagsTemplate", ""),
        enabled = optBoolean("enabled", true),
        dateFormat = optString("dateFormat", ""),
    )
}
