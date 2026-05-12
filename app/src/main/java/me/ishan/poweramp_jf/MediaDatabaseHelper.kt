package me.ishan.poweramp_jf

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.util.BitSet
import androidx.core.database.sqlite.transaction

enum class MediaType {
    ALBUM, TRACK
}

@Serializable
enum class ContentState {
    COMPLETE, DOWNLOADING, PARTIAL, FAILED
}

data class MediaCacheRecord(
    val id: UUID,
    val type: MediaType,
    val state: ContentState,
    private val chunks: BitSet,
    val sizeBytes: Long,
    val lastAccessTime: Long
) {
    fun getFile(cacheDir: File): File {
        return File(cacheDir, "$id.cache")
    }

    fun getChunks(): BitSet {
        return chunks
    }

    companion object {
        const val CHUNK_SIZE: Long = 2 * 1024 * 1024 // 2MiB
    }
}

class MediaDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase?) {
        setWriteAheadLoggingEnabled(true)
        super.onConfigure(db)
    }

    companion object {
        private const val DATABASE_NAME = "jellyfin_media_cache.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_NAME = "cache_records"
        private const val COL_ID = "id"
        private const val COL_TYPE = "type"
        private const val COL_STATE = "state"
        private const val COL_CHUNKS = "chunks"
        private const val COL_SIZE_BYTES = "size_bytes"
        private const val COL_LAST_ACCESS_TIME = "last_access_time"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createTableQuery = """
            CREATE TABLE $TABLE_NAME (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TYPE TEXT NOT NULL,
                $COL_STATE TEXT NOT NULL,
                $COL_CHUNKS BLOB NOT NULL,
                $COL_SIZE_BYTES INTEGER NOT NULL,
                $COL_LAST_ACCESS_TIME INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createTableQuery)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun insertOrUpdate(record: MediaCacheRecord) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, record.id.toString())
            put(COL_TYPE, record.type.name)
            put(COL_STATE, record.state.name)
            put(COL_CHUNKS, record.getChunks().toByteArray())
            put(COL_SIZE_BYTES, record.sizeBytes)
            put(COL_LAST_ACCESS_TIME, record.lastAccessTime)
        }
        db.replace(TABLE_NAME, null, values)
    }

    fun getRecord(id: UUID): MediaCacheRecord? {
        val db = readableDatabase
        return db.query(
            TABLE_NAME, null, "$COL_ID = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                mapCursorToRecord(cursor)
            } else {
                null
            }
        }
    }

    fun getAllRecords(): List<MediaCacheRecord> {
        val db = readableDatabase
        val records = mutableListOf<MediaCacheRecord>()
        db.query(TABLE_NAME, null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                records.add(mapCursorToRecord(cursor))
            }
        }
        return records
    }

    fun updateState(id: UUID, state: ContentState) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_STATE, state.name)
        }
        db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun updateChunks(id: UUID, newChunks: BitSet) {
        val db = writableDatabase
        db.transaction {
            val record = getRecord(id)
            if (record != null) {
                val currentChunks = record.getChunks()
                currentChunks.or(newChunks)
                val values = ContentValues().apply {
                    put(COL_CHUNKS, currentChunks.toByteArray())
                }
                update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id.toString()))
            }
        }
    }

    fun updateLastAccessTime(id: UUID, timeMs: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LAST_ACCESS_TIME, timeMs)
        }
        db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun updateSize(id: UUID, sizeBytes: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_SIZE_BYTES, sizeBytes)
        }
        db.update(TABLE_NAME, values, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun deleteRecord(id: UUID) {
        val db = writableDatabase
        db.delete(TABLE_NAME, "$COL_ID = ?", arrayOf(id.toString()))
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_NAME, null, null)
    }

    private fun mapCursorToRecord(cursor: Cursor): MediaCacheRecord {
        val idStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID))
        val typeStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_TYPE))
        val stateStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_STATE))
        val chunksBlob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_CHUNKS))
        val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SIZE_BYTES))
        val lastAccessTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_ACCESS_TIME))

        // Create a 256-bit BitSet from the stored BLOB
        val bitSet = BitSet.valueOf(chunksBlob)

        return MediaCacheRecord(
            id = UUID.fromString(idStr),
            type = MediaType.valueOf(typeStr),
            state = ContentState.valueOf(stateStr),
            chunks = bitSet,
            sizeBytes = sizeBytes,
            lastAccessTime = lastAccessTime
        )
    }
}
