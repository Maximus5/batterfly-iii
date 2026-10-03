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
                $COL_TIMESTAMP INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_${TABLE}_timestamp ON $TABLE ($COL_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun insert(message: CapturedMessage) {
        val values = ContentValues().apply {
            put(COL_PACKAGE_NAME, message.packageName)
            put(COL_APP_LABEL, message.appLabel)
            put(COL_TITLE, message.title)
            put(COL_TEXT, message.text)
            put(COL_TIMESTAMP, message.timestampMillis)
        }
        writableDatabase.insert(TABLE, null, values)
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
            while (cursor.moveToNext()) {
                messages.add(
                    CapturedMessage(
                        packageName = cursor.getString(packageNameIdx),
                        appLabel = cursor.getString(appLabelIdx),
                        title = cursor.getString(titleIdx),
                        text = cursor.getString(textIdx),
                        timestampMillis = cursor.getLong(timestampIdx),
                    ),
                )
            }
        }
        return messages
    }

    fun deleteOlderThan(cutoffMillis: Long) {
        writableDatabase.delete(TABLE, "$COL_TIMESTAMP < ?", arrayOf(cutoffMillis.toString()))
    }

    /** Wipes all stored history - used when the user turns retention back off. */
    fun clearAll() {
        writableDatabase.delete(TABLE, null, null)
    }

    companion object {
        private const val DB_NAME = "message_history.db"
        private const val DB_VERSION = 1
        private const val TABLE = "messages"
        private const val COL_PACKAGE_NAME = "package_name"
        private const val COL_APP_LABEL = "app_label"
        private const val COL_TITLE = "title"
        private const val COL_TEXT = "text"
        private const val COL_TIMESTAMP = "timestamp_millis"

        @Volatile
        private var instance: MessageHistoryDb? = null

        /** One shared instance for the whole process, so writes don't pile up unclosed helpers. */
        fun getInstance(context: Context): MessageHistoryDb =
            instance ?: synchronized(this) {
                instance ?: MessageHistoryDb(context.applicationContext).also { instance = it }
            }
    }
}
