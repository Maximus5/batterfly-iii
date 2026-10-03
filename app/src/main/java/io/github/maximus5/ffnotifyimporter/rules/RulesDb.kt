package io.github.maximus5.ffnotifyimporter.rules

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

/**
 * Rule storage - plain SQLite, no ORM, one row per [ParseRule]. [COL_SORT_ORDER] is the only
 * priority mechanism `RuleEngine` uses (see ARCHITECTURE.md): [loadAllOrdered] returns rows in
 * that order, and [moveUp]/[moveDown] are how the rules list screen lets the user change it.
 */
class RulesDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID TEXT PRIMARY KEY,
                $COL_LABEL TEXT NOT NULL,
                $COL_PACKAGE_NAMES TEXT NOT NULL,
                $COL_KIND TEXT NOT NULL,
                $COL_ACCOUNT_NAME TEXT NOT NULL,
                $COL_HOME_CURRENCY TEXT NOT NULL,
                $COL_COUNTERPARTY_ACCOUNT_NAME TEXT,
                $COL_PATTERN TEXT NOT NULL,
                $COL_DECIMAL_SEPARATOR TEXT NOT NULL,
                $COL_DESCRIPTION_TEMPLATE TEXT NOT NULL,
                $COL_NOTES_TEMPLATE TEXT NOT NULL,
                $COL_ENABLED INTEGER NOT NULL,
                $COL_SORT_ORDER INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun loadAllOrdered(): List<ParseRule> {
        val rules = mutableListOf<ParseRule>()
        readableDatabase.query(TABLE, null, null, null, null, null, "$COL_SORT_ORDER ASC").use { cursor ->
            while (cursor.moveToNext()) rules.add(cursor.toRule())
        }
        return rules
    }

    /** Appends a copy of [rule] at the end of the list, ignoring whatever id it already has. */
    fun add(rule: ParseRule): ParseRule {
        val nextOrder = (maxSortOrder() ?: -1) + 1
        val withId = rule.copy(id = UUID.randomUUID().toString())
        writableDatabase.insert(TABLE, null, withId.toContentValues(nextOrder))
        return withId
    }

    /** Updates every field of the row matching [rule]'s id. Leaves its sort order untouched. */
    fun update(rule: ParseRule) {
        val order = sortOrderOf(rule.id) ?: return
        writableDatabase.update(TABLE, rule.toContentValues(order), "$COL_ID = ?", arrayOf(rule.id))
    }

    fun delete(id: String) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id))
    }

    /** Clones the rule at [id] with a new id and " (copy)" label, inserted right after it. */
    fun duplicate(id: String): ParseRule? {
        val original = loadAllOrdered().firstOrNull { it.id == id } ?: return null
        val originalOrder = sortOrderOf(id) ?: return null
        val copy = original.copy(id = UUID.randomUUID().toString(), label = "${original.label} (copy)")

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE $TABLE SET $COL_SORT_ORDER = $COL_SORT_ORDER + 1 WHERE $COL_SORT_ORDER > ?",
                arrayOf(originalOrder),
            )
            db.insert(TABLE, null, copy.toContentValues(originalOrder + 1))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return copy
    }

    fun moveUp(id: String) = swapWithNeighbor(id, direction = -1)

    fun moveDown(id: String) = swapWithNeighbor(id, direction = 1)

    private fun swapWithNeighbor(id: String, direction: Int) {
        val ordered = loadAllOrdered()
        val index = ordered.indexOfFirst { it.id == id }
        val neighborIndex = index + direction
        if (index < 0 || neighborIndex < 0 || neighborIndex >= ordered.size) return

        val orderA = sortOrderOf(ordered[index].id) ?: return
        val orderB = sortOrderOf(ordered[neighborIndex].id) ?: return

        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE $TABLE SET $COL_SORT_ORDER = ? WHERE $COL_ID = ?",
                arrayOf<Any>(orderB, ordered[index].id),
            )
            db.execSQL(
                "UPDATE $TABLE SET $COL_SORT_ORDER = ? WHERE $COL_ID = ?",
                arrayOf<Any>(orderA, ordered[neighborIndex].id),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Patterns already used by saved rules, newest-first, one entry per distinct pattern - the
     * "pick an existing pattern" source for the rule editor. No separate library table (see
     * TODO.md/ARCHITECTURE.md): it's just a derived view over this one.
     */
    fun distinctPatterns(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        readableDatabase.query(
            TABLE, arrayOf(COL_PATTERN, COL_LABEL), null, null, null, null, "$COL_SORT_ORDER DESC",
        ).use { cursor ->
            val patternIdx = cursor.getColumnIndexOrThrow(COL_PATTERN)
            val labelIdx = cursor.getColumnIndexOrThrow(COL_LABEL)
            while (cursor.moveToNext()) results.add(cursor.getString(patternIdx) to cursor.getString(labelIdx))
        }
        return results.distinctBy { it.first }
    }

    private fun maxSortOrder(): Int? {
        readableDatabase.rawQuery("SELECT MAX($COL_SORT_ORDER) FROM $TABLE", null).use { cursor ->
            return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getInt(0) else null
        }
    }

    private fun sortOrderOf(id: String): Int? {
        readableDatabase.query(
            TABLE, arrayOf(COL_SORT_ORDER), "$COL_ID = ?", arrayOf(id), null, null, null,
        ).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else null
        }
    }

    private fun ParseRule.toContentValues(sortOrder: Int) = ContentValues().apply {
        put(COL_ID, id)
        put(COL_LABEL, label)
        put(COL_PACKAGE_NAMES, packageNames.joinToString(","))
        put(COL_KIND, kind.name)
        put(COL_ACCOUNT_NAME, accountName)
        put(COL_HOME_CURRENCY, homeCurrency)
        put(COL_COUNTERPARTY_ACCOUNT_NAME, counterpartyAccountName)
        put(COL_PATTERN, pattern)
        put(COL_DECIMAL_SEPARATOR, decimalSeparator.toString())
        put(COL_DESCRIPTION_TEMPLATE, descriptionTemplate)
        put(COL_NOTES_TEMPLATE, notesTemplate)
        put(COL_ENABLED, if (enabled) 1 else 0)
        put(COL_SORT_ORDER, sortOrder)
    }

    private fun Cursor.toRule(): ParseRule {
        fun str(col: String) = getString(getColumnIndexOrThrow(col))
        fun strOrNull(col: String): String? {
            val idx = getColumnIndexOrThrow(col)
            return if (isNull(idx)) null else getString(idx)
        }
        return ParseRule(
            id = str(COL_ID),
            label = str(COL_LABEL),
            packageNames = str(COL_PACKAGE_NAMES).split(",").filter { it.isNotEmpty() }.toSet(),
            kind = RuleKind.valueOf(str(COL_KIND)),
            accountName = str(COL_ACCOUNT_NAME),
            homeCurrency = str(COL_HOME_CURRENCY),
            counterpartyAccountName = strOrNull(COL_COUNTERPARTY_ACCOUNT_NAME),
            pattern = str(COL_PATTERN),
            decimalSeparator = str(COL_DECIMAL_SEPARATOR).first(),
            descriptionTemplate = str(COL_DESCRIPTION_TEMPLATE),
            notesTemplate = str(COL_NOTES_TEMPLATE),
            enabled = getInt(getColumnIndexOrThrow(COL_ENABLED)) != 0,
        )
    }

    companion object {
        private const val DB_NAME = "rules.db"
        private const val DB_VERSION = 1
        private const val TABLE = "rules"
        private const val COL_ID = "id"
        private const val COL_LABEL = "label"
        private const val COL_PACKAGE_NAMES = "package_names"
        private const val COL_KIND = "kind"
        private const val COL_ACCOUNT_NAME = "account_name"
        private const val COL_HOME_CURRENCY = "home_currency"
        private const val COL_COUNTERPARTY_ACCOUNT_NAME = "counterparty_account_name"
        private const val COL_PATTERN = "pattern"
        private const val COL_DECIMAL_SEPARATOR = "decimal_separator"
        private const val COL_DESCRIPTION_TEMPLATE = "description_template"
        private const val COL_NOTES_TEMPLATE = "notes_template"
        private const val COL_ENABLED = "enabled"
        private const val COL_SORT_ORDER = "sort_order"

        @Volatile
        private var instance: RulesDb? = null

        fun getInstance(context: Context): RulesDb =
            instance ?: synchronized(this) {
                instance ?: RulesDb(context.applicationContext).also { instance = it }
            }
    }
}
