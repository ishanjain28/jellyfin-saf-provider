package me.ishan.jellyfin_saf

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
import org.jellyfin.sdk.model.DateTime
import java.time.LocalDateTime

@Serializable
enum class ContentState {
    COMPLETE, DOWNLOADING, PARTIAL
}

data class MediaCacheRecord(
    val id: UUID,
    val state: ContentState,
    private val chunks: BitSet,
    val sizeBytes: Long,
    val lastAccessTime: Long
) {
    fun getFile(filesDir: File): File {
        return File(filesDir, "$id.cache")
    }

    fun getChunks(): BitSet {
        return chunks
    }

    companion object {
        const val CHUNK_SIZE: Long = 2 * 1024 * 1024 // 2MiB
    }
}

data class TrackMetadata(
    val id: UUID,
    val title: String,
    val artists: List<String>,
    val album: String,
    val albumId: UUID?,
    val year: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val trackNumber: Int?,
    val numTracks: Int?,
    val discNumber: Int?,
    val dateModifiedMs: DateTime?,
    val mimeType: String,
    val genres: List<String>,
    val albumArtist: String,
    val dateCreated: DateTime?,
    val isFavourite: Boolean = false,
    val lyrics: String? = null,

    // used locally
    val lastFetched: Long? = null,
    val lastAccessed: Long? = null,
) {
    companion object {
        const val MULTIVALUE_SEP = " // "
        const val STALE_LIMIT: Long = 2 * 24 * 60 * 60 * 1000 // 2 Days
    }

    fun isStale(): Boolean {
        return lastFetched != null && (System.currentTimeMillis() - lastFetched) >= STALE_LIMIT
    }
}

data class LyricsMetadata(
    val id: UUID,
    val content: String,
    val isSynced: Boolean,
    // used locally
    val lastFetched: Long? = null,
    val lastAccessed: Long? = null,
) {
    companion object {
        const val STALE_LIMIT: Long = 7 * 24 * 60 * 60 * 1000 // 7 Days}
    }

    fun isStale(): Boolean {
        return lastFetched != null && (System.currentTimeMillis() - lastFetched) >= STALE_LIMIT
    }
}

object DatabaseManager {
    private var INSTANCE: MediaDatabaseHelper? = null

    fun getInstance(context: Context?): MediaDatabaseHelper {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: MediaDatabaseHelper(context?.applicationContext).also {
                INSTANCE = it
            }
        }
    }
}

