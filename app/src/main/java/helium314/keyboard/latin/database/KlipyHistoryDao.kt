// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase

class KlipyHistoryDao private constructor(context: Context) {
    private val dbHelper = Database.getInstance(context)

    fun addHistory(id: String, url: String, type: String, width: Int = 0, height: Int = 0, previewUrl: String? = null) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            // Remove existing entry for the same ID and type to move it to the top
            val existingPin = getPinState(db, id, type)
            db.delete(TABLE_NAME, "$COLUMN_ID = ? AND $COLUMN_TYPE = ?", arrayOf(id, type))

            val values = ContentValues().apply {
                put(COLUMN_ID, id)
                put(COLUMN_URL, url)
                put(COLUMN_TYPE, type)
                put(COLUMN_TIMESTAMP, System.currentTimeMillis())
                put(COLUMN_WIDTH, width)
                put(COLUMN_HEIGHT, height)
                put(COLUMN_PREVIEW_URL, previewUrl)
                put(COLUMN_PINNED, if (existingPin.isPinned) 1 else 0)
                put(COLUMN_PINNED_TIMESTAMP, existingPin.pinnedTimestamp)
            }
            db.insert(TABLE_NAME, null, values)

            pruneHistory(db, type)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getHistory(type: String): List<KlipyItem> {
        val db = dbHelper.readableDatabase
        return getPinnedHistory(db, type) + getRecentHistory(db, type)
    }

    fun getPinnedHistory(type: String): List<KlipyItem> {
        val db = dbHelper.readableDatabase
        return getPinnedHistory(db, type)
    }

    fun getRecentHistory(type: String): List<KlipyItem> {
        val db = dbHelper.readableDatabase
        return getRecentHistory(db, type)
    }

