package com.adobe.marketing.mobile.telemetry.internal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/**
 * SQLite-backed queue for Phase-1 automatic events. Writes are synchronous so a crash
 * event can be persisted from the uncaught-exception handler before the process dies.
 * Bounded: oldest rows are evicted past [MAX_ROWS].
 */
internal class DiskEventQueue(context: Context, private val maxRows: Int = MAX_ROWS) : EventQueue {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "timestamp INTEGER NOT NULL, " +
                    "body TEXT NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }
    private val lock = Any()
    private val db get() = helper.writableDatabase

    override fun enqueue(event: AutoEvent) = synchronized(lock) {
        val id = db.insertWithOnConflict(TABLE, null, ContentValues().apply {
            put("timestamp", event.timestampMs)
            put("body", event.toJson().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
        while (sizeLocked() > maxRows) {
            db.execSQL("DELETE FROM $TABLE WHERE id = (SELECT MIN(id) FROM $TABLE)")
        }
        if (id != -1L) TelemetryStartupDiagnostics.current.persisted(id, event) { sizeLocked() }
        Unit
    }

    override fun peekChunk(maxEvents: Int): List<QueuedEvent> = synchronized(lock) {
        val out = mutableListOf<QueuedEvent>()
        db.rawQuery("SELECT id, body FROM $TABLE ORDER BY id LIMIT $maxEvents", null).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { AutoEvent.fromJson(JSONObject(cursor.getString(1))) }
                    .getOrNull()?.let { out.add(QueuedEvent(cursor.getLong(0), it)) }
            }
        }
        out
    }

    override fun delete(ids: Collection<Long>) = synchronized(lock) {
        if (ids.isEmpty()) return@synchronized
        db.beginTransaction()
        try {
            ids.forEach { db.delete(TABLE, "id = ?", arrayOf(it.toString())) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun clear() = synchronized(lock) {
        db.delete(TABLE, null, null)
        Unit
    }

    override fun size(): Int = synchronized(lock) { sizeLocked() }

    private fun sizeLocked(): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    companion object {
        const val MAX_ROWS = 5_000
        private const val DB_NAME = "adobe.telemetry.autoqueue"
        private const val DB_VERSION = 1
        private const val TABLE = "events"
    }
}
