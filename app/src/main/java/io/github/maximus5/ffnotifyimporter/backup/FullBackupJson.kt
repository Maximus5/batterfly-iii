package io.github.maximus5.ffnotifyimporter.backup

import io.github.maximus5.ffnotifyimporter.data.CapturedMessage
import io.github.maximus5.ffnotifyimporter.rules.CreatedTransactionRecord
import io.github.maximus5.ffnotifyimporter.rules.CurrencyEntry
import io.github.maximus5.ffnotifyimporter.rules.ParseRule
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
 * [messages]' `fireflyTransactionId` *is* included (unlike `RulesJson`'s deliberate exclusion of
 * `ParseRule.sourceAccountId`/`destinationAccountId`) - a real bug, not a parallel design choice:
 * a created transaction's id is historical record-keeping tied to this message, not a live-picker
 * reference that could go stale, so dropping it on restore was pure data loss (a restored message
 * silently lost its "#1234" link even though the transaction genuinely still exists in Firefly).
 * [createdTransactions] mirrors `CreatedTransactionsDb` for the same reason - without it, the
 * dedup log would come back empty after a restore onto a fresh install, silently disabling
 * duplicate detection for everything created before the backup.
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
    // Reuses rulesToJson's own per-rule shape, pulling just the array back out - one format for
    // "a rule" everywhere, not a second slightly-different one here.
    root.put("rules", JSONObject(rulesToJson(data.rules)).getJSONArray("rules"))
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

    val rules = root.optJSONArray("rules")
        ?.let { arr -> rulesFromJson(JSONObject().put("rules", arr).toString()) }
        .orEmpty()

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
