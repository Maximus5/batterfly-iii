package io.github.maximus5.ffnotifyimporter.rules

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local log of transactions this app has actually posted to Firefly - exists purely for dedup
 * (`existsWithinWindow`, see TODO.md's dedup history) and so a future "already created" indicator
 * has something to check against. One row per successful `FireflyApiClient.createTransaction()`
 * call. Plain SQLite, same reasoning as `MessageHistoryDb`/`RulesDb` - no ORM for one table.
 */
class CreatedTransactionsDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_HASH TEXT NOT NULL,
                $COL_DATE_MILLIS INTEGER NOT NULL,
                $COL_FIREFLY_TRANSACTION_ID TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_hash ON $TABLE ($COL_HASH)")
    }

    // Never seen a real schema change yet - if/when one happens, an additive `ALTER TABLE ... ADD
    // COLUMN` migration, never drop+recreate. See RulesDb's KDoc for why this matters: a
    // drop+recreate here would silently wipe the dedup history - not catastrophic like RulesDb's
    // incident (this table is only ever a derived log, rebuildable by re-reading Firefly), but
    // still real, unrecoverable-without-a-rebuild data.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun recordCreated(hash: String, transactionDateMillis: Long, fireflyTransactionId: String) {
        val values = ContentValues().apply {
            put(COL_HASH, hash)
            put(COL_DATE_MILLIS, transactionDateMillis)
            put(COL_FIREFLY_TRANSACTION_ID, fireflyTransactionId)
        }
        writableDatabase.insert(TABLE, null, values)
    }

    /** The Firefly transaction id of the most recent match for [hash] recorded within
     * [windowMinutes] of [transactionDateMillis] - null if there isn't one. The window is
     * centered on the new transaction's own date (not "now"), so two apps notifying about the
     * same payment seconds apart both hash-match regardless of which one this app happens to
     * process first. The id lets the caller offer "this is already created" as a real answer -
     * link the found transaction to the message instead of just refusing to post - not only
     * "yes/no, a duplicate exists". */
    fun findWithinWindow(hash: String, transactionDateMillis: Long, windowMinutes: Int): String? {
        val windowMillis = windowMinutes * 60_000L
        readableDatabase.query(
            TABLE,
            arrayOf(COL_FIREFLY_TRANSACTION_ID),
            "$COL_HASH = ? AND $COL_DATE_MILLIS BETWEEN ? AND ?",
            arrayOf(hash, (transactionDateMillis - windowMillis).toString(), (transactionDateMillis + windowMillis).toString()),
            null,
            null,
            "$COL_DATE_MILLIS DESC",
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(COL_FIREFLY_TRANSACTION_ID)) else null
        }
    }

    companion object {
        private const val DB_NAME = "created_transactions.db"
        private const val DB_VERSION = 1
        private const val TABLE = "created_transactions"
        private const val COL_HASH = "hash"
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