class MediaDatabaseHelper(context: Context?) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase?) {
        setWriteAheadLoggingEnabled(true)
        super.onConfigure(db)
    }

    companion object {
        private const val DATABASE_NAME = "jellyfin_media_cache.db"
        private const val DATABASE_VERSION = 1

        private const val TABLE_METADATA = "track_metadata"
        private const val COL_META_ID = "id"
        private const val COL_META_TITLE = "title"
        private const val COL_META_STATE = "state"
        private const val COL_META_ARTIST = "artist"
        private const val COL_META_ALBUM = "album"
        private const val COL_META_ALBUM_ID = "album_id"
        private const val COL_META_DURATION = "duration"
        private const val COL_META_SIZE = "size"
        private const val COL_META_MIME = "mime_type"
        private const val COL_META_TRACK_NUM = "track_number"
        private const val COL_META_DISC_NUM = "disc_number"
        private const val COL_META_ALBUM_ARTIST = "album_artist"
        private const val COL_META_DATE_MODIFIED = "date_modified"
        private const val COL_META_DATE_CREATED = "date_created"
        private const val COL_META_IS_FAVOURITE = "is_favourite"
        private const val COL_META_NUM_TRACKS = "num_tracks"
        private const val COL_META_YEAR = "year"
        private const val COL_META_GENRES = "genres"
        private const val COL_META_TRACK_LYRICS = "lyrics"
        private const val COL_META_TRACK_LYRICS_SYNCED = "lyrics_synced"
        private const val COL_META_CHUNKS = "chunks"
        private const val COL_LAST_FETCHED = "last_fetched"
        private const val COL_LAST_ACCESSED = "last_accessed"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createMetaTable = """
            CREATE TABLE $TABLE_METADATA (
                $COL_META_ID TEXT PRIMARY KEY,
                $COL_META_TITLE TEXT,
                $COL_META_STATE TEXT NOT NULL DEFAULT ${ContentState.PARTIAL},
                $COL_META_ARTIST TEXT,
                $COL_META_ALBUM TEXT,
                $COL_META_ALBUM_ID TEXT,
                $COL_META_DURATION INTEGER,
                $COL_META_SIZE INTEGER,
                $COL_META_MIME TEXT,
                $COL_META_TRACK_NUM INTEGER,
                $COL_META_DISC_NUM INTEGER,
                $COL_META_ALBUM_ARTIST TEXT,
                $COL_META_DATE_MODIFIED TEXT,
                $COL_META_DATE_CREATED TEXT,
                $COL_META_IS_FAVOURITE INTEGER DEFAULT 0,
                $COL_META_NUM_TRACKS INTEGER DEFAULT 0,
                $COL_META_YEAR INTEGER,
                $COL_META_GENRES TEXT,
                $COL_META_TRACK_LYRICS TEXT,
                $COL_META_TRACK_LYRICS_SYNCED INTEGER DEFAULT 0,
                $COL_META_CHUNKS BLOB,
                $COL_LAST_FETCHED INTEGER NOT NULL,
                $COL_LAST_ACCESSED INTEGER NOT NULL
            )
        """.trimIndent()
        db.execSQL(createMetaTable)

        // Create indices for frequently queried columns
        db.execSQL("CREATE INDEX idx_last_accessed ON $TABLE_METADATA($COL_LAST_ACCESSED)")
        db.execSQL("CREATE INDEX idx_last_fetched ON $TABLE_METADATA($COL_LAST_FETCHED)")
        db.execSQL("CREATE INDEX idx_state ON $TABLE_METADATA($COL_META_STATE)")
        db.execSQL("CREATE INDEX idx_is_favourite ON $TABLE_METADATA($COL_META_IS_FAVOURITE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) {
            val createMetaTable = """
                CREATE TABLE $TABLE_METADATA (
                    $COL_META_ID TEXT PRIMARY KEY,
                    $COL_META_TITLE TEXT,
                    $COL_META_STATE TEXT NOT NULL DEFAULT ${ContentState.PARTIAL},
                    $COL_META_ARTIST TEXT,
                    $COL_META_ALBUM TEXT,
                    $COL_META_ALBUM_ID TEXT,
                    $COL_META_DURATION INTEGER,
                    $COL_META_SIZE INTEGER,
                    $COL_META_MIME TEXT,
                    $COL_META_TRACK_NUM INTEGER,
                    $COL_META_DISC_NUM INTEGER,
                    $COL_META_ALBUM_ARTIST TEXT,
                    $COL_META_DATE_MODIFIED TEXT,
                    $COL_META_DATE_CREATED TEXT,
                    $COL_META_IS_FAVOURITE INTEGER DEFAULT 0,
                    $COL_META_NUM_TRACKS INTEGER DEFAULT 0,
                    $COL_META_YEAR INTEGER,
                    $COL_META_GENRES TEXT,
                    $COL_META_TRACK_LYRICS TEXT,
                    $COL_META_TRACK_LYRICS_SYNCED TEXT DEFAULT 0,
                    $COL_META_CHUNKS BLOB NOT NULL,
                    $COL_LAST_FETCHED INTEGER NOT NULL,
                    $COL_LAST_ACCESSED INTEGER NOT NULL
                )
            """.trimIndent()
            db.execSQL(createMetaTable)
        }
    }

    fun saveMetadata(metadata: TrackMetadata) {
        val values = ContentValues().apply {
            put(COL_META_ID, metadata.id.toString())
            put(COL_META_TITLE, metadata.title)
            put(COL_META_ARTIST, metadata.artists.joinToString(TrackMetadata.MULTIVALUE_SEP))
            put(COL_META_ALBUM, metadata.album)
            put(COL_META_ALBUM_ID, metadata.albumId?.toString())
            put(COL_META_DURATION, metadata.durationMs)
            put(COL_META_SIZE, metadata.sizeBytes)
            put(COL_META_MIME, metadata.mimeType)
            put(COL_META_TRACK_NUM, metadata.trackNumber)
            put(COL_META_DISC_NUM, metadata.discNumber)
            put(COL_META_ALBUM_ARTIST, metadata.albumArtist)
            put(COL_META_DATE_MODIFIED, metadata.dateModifiedMs.toString())
            put(COL_META_DATE_CREATED, metadata.dateCreated.toString())
            put(COL_META_IS_FAVOURITE, metadata.isFavourite)
            put(COL_META_NUM_TRACKS, metadata.numTracks)
            put(COL_META_YEAR, metadata.year)
            put(COL_META_GENRES, metadata.genres.joinToString(TrackMetadata.MULTIVALUE_SEP))
            put(COL_LAST_FETCHED, metadata.lastFetched ?: System.currentTimeMillis())
            put(COL_LAST_ACCESSED, metadata.lastAccessed ?: System.currentTimeMillis())
        }

        upsertData(metadata.id, values)
    }

    fun saveLyrics(trackId: UUID, content: String?, isSynced: Boolean) {
        val values = ContentValues().apply {
            put(COL_META_ID, trackId.toString())
            put(COL_META_TRACK_LYRICS, content)
            put(COL_META_TRACK_LYRICS_SYNCED, isSynced)
        }

        upsertData(trackId, values)
    }

    fun getCachedMetadata(trackId: UUID): TrackMetadata? {
        val db = writableDatabase
        return db.transaction {
            update(TABLE_METADATA, ContentValues().apply {
                put(COL_LAST_ACCESSED, System.currentTimeMillis())
            }, "$COL_META_ID = ?", arrayOf(trackId.toString()))

            query(
                TABLE_METADATA, arrayOf(
                    COL_META_ID,
                    COL_META_TITLE,
                    COL_META_ARTIST,
                    COL_META_ALBUM,
                    COL_META_ALBUM_ID,
                    COL_META_DURATION,
                    COL_META_SIZE,
                    COL_META_MIME,
                    COL_META_TRACK_NUM,
                    COL_META_DISC_NUM,
                    COL_META_ALBUM_ARTIST,
                    COL_META_DATE_MODIFIED,
                    COL_META_DATE_CREATED,
                    COL_META_IS_FAVOURITE,
                    COL_META_NUM_TRACKS,
                    COL_META_YEAR,
                    COL_META_GENRES,
                    COL_LAST_FETCHED,
                    COL_LAST_ACCESSED
                ), "$COL_META_ID = ?", arrayOf(trackId.toString()), null, null, null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    mapCursorToMetadata(cursor)
                } else null
            }
        }
    }

    fun getCachedLyrics(trackId: UUID): LyricsMetadata? {
        val db = writableDatabase
        return db.transaction {
            update(TABLE_METADATA, ContentValues().apply {
                put(COL_LAST_ACCESSED, System.currentTimeMillis())
            }, "$COL_META_ID = ?", arrayOf(trackId.toString()))

            query(
                TABLE_METADATA,
                arrayOf(COL_META_TRACK_LYRICS, COL_META_TRACK_LYRICS_SYNCED, COL_LAST_FETCHED),
                "$COL_META_ID = ?",
                arrayOf(trackId.toString()),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val lastFetched = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_FETCHED))
                    val lyrics =
                        cursor.getString(cursor.getColumnIndexOrThrow(COL_META_TRACK_LYRICS))
                    val isSynced =
                        (cursor.getInt(cursor.getColumnIndexOrThrow(COL_META_TRACK_LYRICS_SYNCED)) == 1)

                    if (lyrics == null) {
                        null
                    } else {
                        LyricsMetadata(
                            id = trackId,
                            content = lyrics,
                            isSynced = isSynced,
                            lastAccessed = System.currentTimeMillis(),
                            lastFetched = lastFetched,
                        )
                    }
                } else null
            }
        }
    }

    private fun mapCursorToMetadata(cursor: Cursor): TrackMetadata {
        return TrackMetadata(
            id = cursor.getString(
                cursor.getColumnIndexOrThrow(COL_META_ID)
            )?.let { UUID.fromString(it) }!!,
            title = cursor.getString(
                cursor.getColumnIndexOrThrow(COL_META_TITLE)
            ) ?: "Unknown Title",
            artists = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_ARTIST))
                ?.split(TrackMetadata.MULTIVALUE_SEP) ?: emptyList(),
            album = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_ALBUM))
                ?: "Unknown Album",
            albumId = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_ALBUM_ID))
                ?.let { UUID.fromString(it) },
            durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_DURATION)),
            sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_SIZE)),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_MIME)) ?: "",
            trackNumber = cursor.getInt(cursor.getColumnIndexOrThrow(COL_META_TRACK_NUM)),
            discNumber = cursor.getInt(cursor.getColumnIndexOrThrow(COL_META_DISC_NUM)),
            albumArtist = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_ALBUM_ARTIST))
                ?: "Unknown Album Artist",
            dateModifiedMs = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    COL_META_DATE_MODIFIED
                )
            )?.let { LocalDateTime.parse(it) },
            dateCreated = cursor.getString(
                cursor.getColumnIndexOrThrow(
                    COL_META_DATE_CREATED
                )
            )?.let { LocalDateTime.parse(it) },
            isFavourite = cursor.getInt(cursor.getColumnIndexOrThrow(COL_META_IS_FAVOURITE)) == 1,
            numTracks = cursor.getInt(cursor.getColumnIndexOrThrow(COL_META_NUM_TRACKS)),
            year = cursor.getInt(cursor.getColumnIndexOrThrow(COL_META_YEAR)),
            genres = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_GENRES))
                ?.split(TrackMetadata.MULTIVALUE_SEP) ?: emptyList(),
            lastFetched = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_FETCHED)),
            lastAccessed = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_ACCESSED))
        )
    }

    fun getMediaCacheRecord(trackId: UUID): MediaCacheRecord? {
        val db = readableDatabase
        return db.query(
            TABLE_METADATA, arrayOf(
                COL_META_ID,
                COL_META_STATE,
                COL_META_CHUNKS,
                COL_META_SIZE,
                COL_LAST_ACCESSED,
            ), "$COL_META_ID = ?", arrayOf(trackId.toString()), null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                val stateStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_STATE))
                val chunksBlob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_META_CHUNKS))
                val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_SIZE))
                val lastAccessTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_ACCESSED))

                // Create a 256-bit BitSet from the stored BLOB
                val bitSet = if (chunksBlob == null) {
                    BitSet(256)
                } else {
                    BitSet.valueOf(chunksBlob)
                }

                return MediaCacheRecord(
                    id = trackId,
                    state = ContentState.valueOf(stateStr),
                    chunks = bitSet,
                    sizeBytes = sizeBytes,
                    lastAccessTime = lastAccessTime
                )
            } else {
                null
            }
        }
    }

    fun updateState(id: UUID, state: ContentState) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_META_STATE, state.name)
        }

        db.update(TABLE_METADATA, values, "$COL_META_ID = ?", arrayOf(id.toString()))
    }

    fun updateChunks(id: UUID, newChunks: BitSet) {
        val db = writableDatabase
        db.transaction {
            val currentChunks: BitSet? = query(
                TABLE_METADATA,
                arrayOf(COL_META_CHUNKS),
                "$COL_META_ID = ?",
                arrayOf(id.toString()),
                null,
                null,
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val blob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_META_CHUNKS))
                    val bitSet = if (blob == null) {
                        BitSet(256)
                    } else {
                        BitSet.valueOf(blob)
                    }

                    bitSet
                } else {
                    null
                }
            }
            if (currentChunks == null) {
                return
            }

            currentChunks.or(newChunks)
            val values = ContentValues().apply {
                put(COL_META_CHUNKS, currentChunks.toByteArray())
            }
            update(TABLE_METADATA, values, "$COL_META_ID = ?", arrayOf(id.toString()))
        }
    }

    fun updateLastAccessTime(id: UUID, timeMs: Long) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_LAST_ACCESSED, timeMs)
        }
        db.update(TABLE_METADATA, values, "$COL_META_ID = ?", arrayOf(id.toString()))
    }

    // Used when opened for streaming from web
    fun markOpenedForStreaming(id: UUID) {
        val db = writableDatabase
        db.transaction {
            val existing = getMediaCacheRecord(id)

            if (existing != null) {
                // Record exists - just update last access, don't touch chunks!
                val values = ContentValues().apply {
                    put(COL_LAST_ACCESSED, System.currentTimeMillis())
                }
                update(TABLE_METADATA, values, "$COL_META_ID = ?", arrayOf(id.toString()))
            } else {
                // New record - initialize with empty chunks
                val values = ContentValues().apply {
                    put(COL_META_ID, id.toString())
                    put(COL_META_STATE, ContentState.PARTIAL.name)
                    put(COL_META_CHUNKS, BitSet(256).toByteArray())
                    put(COL_LAST_FETCHED, 0)
                }
                upsertData(id, values)
            }
        }
    }

    fun upsertData(id: UUID, values: ContentValues): Int {
        val db = writableDatabase
        db.transaction {
            val rowsAffected =
                update(TABLE_METADATA, values, "$COL_META_ID = ?", arrayOf(id.toString()))

            if (rowsAffected > 0) {
                rowsAffected
            } else {
                if (!values.containsKey(COL_META_ID)) {
                    values.put(COL_META_ID, id.toString())
                }
                // For a new row, ensure we have the NOT NULL timestamps
                if (!values.containsKey(COL_LAST_FETCHED)) {
                    values.put(COL_LAST_FETCHED, System.currentTimeMillis())
                }
                if (!values.containsKey(COL_LAST_ACCESSED)) {
                    values.put(COL_LAST_ACCESSED, System.currentTimeMillis())
                }

                insert(TABLE_METADATA, null, values)
            }
        }

        return 0
    }

    fun resetDatabase() {
        val db = writableDatabase
        db.execSQL("DROP TABLE IF EXISTS $TABLE_METADATA")
        onCreate(db)
    }

    fun clearAll() {
        val db = writableDatabase
        db.delete(TABLE_METADATA, null, null)
    }

    fun getAllTracks(state: ContentState?, excludeFavourites: Boolean): List<UUID> {
        val db = readableDatabase
        val tracks = mutableListOf<UUID>()

        val selection = if (state != null) {
            if (excludeFavourites) {
                "$COL_META_STATE == ? AND $COL_META_IS_FAVOURITE == 0"
            } else {
                "$COL_META_STATE == ?"
            }
        } else if (excludeFavourites) {
            "$COL_META_IS_FAVOURITE == 0"
        } else {
            ""
        }

        val selectionArgs = if (state != null) {
            arrayOf(state.name)
        } else {
            arrayOf()
        }

        db.query(
            TABLE_METADATA, arrayOf(COL_META_ID), selection, selectionArgs, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_ID))
                tracks.add(UUID.fromString(id))
            }
        }

        return tracks
    }

    fun deleteAllTracks(state: ContentState?, excludeFavourites: Boolean = false) {
        val db = writableDatabase

        val selection = if (state != null) {
            if (excludeFavourites) {
                "$COL_META_STATE == ? AND $COL_META_IS_FAVOURITE == 0"
            } else {
                "$COL_META_STATE == ?"
            }
        } else if (excludeFavourites) {
            "$COL_META_IS_FAVOURITE == 0"
        } else {
            ""
        }

        val selectionArgs = if (state != null) {
            arrayOf(state.name)
        } else {
            arrayOf()
        }

        db.delete(TABLE_METADATA, selection, selectionArgs)
    }

    fun getFavouriteTracks(): List<UUID> {
        val db = readableDatabase
        val tracks = mutableListOf<UUID>()

        db.query(
            TABLE_METADATA,
            arrayOf(COL_META_ID),
            "$COL_META_IS_FAVOURITE == 1",
            null,
            null,
            null,
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_ID))
                tracks.add(UUID.fromString(id))
            }
        }

        return tracks
    }

    fun deleteFavouriteTracks() {
        val db = writableDatabase
        db.delete(TABLE_METADATA, "$COL_META_IS_FAVOURITE == 1", null)
    }
}