    fun pinHistory(id: String, type: String) {
        val db = dbHelper.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(COLUMN_PINNED, 1)
                put(COLUMN_PINNED_TIMESTAMP, System.currentTimeMillis())
            }
            db.update(TABLE_NAME, values, "$COLUMN_ID = ? AND $COLUMN_TYPE = ?", arrayOf(id, type))
            demoteOverflowPins(db, type)
            pruneHistory(db, type)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun unpinHistory(id: String, type: String) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_PINNED, 0)
            put(COLUMN_PINNED_TIMESTAMP, 0L)
            put(COLUMN_TIMESTAMP, System.currentTimeMillis())
        }
        db.update(TABLE_NAME, values, "$COLUMN_ID = ? AND $COLUMN_TYPE = ?", arrayOf(id, type))
        pruneHistory(db, type)
    }

    fun deleteHistory(id: String, type: String) {
        val db = dbHelper.writableDatabase
        db.delete(TABLE_NAME, "$COLUMN_ID = ? AND $COLUMN_TYPE = ?", arrayOf(id, type))
    }

    fun clearHistory(type: String) {
        val db = dbHelper.writableDatabase
        db.delete(TABLE_NAME, "$COLUMN_TYPE = ?", arrayOf(type))
    }

    private fun pruneHistory(db: SQLiteDatabase, type: String) {
        demoteOverflowPins(db, type)
        db.execSQL(
            """
                DELETE FROM $TABLE_NAME
                WHERE $COLUMN_TYPE = ?
                    AND $COLUMN_PINNED = 0
                    AND rowid NOT IN (
                        SELECT rowid
                        FROM $TABLE_NAME
                        WHERE $COLUMN_TYPE = ?
                            AND $COLUMN_PINNED = 0
                        ORDER BY $COLUMN_TIMESTAMP DESC, rowid DESC
                        LIMIT ?
                    )
            """.trimIndent(),
            arrayOf<Any>(type, type, maxRecentHistoryForType(type))
        )
    }

    private fun demoteOverflowPins(db: SQLiteDatabase, type: String) {
        val overflowRowIds = mutableListOf<Long>()
        db.rawQuery(
            """
                SELECT rowid
                FROM $TABLE_NAME
                WHERE $COLUMN_TYPE = ?
                    AND $COLUMN_PINNED = 1
                ORDER BY $COLUMN_PINNED_TIMESTAMP DESC, rowid DESC
                LIMIT -1 OFFSET ?
            """.trimIndent(),
            arrayOf(type, MAX_PINNED_HISTORY.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                overflowRowIds.add(cursor.getLong(0))
            }
        }
        if (overflowRowIds.isEmpty()) return

        val now = System.currentTimeMillis()
        overflowRowIds.forEach { rowId ->
            val values = ContentValues().apply {
                put(COLUMN_PINNED, 0)
                put(COLUMN_PINNED_TIMESTAMP, 0L)
                put(COLUMN_TIMESTAMP, now)
            }
            db.update(TABLE_NAME, values, "rowid = ?", arrayOf(rowId.toString()))
        }
    }

    private fun getPinnedHistory(db: SQLiteDatabase, type: String): List<KlipyItem> {
        return queryHistory(
            db = db,
            type = type,
            pinned = true,
            limit = MAX_PINNED_HISTORY,
            orderBy = "$COLUMN_PINNED_TIMESTAMP DESC, rowid DESC"
        )
    }

    private fun getRecentHistory(db: SQLiteDatabase, type: String): List<KlipyItem> {
        return queryHistory(
            db = db,
            type = type,
            pinned = false,
            limit = maxRecentHistoryForType(type),
            orderBy = "$COLUMN_TIMESTAMP DESC, rowid DESC"
        )
    }

    private fun queryHistory(
        db: SQLiteDatabase,
        type: String,
        pinned: Boolean,
        limit: Int,
        orderBy: String
    ): List<KlipyItem> {
        val list = mutableListOf<KlipyItem>()
        db.rawQuery(
            """
                SELECT $COLUMN_ID, $COLUMN_URL, $COLUMN_WIDTH, $COLUMN_HEIGHT, $COLUMN_PREVIEW_URL, $COLUMN_PINNED
                FROM $TABLE_NAME
                WHERE $COLUMN_TYPE = ?
                    AND $COLUMN_PINNED = ?
                ORDER BY $orderBy
                LIMIT ?
            """.trimIndent(),
            arrayOf(type, if (pinned) "1" else "0", limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(KlipyItem(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getInt(2),
                    cursor.getInt(3),
                    cursor.getString(4),
                    cursor.getInt(5) != 0
                ))
            }
        }
        return list
    }

    private fun getPinState(db: SQLiteDatabase, id: String, type: String): PinState {
        db.rawQuery(
            "SELECT $COLUMN_PINNED, $COLUMN_PINNED_TIMESTAMP FROM $TABLE_NAME WHERE $COLUMN_ID = ? AND $COLUMN_TYPE = ?",
            arrayOf(id, type)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return PinState(cursor.getInt(0) != 0, cursor.getLong(1))
            }
        }
        return PinState(isPinned = false, pinnedTimestamp = 0L)
    }

    private data class PinState(val isPinned: Boolean, val pinnedTimestamp: Long)

    companion object {
        const val TABLE_NAME = "KLIPY_HISTORY"
        const val COLUMN_ID = "ID"
        const val COLUMN_URL = "URL"
        const val COLUMN_TYPE = "TYPE" // "GIF" or "STICKER"
        const val COLUMN_TIMESTAMP = "TIMESTAMP"
        const val COLUMN_WIDTH = "WIDTH"
        const val COLUMN_HEIGHT = "HEIGHT"
        const val COLUMN_PREVIEW_URL = "PREVIEW_URL"
        const val COLUMN_PINNED = "PINNED"
        const val COLUMN_PINNED_TIMESTAMP = "PINNED_TIMESTAMP"

        const val TYPE_GIF = "GIF"
        const val TYPE_STICKER = "STICKER"
        const val MAX_HISTORY_ROWS = 5
        const val GIF_HISTORY_COLUMNS = 2
        const val STICKER_HISTORY_COLUMNS = 4
        const val MAX_PINNED_HISTORY = 4
        const val MAX_RECENT_GIF_HISTORY = (MAX_HISTORY_ROWS - 1) * GIF_HISTORY_COLUMNS
        const val MAX_RECENT_STICKER_HISTORY = (MAX_HISTORY_ROWS - 1) * STICKER_HISTORY_COLUMNS
        const val MAX_GIF_HISTORY = MAX_PINNED_HISTORY + MAX_RECENT_GIF_HISTORY
        const val MAX_STICKER_HISTORY = MAX_PINNED_HISTORY + MAX_RECENT_STICKER_HISTORY

        const val CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COLUMN_ID TEXT,
                $COLUMN_URL TEXT,
                $COLUMN_TYPE TEXT,
                $COLUMN_TIMESTAMP INTEGER,
                $COLUMN_WIDTH INTEGER,
                $COLUMN_HEIGHT INTEGER,
                $COLUMN_PREVIEW_URL TEXT,
                $COLUMN_PINNED INTEGER NOT NULL DEFAULT 0,
                $COLUMN_PINNED_TIMESTAMP INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY ($COLUMN_ID, $COLUMN_TYPE)
            )
        """

        private var instance: KlipyHistoryDao? = null
        fun getInstance(context: Context): KlipyHistoryDao {
            if (instance == null) instance = KlipyHistoryDao(context)
            return instance!!
        }

        fun maxHistoryForType(type: String): Int {
            return MAX_PINNED_HISTORY + maxRecentHistoryForType(type)
        }

        fun maxRecentHistoryForType(type: String): Int {
            return if (type == TYPE_STICKER) MAX_RECENT_STICKER_HISTORY else MAX_RECENT_GIF_HISTORY
        }
    }

    data class KlipyItem(
        val id: String,
        val url: String,
        val width: Int,
        val height: Int,
        val previewUrl: String? = null,
        val isPinned: Boolean = false
    )
}
