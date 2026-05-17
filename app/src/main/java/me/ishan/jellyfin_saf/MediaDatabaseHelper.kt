package me.ishan.jellyfin_saf

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.StrictMode
import android.util.Log
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.util.BitSet
import androidx.core.database.sqlite.transaction
import me.ishan.jellyfin_saf.TrackStream.Companion.TAG
import org.jellyfin.sdk.model.DateTime
import java.time.LocalDateTime

@Serializable
enum class ContentState {
	COMPLETE, PARTIAL
}

data class MediaCacheRecord(
	val id: UUID,
	private var state: ContentState,
	private val chunks: BitSet,
	val sizeBytes: Long,
	val durationMs: Long,
) {
	fun getFile(filesDir: File): File {
		return File(filesDir, "$id.cache")
	}
	
	fun chunks(): BitSet {
		return chunks
	}
	
	// Perform the bitwise or operation chunks | updatedChunks
	fun state(): ContentState {
		return state
	}
	
	companion object {
		const val CHUNK_SIZE: Long = 256 * 1024 // 256KiB
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
	}
	
	override fun onCreate(db: SQLiteDatabase) {
		StrictMode.setVmPolicy(
			StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog()
				.penaltyDropBox().build()
		)
		
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
                $COL_LAST_FETCHED INTEGER NOT NULL
            )
        """.trimIndent()
		db.execSQL(createMetaTable)
		
		// Create indices for frequently queried columns
		db.execSQL("CREATE INDEX idx_last_fetched ON $TABLE_METADATA($COL_LAST_FETCHED)")
		db.execSQL("CREATE INDEX idx_state ON $TABLE_METADATA($COL_META_STATE)")
		db.execSQL("CREATE INDEX idx_is_favourite ON $TABLE_METADATA($COL_META_IS_FAVOURITE)")
		db.execSQL("CREATE INDEX idx_album ON $TABLE_METADATA($COL_META_ALBUM_ID)")
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
                    $COL_LAST_FETCHED INTEGER NOT NULL
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
			put(COL_META_DATE_MODIFIED, metadata.dateModifiedMs?.toString())
			put(COL_META_DATE_CREATED, metadata.dateCreated?.toString())
			put(COL_META_IS_FAVOURITE, metadata.isFavourite)
			put(COL_META_NUM_TRACKS, metadata.numTracks)
			put(COL_META_YEAR, metadata.year)
			put(COL_META_GENRES, metadata.genres.joinToString(TrackMetadata.MULTIVALUE_SEP))
			put(COL_LAST_FETCHED, metadata.lastFetched ?: System.currentTimeMillis())
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
		val db = readableDatabase
		db.query(
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
				COL_LAST_FETCHED
			), "$COL_META_ID = ?", arrayOf(trackId.toString()), null, null, null
		).use { cursor ->
			if (!cursor.moveToFirst()) return null
			
			return mapCursorToMetadata(cursor)
		}
	}
	
	fun getCachedLyrics(trackId: UUID): LyricsMetadata? {
		val db = readableDatabase
		return db.query(
			TABLE_METADATA,
			arrayOf(COL_META_TRACK_LYRICS, COL_META_TRACK_LYRICS_SYNCED, COL_LAST_FETCHED),
			"$COL_META_ID = ?",
			arrayOf(trackId.toString()),
			null,
			null,
			null
		).use { cursor ->
			if (!cursor.moveToFirst()) return null
			
			val lastFetched = cursor.getLong(cursor.getColumnIndexOrThrow(COL_LAST_FETCHED))
			val lyrics = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_TRACK_LYRICS))
			val isSynced =
				(cursor.getInt(cursor.getColumnIndexOrThrow(COL_META_TRACK_LYRICS_SYNCED)) == 1)
			
			if (lyrics == null) return null
			
			LyricsMetadata(
				id = trackId,
				content = lyrics,
				isSynced = isSynced,
				lastFetched = lastFetched,
			)
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
		)
	}
	
	fun getMediaCacheRecord(trackId: UUID): MediaCacheRecord? {
		val db = readableDatabase
		
		db.query(
			TABLE_METADATA, arrayOf(
				COL_META_STATE, COL_META_CHUNKS, COL_META_SIZE, COL_META_DURATION
			), "$COL_META_ID = ?", arrayOf(trackId.toString()), null, null, null
		).use { cursor ->
			if (!cursor.moveToFirst()) return null
			
			val stateStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_STATE))
			val chunksBlob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_META_CHUNKS))
			val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_SIZE))
			val durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_DURATION))
			
			val bitSet = if (chunksBlob == null) BitSet() else BitSet.valueOf(chunksBlob)
			
			return MediaCacheRecord(
				id = trackId,
				state = ContentState.valueOf(stateStr),
				chunks = bitSet,
				sizeBytes = sizeBytes,
				durationMs = durationMs,
			)
		}
	}
	
	fun updateState(id: UUID, newChunks: BitSet) {
		val db = writableDatabase
		
		db.transaction {
			query(
				TABLE_METADATA,
				arrayOf(COL_META_CHUNKS, COL_META_SIZE),
				"$COL_META_ID = ?",
				arrayOf(id.toString()),
				null,
				null,
				null
			).use { cursor ->
				val bitset = if (!cursor.moveToFirst()) return@use BitSet() else {
					val blob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_META_CHUNKS))
					if (blob != null) BitSet.valueOf(blob) else BitSet()
				}
				val size = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_SIZE))
				
				val totalChunks =
					(size + MediaCacheRecord.CHUNK_SIZE - 1) / MediaCacheRecord.CHUNK_SIZE
				
				bitset.or(newChunks)
				val newState =
					if (bitset.cardinality() >= totalChunks) ContentState.COMPLETE else ContentState.PARTIAL
				
				if (newState == ContentState.COMPLETE) {
					Log.i(TAG, "[$id] Download complete (${bitset.cardinality()}/$totalChunks)")
				}
				
				val values = ContentValues().apply {
					put(COL_META_CHUNKS, bitset.toByteArray())
					put(COL_META_STATE, newState.name)
				}
				
				update(TABLE_METADATA, values, "$COL_META_ID = ?", arrayOf(id.toString()))
			}
		}
	}
	
	// Used when opened for streaming from web
	fun markOpenedForStreaming(id: UUID, sizeBytes: Long, durationMs: Long): MediaCacheRecord {
		val existing = getMediaCacheRecord(id)
		if (existing != null) return existing
		
		// New record - initialize with empty chunks but correct size/duration
		val values = ContentValues().apply {
			put(COL_META_ID, id.toString())
			put(COL_META_STATE, ContentState.PARTIAL.name)
			put(COL_META_CHUNKS, BitSet().toByteArray())
			put(COL_META_SIZE, sizeBytes)
			put(COL_META_DURATION, durationMs)
			put(COL_LAST_FETCHED, 0)
		}
		upsertData(id, values)
		
		return MediaCacheRecord(
			id = id,
			state = ContentState.PARTIAL,
			chunks = BitSet(),
			sizeBytes = sizeBytes,
			durationMs = durationMs,
		)
	}
	
	fun upsertData(id: UUID, values: ContentValues) {
		val db = writableDatabase
		db.transaction {
			val rowsAffected =
				update(TABLE_METADATA, values, "$COL_META_ID = ?", arrayOf(id.toString()))
			if (rowsAffected == 0) {
				if (!values.containsKey(COL_META_ID)) {
					values.put(COL_META_ID, id.toString())
				}
				// For a new row, ensure we have the NOT NULL timestamps
				if (!values.containsKey(COL_LAST_FETCHED)) {
					values.put(COL_LAST_FETCHED, System.currentTimeMillis())
				}
				
				insert(TABLE_METADATA, null, values)
			}
		}
	}
	
	fun resetDatabase() {
		val db = writableDatabase
		db.execSQL("DROP TABLE IF EXISTS $TABLE_METADATA")
		onCreate(db)
	}
	
	fun getAllTracks(state: ContentState?, excludeFavourites: Boolean): List<UUID> {
		val db = readableDatabase
		val tracks = mutableListOf<UUID>()
		
		val selection = if (state != null) {
			if (excludeFavourites) {
				"$COL_META_STATE = ? AND $COL_META_IS_FAVOURITE = 0"
			} else {
				"$COL_META_STATE = ?"
			}
		} else if (excludeFavourites) {
			"$COL_META_IS_FAVOURITE = 0"
		} else {
			null
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
				"$COL_META_STATE = ? AND $COL_META_IS_FAVOURITE = 0"
			} else {
				"$COL_META_STATE = ?"
			}
		} else if (excludeFavourites) {
			"$COL_META_IS_FAVOURITE = 0"
		} else {
			null
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
			"$COL_META_IS_FAVOURITE = 1",
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
		db.delete(TABLE_METADATA, "$COL_META_IS_FAVOURITE = 1", null)
	}
	
	/**
	 * Gets all tracks that have some cached content (COMPLETE or PARTIAL),
	 * ordered by last access time (oldest first).
	 * Excludes favorites.
	 */
	fun getOldestAccessedTracks(excludeFavourites: Boolean = true): List<MediaCacheRecord> {
		val db = readableDatabase
		val records = mutableListOf<MediaCacheRecord>()
		
		val selection = if (excludeFavourites) "$COL_META_IS_FAVOURITE = 0" else null
		
		// TODO: figure out in what order, how do we decide old?
		db.query(
			TABLE_METADATA,
			arrayOf(
				COL_META_ID,
				COL_META_STATE,
				COL_META_CHUNKS,
				COL_META_SIZE,
				COL_META_DURATION
			),
			selection, null, null, null, null,
		).use { cursor ->
			while (cursor.moveToNext()) {
				val id = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_ID))
				val stateStr = cursor.getString(cursor.getColumnIndexOrThrow(COL_META_STATE))
				val chunksBlob = cursor.getBlob(cursor.getColumnIndexOrThrow(COL_META_CHUNKS))
				val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_SIZE))
				val durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_META_DURATION))
				val bitSet = if (chunksBlob == null) BitSet() else BitSet.valueOf(chunksBlob)
				
				records.add(
					MediaCacheRecord(
						id = UUID.fromString(id),
						state = ContentState.valueOf(stateStr),
						chunks = bitSet,
						sizeBytes = sizeBytes,
						durationMs = durationMs,
					)
				)
			}
		}
		return records
	}
	
	fun deleteTrackRecord(id: UUID) {
		val db = writableDatabase
		db.delete(TABLE_METADATA, "$COL_META_ID = ?", arrayOf(id.toString()))
	}
}