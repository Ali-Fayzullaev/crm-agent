package com.crm.whatsagent.queue

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.crm.whatsagent.models.MessageEvent
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "LocalQueue"
private const val DB_NAME = "crm_agent.db"
private const val DB_VERSION = 1
private const val TABLE = "pending_events"

/**
 * SQLite-backed local queue for message events.
 *
 * Events are persisted here first, then forwarded to the backend.
 * Once the backend acknowledges receipt (WebSocket connection confirmed send),
 * the entry is deleted.
 *
 * This provides offline resilience: if the connection drops, events accumulate
 * locally and are flushed on reconnect.
 */
class LocalMessageQueue(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val gson = Gson()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                idempotency_key TEXT    NOT NULL UNIQUE,
                payload         TEXT    NOT NULL,
                retry_count     INTEGER NOT NULL DEFAULT 0,
                created_at      INTEGER NOT NULL DEFAULT (strftime('%s','now'))
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    /** Enqueue an event. Silently ignores duplicates (same idempotency key). */
    suspend fun enqueue(event: MessageEvent) = withContext(Dispatchers.IO) {
        try {
            writableDatabase.execSQL(
                "INSERT OR IGNORE INTO $TABLE (idempotency_key, payload) VALUES (?, ?)",
                arrayOf(event.idempotencyKey, gson.toJson(event))
            )
        } catch (e: Exception) {
            Log.e(TAG, "enqueue failed: ${e.message}")
        }
    }

    /** Returns up to [limit] pending events ordered by insertion time. */
    suspend fun peek(limit: Int = 20): List<PendingEntry> = withContext(Dispatchers.IO) {
        val result = mutableListOf<PendingEntry>()
        readableDatabase.rawQuery(
            "SELECT id, idempotency_key, payload, retry_count FROM $TABLE ORDER BY id ASC LIMIT ?",
            arrayOf(limit.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PendingEntry(
                    id              = cursor.getLong(0),
                    idempotencyKey  = cursor.getString(1),
                    payload         = cursor.getString(2),
                    retryCount      = cursor.getInt(3),
                )
            }
        }
        result
    }

    fun parseEvent(entry: PendingEntry): MessageEvent? =
        try { gson.fromJson(entry.payload, MessageEvent::class.java) } catch (_: Exception) { null }

    /** Removes a successfully delivered event by its DB id. */
    suspend fun ack(id: Long) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL("DELETE FROM $TABLE WHERE id = ?", arrayOf(id))
    }

    /** Increments retry counter; removes entries that exceeded max retries. */
    suspend fun incrementRetry(id: Long, maxRetries: Int = 10) = withContext(Dispatchers.IO) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET retry_count = retry_count + 1 WHERE id = ?", arrayOf(id)
        )
        writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE id = ? AND retry_count > ?", arrayOf(id, maxRetries)
        )
    }

    /** Pending count (for logging/debug). */
    suspend fun pendingCount(): Long = withContext(Dispatchers.IO) {
        readableDatabase.compileStatement("SELECT COUNT(*) FROM $TABLE").simpleQueryForLong()
    }

    data class PendingEntry(
        val id: Long,
        val idempotencyKey: String,
        val payload: String,
        val retryCount: Int,
    )
}
