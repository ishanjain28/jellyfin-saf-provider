package me.ishan.jellyfin_saf

import android.content.Context
import android.graphics.Point
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

class SharedTrackState(val entry: MediaCacheRecord) {
	@Volatile
	var waitLock = Object()
	
	@Volatile
	private var inFlightChunks = BitSet()
	
	@Volatile
	var activeReaders: Int = 0
	
	fun nextClearBitDisk(chunk: Int): Int = synchronized(entry.chunks()) {
		entry.chunks().nextClearBit(chunk)
	}
	
	fun isChunkInFlight(chunk: Int): Boolean = synchronized(inFlightChunks) {
		inFlightChunks.get(chunk)
	}
	
	fun claimChunks(start: Int, end: Int) = synchronized(inFlightChunks) {
		inFlightChunks.set(start, end)
	}
	
	fun releaseChunks(start: Int, end: Int) = synchronized(inFlightChunks) {
		inFlightChunks.clear(start, end)
	}
	
	fun nextClearBitAny(startChunk: Int): Int {
		var current = startChunk
		while (true) {
			val nextDiskGap = synchronized(entry.chunks()) {
				entry.chunks().nextClearBit(current)
			}
			val inFlight = synchronized(inFlightChunks) {
				inFlightChunks.get(nextDiskGap)
			}
			if (!inFlight) return nextDiskGap
			current = nextDiskGap + 1
		}
	}
	
	fun nextSetBitAny(startChunk: Int): Int {
		val nextDiskFilled = synchronized(entry.chunks()) {
			entry.chunks().nextSetBit(startChunk)
		}
		val nextInFlight = synchronized(inFlightChunks) {
			inFlightChunks.nextSetBit(startChunk)
		}
		
		if (nextDiskFilled == -1) return nextInFlight
		if (nextInFlight == -1) return nextDiskFilled
		return minOf(nextDiskFilled, nextInFlight)
	}
}


