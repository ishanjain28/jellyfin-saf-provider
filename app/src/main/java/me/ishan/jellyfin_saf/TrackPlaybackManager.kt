package me.ishan.jellyfin_saf

import android.content.Context
import android.graphics.Point
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.all
import kotlin.concurrent.write


class TrackStream(
	val trackId: UUID,
	val entry: MediaCacheRecord,
	private val channel: FileChannel,
	private val db: MediaDatabaseHelper,
	private val jellyfinClient: JellyfinClientManager
) {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private val progressSignal = MutableSharedFlow<Unit>(
		replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
	)
	private var downloadJob: Job? = null
	private var metadataJob: Job? = null
	
	@Volatile
	private var isClosed: Boolean = false
	
	init {
		// Wait until coroutines are shut down to close the file
		scope.coroutineContext[Job]?.invokeOnCompletion {
			channel.close()
		}
	}
	
	fun fetchEOFMetadata() {
		// The last 2 chunks may have data players try to read. We should prefetch them
		// otherwise player will try to jump around right after reading the first few bytes
		// and cause problems!
		if (metadataJob?.isActive == true) {
			Log.d(TAG, "[$trackId] EOF Download already in progress")
			return
		}
		
		// short-lived job, don't care about lifetime
		metadataJob = scope.launch {
			val totalChunks = entry.totalChunks()
			val startOffset = entry.sizeBytes - (2 * MediaCacheRecord.CHUNK_SIZE)
			
			if (entry.chunks().nextClearBit(totalChunks - 2) >= totalChunks) {
				Log.i(
					TAG,
					"[$trackId] Download skipped to fetch EOF Metadata from $startOffset->${entry.sizeBytes}"
				)
				return@launch
			}
			
			Log.i(
				TAG,
				"[$trackId] Download started to fetch EOF Metadata from $startOffset->${entry.sizeBytes}"
			)
			
			try {
				jellyfinClient.downloadTrack(
					trackId = trackId,
					outputFile = channel,
					byteOffset = startOffset,
					onProgress = { bytesDownloaded ->
						val firstChunkId = (startOffset / MediaCacheRecord.CHUNK_SIZE).toInt()
						val lastChunkId =
							((startOffset + bytesDownloaded - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()
						
						entry.chunks().set(
							firstChunkId, lastChunkId + 1
						) // set 2nd arg is exclusive
						// don't emit progress signals from here
					})
				Log.i(TAG, "[$trackId] Download completed from EOF - 2 to EOF")
			} catch (e: Exception) {
				Log.w(
					TAG, "[$trackId] Download interrupted for EOF Metadata at chunk: ${e.message}"
				)
			} finally {
				saveState()
			}
		}
	}
	
	fun startSequentialDownload(
		fromByteOffset: Long? = null
	) {
		if (downloadJob?.isActive == true) {
			Log.d(TAG, "[$trackId] Download already in progress")
			return
		}
		
		val startOffset =
			fromByteOffset ?: (entry.chunks().nextClearBit(0) * MediaCacheRecord.CHUNK_SIZE)
		val startChunk = (startOffset / MediaCacheRecord.CHUNK_SIZE).toInt()
		if (startOffset > entry.sizeBytes) return
		
		downloadJob = scope.launch {
			Log.i(
				TAG, "[$trackId] Download started from chunk $startChunk (${
					entry.chunks().cardinality()
				} already cached)"
			)
			try {
				jellyfinClient.downloadTrack(
					trackId = trackId,
					outputFile = channel,
					byteOffset = startOffset,
					onProgress = { bytesDownloaded ->
						// bytesDownloaded is relative to this download session, add startOffset for absolute position
						val firstChunkId = (startOffset / MediaCacheRecord.CHUNK_SIZE).toInt()
						val lastChunkId =
							((startOffset + bytesDownloaded - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()
						
						entry.chunks().set(
							firstChunkId, lastChunkId + 1
						) // set 2nd arg is exclusive
						progressSignal.tryEmit(Unit)
					})
				Log.i(TAG, "[$trackId] Download completed to EOF")
			} catch (e: Exception) {
				Log.w(
					TAG, "[$trackId] Download interrupted: ${e.message}"
				)
			} finally {
				saveState()
				progressSignal.tryEmit(Unit)
			}
		}
	}
	
	suspend fun waitForFirstChunk() {
		val endByte = bufferBytesForSeconds(5)
		val endChunk = (endByte / MediaCacheRecord.CHUNK_SIZE).toInt()
		
		if (entry.chunks().get(endChunk)) {
			Log.d(TAG, "[$trackId] First chunk already available")
			return
		}
		
		Log.d(TAG, "[$trackId] Waiting for initial buffer (chunk 0-$endChunk)")
		progressSignal.first { isClosed || entry.chunks().get(endChunk) }
	}
	
	fun blockUntilCanReadWithTimeout(offset: Long, size: Int, timeoutMs: Long = 5000) {
		val startChunk = (offset / MediaCacheRecord.CHUNK_SIZE).toInt()
		val endChunk = ((offset + size - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()
		
		if (entry.chunks().nextClearBit(startChunk) > endChunk) {
			return
		}
		
		// no point in waiting if download job died!
		// because we don't have the data and with downloadJob gone, we'll not get it
		if (downloadJob?.isActive != true) {
			// No active downloads, chunk will never arrive
			Log.w(TAG, "[$trackId] Chunk $startChunk not available and no active download")
			return  // Don't wait, return immediately
		}
		
		try {
			runBlocking {
				withTimeout(timeoutMs) {
					waitForData(offset, size)
				}
			}
		} catch (e: TimeoutCancellationException) {
			Log.w(TAG, "[$trackId] Timeout waiting for chunk $startChunk")
		}
	}
	
	fun bitrateInBps(): Long {
		return (entry.sizeBytes * 8) / (entry.durationMs / 1000)
	}
	
	fun bufferBytesForSeconds(seconds: Long): Long {
		return (bitrateInBps() / 8 * seconds)
	}
	
	// Must be called before discarding TrackStream
	// Idempotent - safe to call multiple times
	fun close() {
		if (isClosed) return
		isClosed = true
		// Wake up anything blocking on it still
		progressSignal.tryEmit(Unit)
		scope.cancel()
	}
	
	fun read(data: ByteArray, offset: Long, size: Int): Int {
		if (isClosed || offset >= entry.sizeBytes) return 0
		
		// Check if the required chunks are available to fulfill request.
		// If not, block until we have something or until timeout
		// The large timeout here is helpful if we jump to a posiiton in middle
		// and the sequential download from start needs to catch up. it has 5 seconds!
		blockUntilCanReadWithTimeout(offset, size, 5000)
		
		if (isClosed) return 0
		
		val read = channel.readAt(data, offset, 0, size)
		return if (read < 0) 0 else read
	}
	
	private suspend fun waitForData(offset: Long, size: Int) {
		val startChunk = (offset / MediaCacheRecord.CHUNK_SIZE).toInt()
		val endChunk = ((offset + size - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()
		
		if (entry.chunks().nextClearBit(startChunk) > endChunk) return

        Log.d(
            TAG,
            "[$trackId] Waiting for chunks $startChunk-$endChunk (offset=$offset) isClosed=$isClosed"
        )
        progressSignal.first {
            isClosed || entry.chunks().nextClearBit(startChunk) > endChunk
        }
	}
	
	private fun saveState() {
		if (entry.sizeBytes == 0L) {
			Log.w(TAG, "[$trackId] Skipping state save - size is 0")
			return
		}
		
		val totalChunks = entry.totalChunks()
		val cachedChunks = entry.chunks().cardinality()
		
		val newState =
			if (cachedChunks >= totalChunks) ContentState.COMPLETE else ContentState.PARTIAL
		entry.updateState(newState)
		db.updateState(trackId, newState, entry.chunks())
		
		if (newState == ContentState.COMPLETE) {
			Log.i(TAG, "[$trackId] Download complete ($cachedChunks/$totalChunks chunks)")
		} else {
			Log.d(TAG, "[$trackId] Saved state: $cachedChunks/$totalChunks chunks")
		}
	}
	
	companion object {
		const val TAG = "TrackStream"
	}
}

class TrackPlaybackManager(
	private val tracksDir: File,
	private val thumbDir: File,
	private val db: MediaDatabaseHelper,
	private val dbPath: File,
	private var maxCacheSizeMB: Long = 2048
) {
	fun setMaxCacheSizeMB(sizeMB: Long) {
		maxCacheSizeMB = sizeMB
		Log.d(TAG, "Max cache size updated to $maxCacheSizeMB MB")
	}
	
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//    private val activeTrackStreams = ConcurrentHashMap<UUID, TrackStream>()
	
	private var evictionJob: Job? = null
	
	/**
	 * Public method to trigger cache eviction.
	 * Loops until cache is under the limit (or 90% of it).
	 */
	fun performEviction(onComplete: (Int) -> Unit) {
		if (evictionJob?.isActive == true) return
		
		evictionJob = scope.launch {
			var evictedCount = 0
			try {
				val limitBytes = maxCacheSizeMB * 1024 * 1024
				val breakdown = getCacheSizeBreakdown()
				var currentSize = breakdown.totalSize
				
				if (currentSize <= limitBytes) {
					onComplete(0)
					return@launch
				}
				
				Log.i(
					TAG, "Manual eviction started. Current size: $currentSize, Limit: $limitBytes"
				)
				
				// Get the oldest tracks (excluding favorites)
				val oldestTracks = db.getOldestAccessedTracks(excludeFavourites = true)
				
				for (record in oldestTracks) {
					// Don't evict currently open files or active downloads
					// TODO: do not remove files with active handles!
					
					val file = record.getFile(tracksDir)
					val fileSize = if (record.state() == ContentState.COMPLETE) {
						file.length()
					} else {
						record.chunks().cardinality() * MediaCacheRecord.CHUNK_SIZE
					}
					
					if (file.exists()) {
						if (file.delete()) {
							evictedCount++
							currentSize -= fileSize
							Log.d(
								TAG, "Evicted track ${record.id} (saved ${fileSize / 1024} KB)"
							)
						}
					}
					db.deleteTrackRecord(record.id)
					
					// Evict until we are at 90% of limit
					if (currentSize <= limitBytes * 0.9) {
						break
					}
				}
				
				Log.i(
					TAG,
					"Manual eviction finished. Evicted $evictedCount tracks. New size: $currentSize"
				)
			} catch (e: Exception) {
				Log.e(TAG, "Error during manual cache eviction", e)
			} finally {
				withContext(Dispatchers.Main) {
					onComplete(evictedCount)
				}
			}
		}
	}
	
	fun openTrackForStreaming(
		trackId: UUID, sizeBytes: Long, durationMs: Long, jellyfinClient: JellyfinClientManager
	): TrackStream {
//        activeTrackStreams[trackId]?.let {
//            Log.d(TAG, "[$trackId] Reusing existing stream")
//            return it
//        }
		
		val channel = FileChannel.open(
			File(tracksDir, "$trackId.cache").toPath(),
			StandardOpenOption.READ,
			StandardOpenOption.WRITE,
			StandardOpenOption.CREATE,
			StandardOpenOption.SPARSE
		).also { channel ->
			if (sizeBytes > 0) {
				val buffer = ByteBuffer.wrap(byteArrayOf(0))
				channel.write(buffer, sizeBytes - 1)
			}
		}
		
		db.markOpenedForStreaming(trackId, sizeBytes, durationMs)
		val entry = db.getMediaCacheRecord(trackId)
			?: throw IOException("could not find details for the track")
		val stream = TrackStream(trackId, entry, channel, db, jellyfinClient)
//        activeTrackStreams[trackId] = stream
		
		Log.i(TAG, "[$trackId] Stream opened (${entry.chunks().cardinality()} chunks cached)")
		
		return stream
	}
	
	fun releaseTrackStream(trackId: UUID) {
//        activeTrackStreams.remove(trackId)
	}
	
	fun getCachedThumbnail(itemId: UUID): File? {
		val thumbFile = File(thumbDir, "$itemId.jpg")
		return thumbFile.takeIf { it.exists() }
	}
	
	suspend fun downloadThumbnail(
		itemId: UUID, jellyfinClient: JellyfinClientManager, sizeHint: Point?
	): File? = withContext(Dispatchers.IO) {
		val thumbFile = File(thumbDir, "$itemId.jpg")
		
		if (thumbFile.exists()) {
			return@withContext thumbFile
		}
		
		val success = jellyfinClient.downloadAlbumArt(itemId, thumbFile, sizeHint)
		if (success) thumbFile else null
	}
	
	fun deletePartialFiles(excludeFavourites: Boolean = true) {
		val partialTracks = db.getAllTracks(ContentState.PARTIAL, excludeFavourites)
		partialTracks.forEach { trackId ->
			val file = File(tracksDir, "$trackId.cache")
			if (file.exists()) {
				file.delete()
				Log.d(TAG, "Deleted partial file: $trackId")
			}
		}
		db.deleteAllTracks(ContentState.PARTIAL, excludeFavourites)
		Log.i(TAG, "Deleted ${partialTracks.size} partial files")
	}
	
	fun deleteAllAlbumArts(): Int {
		val thumbFiles = thumbDir.listFiles() ?: return 0
		var count = 0
		thumbFiles.forEach { file ->
			if (file.delete()) {
				count++
			}
		}
		Log.i(TAG, "Deleted $count album art files")
		return count
	}
	
	fun deleteAllTracks(excludeFavourites: Boolean = true) {
		val allTracks = db.getAllTracks(null, excludeFavourites)
		allTracks.forEach { trackId ->
			val file = File(tracksDir, "$trackId.cache")
			if (file.exists()) {
				file.delete()
				Log.d(TAG, "Deleted track file: $file")
			}
		}
		db.deleteAllTracks(null, excludeFavourites)
		
		Log.i(TAG, "Deleted ${allTracks.size} track files")
	}
	
	fun deleteFavouriteTracks() {
		val favouriteTracks = db.getFavouriteTracks()
		favouriteTracks.forEach { trackId ->
			val file = File(tracksDir, "$trackId.cache")
			if (file.exists()) {
				file.delete()
				Log.d(TAG, "Deleted favourite track file: $trackId")
			}
		}
		db.deleteFavouriteTracks()
		Log.i(TAG, "Deleted ${favouriteTracks.size} favourite track files")
	}
	
	data class CacheSizeBreakdown(
		val partialFilesSize: Long,
		val partialSongsNum: Int,
		val favouriteFilesSize: Long,
		val favouriteSongsNum: Int,
		val completeFilesSize: Long,
		val completeSongsNum: Int,
		val albumArtsSize: Long,
		val databaseSize: Long
	) {
		val totalSize: Long
			get() = partialFilesSize + completeFilesSize + albumArtsSize + databaseSize
	}
	
	fun getCacheSizeBreakdown(): CacheSizeBreakdown {
		var partialSize = 0L
		var partialNum = 0
		var completeSize = 0L
		var completeNum = 0
		var favouritesSize = 0L
		var favouriteNum = 0
		
		val partialTracks = db.getAllTracks(ContentState.PARTIAL, false).toSet()
		val favouriteTracks = db.getFavouriteTracks().toSet()
		
		tracksDir.listFiles()?.forEach { file ->
			val trackId = try {
				UUID.fromString(file.nameWithoutExtension)
			} catch (_: Exception) {
				return@forEach
			}
			if (trackId == null) {
				return@forEach
			}
			
			if (partialTracks.contains(trackId)) {
				partialNum += 1
				// For partial files: calculate actual usage from chunks bitset
				val record = db.getMediaCacheRecord(trackId)
				val size = if (record != null) {
					record.chunks().cardinality() * MediaCacheRecord.CHUNK_SIZE
				} else {
					0L
				}
				if (favouriteTracks.contains(trackId)) {
					favouriteNum += 1
					favouritesSize += size
				}
				
				partialSize += size
			} else {
				completeNum += 1
				// For complete files: file.length() is accurate
				val l = file.length()
				completeSize += l
				if (favouriteTracks.contains(trackId)) {
					favouriteNum += 1
					favouritesSize += l
				}
			}
		}
		
		var albumArtsSize = 0L
		thumbDir.listFiles()?.forEach { file ->
			albumArtsSize += file.length()
		}
		
		val dbSize = if (dbPath.exists()) dbPath.length() else 0L
		
		return CacheSizeBreakdown(
			partialFilesSize = partialSize,
			partialSongsNum = partialNum,
			favouriteFilesSize = favouritesSize,
			favouriteSongsNum = favouriteNum,
			completeFilesSize = completeSize,
			completeSongsNum = completeNum,
			albumArtsSize = albumArtsSize,
			databaseSize = dbSize
		)
	}
	
	
	fun shutdown() {
		scope.cancel()
	}
	
	
	companion object {
		private const val TAG = "TrackPlaybackManager"
	}
}

object TrackPlaybackManagerSingleton {
	private var INSTANCE: TrackPlaybackManager? = null
	
	fun getInstance(context: Context): TrackPlaybackManager {
		return INSTANCE ?: synchronized(this) {
			INSTANCE ?: run {
				val appContext = context.applicationContext
				val tracksDir = File(appContext.filesDir, "jellyfin_tracks").apply { mkdirs() }
				val thumbDir = File(appContext.cacheDir, "jellyfin_thumbs").apply { mkdirs() }
				val db = DatabaseManager.getInstance(appContext)
				val dbPath = appContext.getDatabasePath("jellyfin_media_cache.db")
				val clientManager = JellyfinClientManager(appContext)
				
				TrackPlaybackManager(
					tracksDir = tracksDir,
					thumbDir = thumbDir,
					db = db,
					dbPath = dbPath,
					maxCacheSizeMB = clientManager.getMaxCacheSize()
				).also { INSTANCE = it }
			}
		}
	}
}