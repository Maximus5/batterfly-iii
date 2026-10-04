package io.github.maximus5.ffnotifyimporter.rules

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * User-maintained code<->symbol table - plain SQLite, no ORM. Seeded with EUR/USD/GBP on first
 * creation since those cover the common case; the user edits it from here on (Settings ->
 * Currencies). [symbolToCodeMap] is what [RuleEngine] uses via [normalizeCurrency].
 */
class CurrenciesDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_CODE TEXT PRIMARY KEY,
                $COL_SYMBOL TEXT NOT NULL
            )
            """.trimIndent(),
        )
        DEFAULTS.forEach { entry -> db.insert(TABLE, null, entry.toContentValues()) }
    }

    // DB_VERSION has never actually bumped yet, so this has never run - but don't drop+recreate
    // when it does. RulesDb did exactly this on a real schema change and silently wiped the
    // user's actual saved rules, not test data by that point (same risk here: any currency the
    // user added beyond DEFAULTS). Write a real `if (oldVersion < N)` + `ALTER TABLE ... ADD
    // COLUMN` migration instead when this table's schema actually changes.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun loadAll(): List<CurrencyEntry> {
        val entries = mutableListOf<CurrencyEntry>()
        readableDatabase.query(TABLE, null, null, null, null, null, "$COL_CODE ASC").use { cursor ->
            val codeIdx = cursor.getColumnIndexOrThrow(COL_CODE)
            val symbolIdx = cursor.getColumnIndexOrThrow(COL_SYMBOL)
            while (cursor.moveToNext()) {
                entries.add(CurrencyEntry(cursor.getString(codeIdx), cursor.getString(symbolIdx)))
            }
        }
        return entries
    }

    /** Inserts or replaces by code - editing an existing code's symbol is just add() again. */
    fun add(entry: CurrencyEntry) {
        writableDatabase.insertWithOnConflict(TABLE, null, entry.toContentValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun delete(code: String) {
        writableDatabase.delete(TABLE, "$COL_CODE = ?", arrayOf(code))
    }

    /** symbol -> code, for [normalizeCurrency]. Blank symbols are skipped (nothing to key on). */
    fun symbolToCodeMap(): Map<String, String> =
        loadAll().filter { it.symbol.isNotBlank() }.associate { it.symbol to it.code }

    private fun CurrencyEntry.toContentValues() = ContentValues().apply {
        put(COL_CODE, code)
        put(COL_SYMBOL, symbol)
    }

    companion object {
        private const val DB_NAME = "currencies.db"
        private const val DB_VERSION = 1
        private const val TABLE = "currencies"
        private const val COL_CODE = "code"
        private const val COL_SYMBOL = "symbol"

        private val DEFAULTS = listOf(
            CurrencyEntry("EUR", "€"),
            CurrencyEntry("USD", "$"),
            CurrencyEntry("GBP", "£"),
        )

        @Volatile
        private var instance: CurrenciesDb? = null

        fun getInstance(context: Context): CurrenciesDb =
            instance ?: synchronized(this) {
                instance ?: CurrenciesDb(context.applicationContext).also { instance = it }
            }
    }
}
