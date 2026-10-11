package io.github.maximus5.ffnotifyimporter.rules

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One row of [CreatedTransactionsDb], for [CreatedTransactionsDb.loadAll]/full-backup round
 * tripping (see `backup.FullBackupJson`). [hashWithoutDescription] is null for a row written
 * before the two-hash change below existed - never backfilled, there's no way to recover the
 * original description text from a one-way hash to recompute it. */
data class CreatedTransactionRecord(
    val hashWithDescription: String,
    val hashWithoutDescription: String?,
    val dateMillis: Long,
    val fireflyTransactionId: String,
    /** [transactionDedupHashIgnoringAccounts]; null for a row written before it existed. */
    val hashWithoutAccounts: String? = null,
)

/**
 * Local log of transactions this app has actually posted to Firefly - exists purely for dedup
 * (`findWithinWindow`, see TODO.md's dedup history) and so a future "already created" indicator
 * has something to check against. One row per successful `FireflyApiClient.createTransaction()`
 * call. Plain SQLite, same reasoning as `MessageHistoryDb`/`RulesDb` - no ORM for one table.
 *
 * Both hash variants ([COL_HASH_WITH_DESCRIPTION]/[COL_HASH_WITHOUT_DESCRIPTION]) are always
 * computed and stored on every write, regardless of `FireflyPrefs.isDedupIncludeDescription()` at
 * the time - a real user-reported bug: with only one hash column, flipping that setting changed
 * what every *existing* row's hash means, so every row already written instantly stopped matching
 * anything (a SHA-256 digest has no partial/fuzzy comparison - any changed input changes the whole
 * digest). Writing both up front means a lookup under either setting can still find a match
 * against history written under the other.
 */
class CreatedTransactionsDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_HASH_WITH_DESCRIPTION TEXT NOT NULL,
                $COL_HASH_WITHOUT_DESCRIPTION TEXT,
                $COL_HASH_WITHOUT_ACCOUNTS TEXT,
                $COL_DATE_MILLIS INTEGER NOT NULL,
                $COL_FIREFLY_TRANSACTION_ID TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_hash_with_description ON $TABLE ($COL_HASH_WITH_DESCRIPTION)")
        db.execSQL("CREATE INDEX idx_${TABLE}_hash_without_description ON $TABLE ($COL_HASH_WITHOUT_DESCRIPTION)")
        db.execSQL("CREATE INDEX idx_${TABLE}_hash_without_accounts ON $TABLE ($COL_HASH_WITHOUT_ACCOUNTS)")
    }

    // Additive only, per RulesDb's hard-learned rule (see ARCHITECTURE.md's Known bugs/gotchas) -
    // this table's own onUpgrade used to just drop+recreate, never actually exercised since
    // DB_VERSION never moved before now. v1->v2 adds hash_without_description via plain ADD
    // COLUMN - deliberately *not* RENAME COLUMN for the old `hash` column (SQLite only gained that
    // in 3.25, newer than what ships on this app's minSdk 26 devices); COL_HASH_WITH_DESCRIPTION
    // stays mapped to the original physical column name below instead. Existing rows keep
    // whatever their `hash` value already was (the default setting has always been "include
    // description", so that's the closest-to-correct label for old data) and get
    // hash_without_description = NULL, meaning "unknown" rather than a wrong guess - there's no
    // original description text left to recompute it from.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_HASH_WITHOUT_DESCRIPTION TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_hash_with_description ON $TABLE ($COL_HASH_WITH_DESCRIPTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_hash_without_description ON $TABLE ($COL_HASH_WITHOUT_DESCRIPTION)")
        }
        // v2->v3: account-agnostic hash (see transactionDedupHashIgnoringAccounts). Old rows keep NULL
        // = "unknown" - their account/amount inputs are gone, so it cannot be recomputed.
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_HASH_WITHOUT_ACCOUNTS TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_${TABLE}_hash_without_accounts ON $TABLE ($COL_HASH_WITHOUT_ACCOUNTS)")
        }
    }

    fun recordCreated(
        hashWithDescription: String,
        hashWithoutDescription: String?,
        transactionDateMillis: Long,
        fireflyTransactionId: String,
        hashWithoutAccounts: String? = null,
    ) {
        val values = ContentValues().apply {
            put(COL_HASH_WITH_DESCRIPTION, hashWithDescription)
            put(COL_HASH_WITHOUT_DESCRIPTION, hashWithoutDescription)
            put(COL_HASH_WITHOUT_ACCOUNTS, hashWithoutAccounts)
            put(COL_DATE_MILLIS, transactionDateMillis)
            put(COL_FIREFLY_TRANSACTION_ID, fireflyTransactionId)
        }
        writableDatabase.insert(TABLE, null, values)
    }

    /** The Firefly transaction id of the most recent match for [hash] recorded within
     * [windowMinutes] of [transactionDateMillis] - null if there isn't one. [includeDescription]
     * picks which of the two stored hash variants to compare against (the caller computed [hash]
     * with the same flag, via `transactionDedupHash`) - a row written while the setting had the
     * other value still matches here, since both variants are always stored (see the class KDoc).
     * The window is centered on the new transaction's own date (not "now"), so two apps notifying
     * about the same payment seconds apart both hash-match regardless of which one this app
     * happens to process first. The id lets the caller offer "this is already created" as a real
     * answer - link the found transaction to the message instead of just refusing to post - not
     * only "yes/no, a duplicate exists". */
    fun findWithinWindow(hash: String, includeDescription: Boolean, transactionDateMillis: Long, windowMinutes: Int): String? {
        val column = if (includeDescription) COL_HASH_WITH_DESCRIPTION else COL_HASH_WITHOUT_DESCRIPTION
        val windowMillis = windowMinutes * 60_000L
        readableDatabase.query(
            TABLE,
            arrayOf(COL_FIREFLY_TRANSACTION_ID),
            "$column = ? AND $COL_DATE_MILLIS BETWEEN ? AND ?",
            arrayOf(hash, (transactionDateMillis - windowMillis).toString(), (transactionDateMillis + windowMillis).toString()),
            null,
            null,
            "$COL_DATE_MILLIS DESC",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(COL_FIREFLY_TRANSACTION_ID)) else null
        }
    }

    /** Same lookup as [findWithinWindow], against the account-agnostic hash
     * ([transactionDedupHashIgnoringAccounts]) - for a draft from an `accountApproximate` rule. Rows
     * written before that column existed have NULL there and never match. */
    fun findWithoutAccountsWithinWindow(hash: String, transactionDateMillis: Long, windowMinutes: Int): String? {
        val windowMillis = windowMinutes * 60_000L
        readableDatabase.query(
            TABLE,
            arrayOf(COL_FIREFLY_TRANSACTION_ID),
            "$COL_HASH_WITHOUT_ACCOUNTS = ? AND $COL_DATE_MILLIS BETWEEN ? AND ?",
            arrayOf(hash, (transactionDateMillis - windowMillis).toString(), (transactionDateMillis + windowMillis).toString()),
            null,
            null,
            "$COL_DATE_MILLIS DESC",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(COL_FIREFLY_TRANSACTION_ID)) else null
        }
    }

    /** Every row, for full-backup export (`backup.BackupManager.collect`) - no real need for
     * ordering here, the table is just a flat log, not something displayed in sequence. */
    fun loadAll(): List<CreatedTransactionRecord> {
        val records = mutableListOf<CreatedTransactionRecord>()
        readableDatabase.query(TABLE, null, null, null, null, null, null).use { cursor ->
            val hashWithIdx = cursor.getColumnIndexOrThrow(COL_HASH_WITH_DESCRIPTION)
            val hashWithoutIdx = cursor.getColumnIndexOrThrow(COL_HASH_WITHOUT_DESCRIPTION)
            val hashNoAccountsIdx = cursor.getColumnIndexOrThrow(COL_HASH_WITHOUT_ACCOUNTS)
            val dateIdx = cursor.getColumnIndexOrThrow(COL_DATE_MILLIS)
            val idIdx = cursor.getColumnIndexOrThrow(COL_FIREFLY_TRANSACTION_ID)
            while (cursor.moveToNext()) {
                records.add(
                    CreatedTransactionRecord(
                        hashWithDescription = cursor.getString(hashWithIdx),
                        hashWithoutDescription = if (cursor.isNull(hashWithoutIdx)) null else cursor.getString(hashWithoutIdx),
                        dateMillis = cursor.getLong(dateIdx),
                        fireflyTransactionId = cursor.getString(idIdx),
                        hashWithoutAccounts = if (cursor.isNull(hashNoAccountsIdx)) null else cursor.getString(hashNoAccountsIdx),
                    ),
                )
            }
        }
        return records
    }

    /** Retention: drops rows whose transaction date is before [cutoffMillis] (see
     * `data.RetentionController`). The dedup window is minutes, so any retention setting is far
     * longer than a lookup can ever reach back. */
    fun deleteOlderThan(cutoffMillis: Long) {
        writableDatabase.delete(TABLE, "$COL_DATE_MILLIS < ?", arrayOf(cutoffMillis.toString()))
    }

    /** Wipes the whole log - used by `backup.BackupManager.restore` before re-adding the backed-up
     * rows, same replace-not-merge semantics every other store restore uses. */
    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val DB_NAME = "created_transactions.db"
        private const val DB_VERSION = 3
        private const val TABLE = "created_transactions"
        // Physical name stays "hash", not "hash_with_description" - this is the pre-existing
        // column from DB_VERSION 1, and renaming it would need SQLite's RENAME COLUMN (3.25+,
        // newer than what ships on this app's minSdk 26 devices). The Kotlin-side name is the
        // accurate one; see onUpgrade's KDoc.
        private const val COL_HASH_WITH_DESCRIPTION = "hash"
        private const val COL_HASH_WITHOUT_DESCRIPTION = "hash_without_description"
        private const val COL_HASH_WITHOUT_ACCOUNTS = "hash_without_accounts"
        private const val COL_DATE_MILLIS = "date_millis"
        private const val COL_FIREFLY_TRANSACTION_ID = "firefly_transaction_id"

        @Volatile
        private var instance: CreatedTransactionsDb? = null

        fun getInstance(context: Context): CreatedTransactionsDb =
            instance ?: synchronized(this) {
                instance ?: CreatedTransactionsDb(context.applicationContext).also { instance = it }
            }
    }
}