class TrackStream(
	val trackId: UUID,
	val sharedState: SharedTrackState,
	private val channel: FileChannel,
	private val db: MediaDatabaseHelper,
	private val jellyfinClient: JellyfinClientManager,
	private val onCloseCallback: () -> Unit
) {
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	
	private val waitLock get() = sharedState.waitLock
	
	private val entry get() = sharedState.entry
	
	private data class ActiveDownload(
		val job: Job, val startChunk: Int, val endChunkInclusive: Int
	)
	
	@Volatile
	private var activeDownload: ActiveDownload? = null
	
	@Volatile
	private var isClosed: Boolean = false
	
	init {
		// Wait until coroutines are shut down to close the file
		scope.coroutineContext[Job]?.invokeOnCompletion { channel.close() }
	}
	
	fun bitrateInBps(): Long {
		if (entry.durationMs <= 0L) return 320_000 // Default to 320Kbps
		return (entry.sizeBytes * 8000) / entry.durationMs
	}
	
	fun bytesInSeconds(seconds: Int): Long {
		return (bitrateInBps() / 8 * seconds)
	}
	
	fun secondsInBytes(bytes: Long): Int {
		val bitrate = bitrateInBps()
		if (bitrate <= 0L) return Int.MAX_VALUE
		return (bytes * 8 / bitrate).toInt()
	}
	
	// Must be called before discarding TrackStream
	// Idempotent - safe to call multiple times
	fun close() {
		if (isClosed) return
		isClosed = true
		Log.d(TAG, "[$trackId] Closing track stream ${this.hashCode()}. Waking up readers.")
		activeDownload?.job?.cancel()
		// Last reader can save state to disk
		synchronized(waitLock) {
			if (sharedState.activeReaders == 1) {
				try {
					db.updateState(trackId, entry.chunks())
				} catch (e: Exception) {
					Log.w(TAG, "[$trackId] Failed to save state: ${e.message}")
				}
			}
			
			// Notify waitLock for the synchronous onRead() thread
			waitLock.notifyAll()
		}
		
		onCloseCallback()
		// Cancel all download jobs in our scope
		scope.cancel()
	}
	
	
	fun read(data: ByteArray, offset: Long, size: Int): Int {
		if (isClosed || offset >= entry.sizeBytes) return 0
		
		val startChunk = (offset / MediaCacheRecord.CHUNK_SIZE).toInt()
		val endChunk = ((offset + size - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()
		val totalChunks =
			((entry.sizeBytes + MediaCacheRecord.CHUNK_SIZE - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()
		
		// When to trigger downloads ?
		// 1. If we don't have the data to serve the current request
		// 2. If the buffer is running low, < LOW_BUFFER_SECONDS seconds left
		val nextMissingDisk = sharedState.nextClearBitDisk(startChunk)
		val nextMissingAny = sharedState.nextClearBitAny(startChunk)
		val bufferedSecondsAny =
			secondsInBytes((nextMissingAny - startChunk).toLong() * MediaCacheRecord.CHUNK_SIZE)
		val prefetchChunks =
			(bytesInSeconds(PREFETCH_SECONDS) / MediaCacheRecord.CHUNK_SIZE).toInt()
		val targetEndChunk = minOf(totalChunks - 1, nextMissingAny + prefetchChunks - 1)
		
		// Trigger a download if,
		// 1. The chunk to serve this request is not on disk, nor in-flight
		// 2. The buffer on disk or in flight is running lower than NUM_BUFFER_SECONDS
		//    and the new request will request additional data than what is in flight
		if (nextMissingAny <= endChunk || (bufferedSecondsAny <= LOW_BUFFER_SECONDS && nextMissingAny <= targetEndChunk)) {
			val currentDownload = activeDownload
			
			currentDownload?.job?.cancel()
			if (currentDownload != null) {
				sharedState.releaseChunks(
					currentDownload.startChunk, currentDownload.endChunkInclusive + 1
				)
				activeDownload = null
			}
			Log.d(
				TAG,
				"Triggering Download: start=$nextMissingAny end=$targetEndChunk buffer=$bufferedSecondsAny requested_start=$startChunk requested_end=$endChunk"
			)
			downloadRange(nextMissingAny, targetEndChunk)
		}
		
		// Block the handlerThread looper only if we need the data that's missing
		if (sharedState.nextClearBitDisk(startChunk) <= endChunk) synchronized(waitLock) {
			val deadline = System.currentTimeMillis() + 15_000
			while (!isClosed && sharedState.nextClearBitDisk(startChunk) <= endChunk) {
				val remaining = deadline - System.currentTimeMillis()
				if (remaining <= 0) break
				try {
					waitLock.wait(remaining)
				} catch (e: InterruptedException) {
					Log.d(
						TAG,
						"[$trackId] Interrupt read() at $offset, size=$size range=$startChunk-$endChunk: ${e.message}"
					)
				}
			}
		}
		
		// Final check: did we get the data or was it closed?
		if (isClosed || sharedState.nextClearBitDisk(startChunk) <= endChunk) return 0
		
		val read = channel.readAt(data, offset, 0, size)
		return if (read < 0) 0 else read
	}
	
	/**
	 * Downloads a specific range of chunks. Range Inclusive.
	 */
	private fun downloadRange(alignedStartChunk: Int, alignedEndChunk: Int) {
		val alignedStartOffset = alignedStartChunk.toLong() * MediaCacheRecord.CHUNK_SIZE
		val alignedEndOffset =
			minOf(entry.sizeBytes, (alignedEndChunk.toLong() + 1) * MediaCacheRecord.CHUNK_SIZE)
		
		// Skip if we have this whole range
		if (sharedState.nextClearBitDisk(alignedStartChunk) > alignedEndChunk) return
		// Skip if the whole range is in flight already!
//		if (sharedState.isChunkInFlight(alignedStartChunk) return
		
		Log.i(
			TAG,
			"[$trackId] obj=${this.hashCode()} Download: asChunks=$alignedStartChunk aeChunk=$alignedEndChunk asOffset=$alignedStartOffset aeOffset=$alignedEndOffset"
		)
		
		// Claim the chunks synchronously before yielding to the coroutine
		sharedState.claimChunks(alignedStartChunk, alignedEndChunk + 1)
		
		val job = scope.launch {
			val currentJob = coroutineContext[Job]
			try {
				jellyfinClient.downloadTrack(
					trackId = trackId,
					outputFile = channel,
					byteOffset = alignedStartOffset,
					byteLength = alignedEndOffset - alignedStartOffset,
					onProgress = { absolutePosition ->
						val endChunk = if (absolutePosition >= entry.sizeBytes) {
							((entry.sizeBytes + MediaCacheRecord.CHUNK_SIZE - 1) / MediaCacheRecord.CHUNK_SIZE).toInt() - 1
						} else {
							val bytesDownloaded = absolutePosition - alignedStartOffset
							val completeChunks =
								(bytesDownloaded / MediaCacheRecord.CHUNK_SIZE).toInt()
							alignedStartChunk + completeChunks - 1
						}
						if (endChunk >= alignedStartChunk) {
							synchronized(entry.chunks()) {
								entry.chunks().set(alignedStartChunk, endChunk + 1)
							}
							sharedState.releaseChunks(alignedStartChunk, endChunk + 1)
							synchronized(waitLock) {
								waitLock.notifyAll()
							}
						}
					})
			} catch (e: Exception) {
				Log.w(
					TAG,
					"[$trackId] $this downloadRange $alignedStartOffset-$alignedEndOffset interrupted: ${e.message}"
				)
			} finally {
				// Only release if we weren't superseded by a newer job cancelling us
				if (activeDownload?.job == currentJob) {
					sharedState.releaseChunks(alignedStartChunk, alignedEndChunk + 1)
					activeDownload = null
				}
				// Wake up anyone waiting for the final state
				synchronized(waitLock) {
					waitLock.notifyAll()
				}
				
			}
		}
		
		activeDownload = ActiveDownload(job, alignedStartChunk, alignedEndChunk)
	}
	
	companion object {
		const val TAG = "TrackStream"
		
		const val PREFETCH_SECONDS: Int = 45
		
		const val LOW_BUFFER_SECONDS: Int = 10
	}
}

class TrackPlaybackManager(
	private val tracksDir: File,
	private val thumbDir: File,
	private val db: MediaDatabaseHelper,
	private val dbPath: File,
	private var maxCacheSizeMB: Long = 2048
) {
	private val activeTracks = ConcurrentHashMap<UUID, SharedTrackState>()
	
	fun setMaxCacheSizeMB(sizeMB: Long) {
		maxCacheSizeMB = sizeMB
		Log.d(TAG, "Max cache size updated to $maxCacheSizeMB MB")
	}
	
	private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	private var evictionJob: Job? = null
	
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
				val oldestTracks = db.getOldestAccessedTracks(excludeFavourites = true)
				for (record in oldestTracks) {
					val file = record.getFile(tracksDir)
					val fileSize =
						if (record.state() == ContentState.COMPLETE) file.length() else record
							.chunks().cardinality() * MediaCacheRecord.CHUNK_SIZE
					if (file.exists() && file.delete()) {
						evictedCount++
						currentSize -= fileSize
					}
					db.deleteTrackRecord(record.id)
					if (currentSize <= limitBytes * 0.9) break
				}
			} catch (e: Exception) {
				Log.e(TAG, "Cache eviction failed", e)
			} finally {
				withContext(Dispatchers.Main) { onComplete(evictedCount) }
			}
		}
	}
	
	fun openTrackForStreaming(
		trackId: UUID, sizeBytes: Long, durationMs: Long, jellyfinClient: JellyfinClientManager
	): TrackStream {
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
		val sharedState = activeTracks.compute(trackId) { _, existingState ->
			if (existingState != null) {
				existingState.activeReaders += 1
				existingState
			} else {
				val entry = db.markOpenedForStreaming(trackId, sizeBytes, durationMs)
				SharedTrackState(entry).apply { activeReaders = 1 }
			}
		}!!
		
		val stream = TrackStream(trackId, sharedState, channel, db, jellyfinClient) {
			activeTracks.computeIfPresent(trackId) { _, state ->
				state.activeReaders -= 1
				if (state.activeReaders <= 0) null else state
			}
		}
		Log.i(TAG, "[$trackId] Stream opened")
		return stream
	}
	
	fun getCachedTrack(trackId: UUID): File? {
		val entry = db.getMediaCacheRecord(trackId)
		if (entry?.state() != ContentState.COMPLETE) {
			return null
		}
		val path = File(tracksDir, "$trackId.cache").toPath()
		if (!path.exists()) {
			return null
		}
		return path.toFile()
	}
	
	fun getCachedThumbnail(itemId: UUID): File? =
		File(thumbDir, "$itemId.jpg").takeIf { it.exists() }
	
	suspend fun downloadThumbnail(
		itemId: UUID, client: JellyfinClientManager, size: Point?
	): File? = withContext(Dispatchers.IO) {
		val file = File(thumbDir, "$itemId.jpg")
		if (file.exists()) return@withContext file
		if (client.downloadAlbumArt(itemId, file, size)) file else null
	}
	
	fun deletePartialFiles(excludeFavourites: Boolean = true) {
		val partialTracks = db.getAllTracks(ContentState.PARTIAL, excludeFavourites)
		partialTracks.forEach { File(tracksDir, "$it.cache").delete() }
		db.deleteAllTracks(ContentState.PARTIAL, excludeFavourites)
	}
	
	fun deleteAllAlbumArts(): Int {
		val files = thumbDir.listFiles() ?: return 0
		var count = 0
		files.forEach { if (it.delete()) count++ }
		return count
	}
	
	fun deleteAllTracks(excludeFavourites: Boolean = true) {
		val allTracks = db.getAllTracks(null, excludeFavourites)
		allTracks.forEach { File(tracksDir, "$it.cache").delete() }
		db.deleteAllTracks(null, excludeFavourites)
	}
	
	fun deleteFavouriteTracks() {
		db.getFavouriteTracks().forEach { File(tracksDir, "$it.cache").delete() }
		db.deleteFavouriteTracks()
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
		val totalSize: Long get() = partialFilesSize + completeFilesSize + albumArtsSize + databaseSize
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
			} ?: return@forEach
			if (partialTracks.contains(trackId)) {
				partialNum++
				val size = db.getMediaCacheRecord(trackId)?.chunks()?.cardinality()
					?.times(MediaCacheRecord.CHUNK_SIZE) ?: 0L
				if (favouriteTracks.contains(trackId)) {
					favouriteNum++; favouritesSize += size
				}
				partialSize += size
			} else {
				completeNum++
				val l = file.length()
				completeSize += l
				if (favouriteTracks.contains(trackId)) {
					favouriteNum++; favouritesSize += l
				}
			}
		}
		val dbSize = if (dbPath.exists()) dbPath.length() else 0L
		return CacheSizeBreakdown(
			partialSize,
			partialNum,
			favouritesSize,
			favouriteNum,
			completeSize,
			completeNum,
			(thumbDir.listFiles()?.sumOf { it.length() } ?: 0L),
			dbSize)
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
	fun getInstance(context: Context): TrackPlaybackManager = INSTANCE ?: synchronized(this) {
		INSTANCE ?: run {
			val appContext = context.applicationContext
			val client = JellyfinClientManager(appContext)
			TrackPlaybackManager(
				File(
				appContext.filesDir, "jellyfin_tracks"
			).apply { mkdirs() },
				File(appContext.cacheDir, "jellyfin_thumbs").apply { mkdirs() },
				DatabaseManager.getInstance(appContext),
				appContext.getDatabasePath("jellyfin_media_cache.db"),
				client.getMaxCacheSize()
			).also { INSTANCE = it }
		}
	}
}