package com.adobe.marketing.mobile.telemetry.internal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.adobe.marketing.mobile.telemetry.TelemetryRecord
import org.json.JSONObject

/**
 * SQLite-backed durable outbox (decided design: records persist to a bounded SQLite
 * outbox and survive process death). Single table, FIFO by rowid, JSON payload.
 * All methods are synchronized; callers may be the capture worker or network callback.
 *
 * Implementation detail, not a supported public API surface.
 */
class SqliteOutbox(context: Context) : RecordOutbox {
    private val helper = object : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "seq INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "recordId TEXT NOT NULL UNIQUE, " +
                    "timestamp INTEGER NOT NULL, " +
                    "body TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE"); onCreate(db)
        }
    }
    private val lock = Any()
    private val db get() = helper.writableDatabase

    override fun insert(record: TelemetryRecord) = synchronized(lock) {
        db.insertWithOnConflict(TABLE, null, ContentValues().apply {
            put("recordId", record.recordId)
            put("timestamp", record.timestamp)
            put("body", record.toJson().toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        Unit
    }

    override fun evictOldest() = synchronized(lock) {
        db.execSQL("DELETE FROM $TABLE WHERE seq = (SELECT MIN(seq) FROM $TABLE)")
        Unit
    }

    override fun pending(): List<TelemetryRecord> = synchronized(lock) { read(null) }

    override fun pendingBytes(): Int = synchronized(lock) {
        db.rawQuery("SELECT COALESCE(SUM(LENGTH(body)), 0) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    override fun count(): Int = synchronized(lock) {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    override fun oldestTimestamp(): Long? = synchronized(lock) {
        db.rawQuery("SELECT timestamp FROM $TABLE ORDER BY seq LIMIT 1", null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }
    }

    override fun peekBatch(maxBytes: Int): List<TelemetryRecord> = synchronized(lock) {
        val out = mutableListOf<TelemetryRecord>()
        var bytes = 0
        for (record in read(null)) {
            val size = record.toJson().toString().toByteArray().size
            if (out.isNotEmpty() && bytes + size > maxBytes) break
            out.add(record); bytes += size
        }
        out
    }

    override fun remove(recordIds: Collection<String>) = synchronized(lock) {
        if (recordIds.isEmpty()) return@synchronized
        db.beginTransaction()
        try {
            recordIds.forEach { db.delete(TABLE, "recordId = ?", arrayOf(it)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clear() = synchronized(lock) {
        db.delete(TABLE, null, null)
        Unit
    }

    private fun read(limit: Int?): List<TelemetryRecord> {
        val out = mutableListOf<TelemetryRecord>()
        db.rawQuery(
            "SELECT body FROM $TABLE ORDER BY seq" + (limit?.let { " LIMIT $it" } ?: ""), null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { TelemetryRecord.fromJson(JSONObject(cursor.getString(0))) }
                    .getOrNull()?.let(out::add)
            }
        }
        return out
    }

    private companion object {
        const val DB_NAME = "adobe.telemetry.outbox"
        const val DB_VERSION = 1
        const val TABLE = "records"
    }
}
