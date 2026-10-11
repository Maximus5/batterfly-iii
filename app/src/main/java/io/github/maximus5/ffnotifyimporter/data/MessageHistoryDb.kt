package io.github.maximus5.ffnotifyimporter.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * On-disk mirror of captured messages, only ever written to while the user has opted into a
 * retention window (see `prefs.RetentionPrefs`). Plain SQLite, no ORM - one table, three
 * operations - doesn't warrant more than that.
 */
class MessageHistoryDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_PACKAGE_NAME TEXT NOT NULL,
                $COL_APP_LABEL TEXT NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_FIREFLY_TRANSACTION_ID TEXT,
                $COL_APPLIED_RULE_LABEL TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_timestamp ON $TABLE ($COL_TIMESTAMP)")
    }

    // v1 -> v2: added COL_FIREFLY_TRANSACTION_ID. This table's schema had never actually changed
    // before, so this is the first real test of the promise this comment used to make - additive
    // only, same as RulesDb since the data-loss incident documented in ARCHITECTURE.md's Known
    // bugs/gotchas. Never go back to DROP TABLE + recreate here.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_FIREFLY_TRANSACTION_ID TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN $COL_APPLIED_RULE_LABEL TEXT")
        }
    }

    fun insert(message: CapturedMessage) {
        val values = ContentValues().apply {
            put(COL_PACKAGE_NAME, message.packageName)
            put(COL_APP_LABEL, message.appLabel)
            put(COL_TITLE, message.title)
            put(COL_TEXT, message.text)
            put(COL_TIMESTAMP, message.timestampMillis)
            put(COL_FIREFLY_TRANSACTION_ID, message.fireflyTransactionId)
            put(COL_APPLIED_RULE_LABEL, message.appliedRuleLabel)
        }
        writableDatabase.insert(TABLE, null, values)
    }

    /** Updates the row matching [message] on every column *except* [COL_FIREFLY_TRANSACTION_ID]
     * (same "match on the stable identity, not the field being changed" reasoning as
     * `MessageStore.setTransactionId`, which calls this via `RetentionController`'s
     * `onMessageUpdated` hook) to carry [message]'s current `fireflyTransactionId`. */
    fun updateTransactionId(message: CapturedMessage) {
        val values = ContentValues().apply {
            put(COL_FIREFLY_TRANSACTION_ID, message.fireflyTransactionId)
            put(COL_APPLIED_RULE_LABEL, message.appliedRuleLabel)
        }
        writableDatabase.update(
            TABLE,
            values,
            "$COL_PACKAGE_NAME = ? AND $COL_TITLE = ? AND $COL_TEXT = ? AND $COL_TIMESTAMP = ?",
            arrayOf(message.packageName, message.title, message.text, message.timestampMillis.toString()),
        )
    }

    /** Oldest first, so callers can feed them into `MessageStore.restore` in the right order. */
    fun loadNewerThan(cutoffMillis: Long): List<CapturedMessage> {
        val messages = mutableListOf<CapturedMessage>()
        readableDatabase.query(
            TABLE,
            null,
            "$COL_TIMESTAMP >= ?",
            arrayOf(cutoffMillis.toString()),
            null,
            null,
            "$COL_TIMESTAMP ASC",
        ).use { cursor ->
            val packageNameIdx = cursor.getColumnIndexOrThrow(COL_PACKAGE_NAME)
            val appLabelIdx = cursor.getColumnIndexOrThrow(COL_APP_LABEL)
            val titleIdx = cursor.getColumnIndexOrThrow(COL_TITLE)
            val textIdx = cursor.getColumnIndexOrThrow(COL_TEXT)
            val timestampIdx = cursor.getColumnIndexOrThrow(COL_TIMESTAMP)
            val transactionIdIdx = cursor.getColumnIndexOrThrow(COL_FIREFLY_TRANSACTION_ID)
            val ruleLabelIdx = cursor.getColumnIndexOrThrow(COL_APPLIED_RULE_LABEL)
            while (cursor.moveToNext()) {
                messages.add(
                    CapturedMessage(
                        packageName = cursor.getString(packageNameIdx),
                        appLabel = cursor.getString(appLabelIdx),
                        title = cursor.getString(titleIdx),
                        text = cursor.getString(textIdx),
                        timestampMillis = cursor.getLong(timestampIdx),
                        fireflyTransactionId = if (cursor.isNull(transactionIdIdx)) null else cursor.getString(transactionIdIdx),
                        appliedRuleLabel = if (cursor.isNull(ruleLabelIdx)) null else cursor.getString(ruleLabelIdx),
                    ),
                )
            }
        }
        return messages
    }

    fun deleteOlderThan(cutoffMillis: Long) {
        writableDatabase.delete(TABLE, "$COL_TIMESTAMP < ?", arrayOf(cutoffMillis.toString()))
    }

    /** Deletes the row matching [message] exactly (its full column set is unique enough here). */
    fun delete(message: CapturedMessage) {
        writableDatabase.delete(
            TABLE,
            "$COL_PACKAGE_NAME = ? AND $COL_TITLE = ? AND $COL_TEXT = ? AND $COL_TIMESTAMP = ?",
            arrayOf(message.packageName, message.title, message.text, message.timestampMillis.toString()),
        )
    }

    /** Wipes all stored history - used when the user turns retention back off. */
    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val DB_NAME = "message_history.db"
        private const val DB_VERSION = 3
        private const val TABLE = "messages"
        private const val COL_PACKAGE_NAME = "package_name"
        private const val COL_APP_LABEL = "app_label"
        private const val COL_TITLE = "title"
        private const val COL_TEXT = "text"
        private const val COL_TIMESTAMP = "timestamp_millis"
        private const val COL_FIREFLY_TRANSACTION_ID = "firefly_transaction_id"
        private const val COL_APPLIED_RULE_LABEL = "applied_rule_label"

        @Volatile
        private var instance: MessageHistoryDb? = null

        /** One shared instance for the whole process, so writes don't pile up unclosed helpers. */
        fun getInstance(context: Context): MessageHistoryDb =
            instance ?: synchronized(this) {
                instance ?: MessageHistoryDb(context.applicationContext).also { instance = it }
            }
    }
}
