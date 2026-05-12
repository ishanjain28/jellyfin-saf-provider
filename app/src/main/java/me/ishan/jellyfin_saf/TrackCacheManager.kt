package me.ishan.jellyfin_saf

import android.content.Context
import android.graphics.Point
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

class RefCountedAsyncFileChannel(
    private val file: File,
    private val mode: String = "rw",
    private val size: Long,
    private val scope: CoroutineScope,
    private val onClosed: (() -> Unit)
) {
    private val lock = ReentrantReadWriteLock()
    private var refCount = 0
    private var channel: AsynchronousFileChannel? = null
    private var closeJob: Job? = null


    companion object {
        private const val TAG = "RefCountedAsyncFileChannel"
    }

    fun acquire(): AsynchronousFileChannel {
        return lock.write {
            closeJob?.cancel()
            closeJob = null

            if (channel == null) {
                channel = AsynchronousFileChannel.open(
                    file.toPath(),
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.SPARSE
                )

                channel!!.truncate(size)
                if (size > 0) {
                    val buffer = ByteBuffer.wrap(byteArrayOf(0))
                    runBlocking { channel!!.writeSuspend(buffer, size - 1) }
                }
            }

            refCount++
            channel!!
        }
    }

    fun release() {
        lock.write {
            if (refCount > 0) {
                refCount--
            }

            if (refCount == 0 && channel != null) {
                // Debounce closing to handle rapid open/close cycles from Poweramp
                closeJob?.cancel()
                closeJob = scope.launch {
                    delay(2000) // 2-second grace period
                    lock.write {
                        if (refCount == 0 && channel != null) {
                            channel?.close()
                            channel = null
                            onClosed.invoke()
                        }
                    }
                }
            }
        }
    }
}

object TrackCacheManagerSingleton {
    private var INSTANCE: TrackCacheManager? = null

    fun getInstance(context: Context): TrackCacheManager {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: TrackCacheManager(
                context.applicationContext,
                maxCacheSizeMB = JellyfinClientManager(context).getMaxCacheSize()
            ).also { INSTANCE = it }
        }
    }
}

class TrackCacheManager(
    private val context: Context, private var maxCacheSizeMB: Long = 2048
) {
    fun setMaxCacheSizeMB(sizeMB: Long) {
        maxCacheSizeMB = sizeMB
        Log.d(TAG, "Max cache size updated to $maxCacheSizeMB MB")
    }

    private val tracksDir = File(context.filesDir, "jellyfin_tracks").apply { mkdirs() }
    private val thumbDir = File(context.cacheDir, "jellyfin_thumbs").apply { mkdirs() }
    private val db = DatabaseManager.getInstance(context)
    private val trackBitSets = ConcurrentHashMap<UUID, BitSet>()
    private val activeChunkDownloads = ConcurrentHashMap<Pair<UUID, Int>, Job>()
    private val fileChannels = ConcurrentHashMap<UUID, RefCountedAsyncFileChannel>()
    private val chunkProgress = ConcurrentHashMap<Pair<UUID, Int>, Long>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val progressSignal = MutableSharedFlow<Unit>(
        replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

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
                var breakdown = getCacheSizeBreakdown()
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
                    if (fileChannels.containsKey(record.id) || activeChunkDownloads.keys.any { it.first == record.id }) {
                        continue
                    }

                    val file = record.getFile(tracksDir)
                    val fileSize = if (record.state == ContentState.COMPLETE) {
                        file.length()
                    } else {
                        record.getChunks().cardinality() * MediaCacheRecord.CHUNK_SIZE
                    }

                    if (file.exists()) {
                        if (file.delete()) {
                            evictedCount++
                            currentSize -= fileSize
                            Log.d(TAG, "Evicted track ${record.id} (saved ${fileSize / 1024} KB)")
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


    private fun getFileHandle(trackId: UUID, size: Long): RefCountedAsyncFileChannel {
        return fileChannels.computeIfAbsent(trackId) {
            RefCountedAsyncFileChannel(
                File(tracksDir, "$trackId.cache"), "rw", size, scope, onClosed = {
                    activeChunkDownloads.filterKeys { it.first == trackId }.forEach { (key, job) ->
                        job.cancel()
                        activeChunkDownloads.remove(key)
                        chunkProgress.remove(key)
                    }
                    fileChannels.remove(trackId)
                    trackBitSets.remove(trackId)
                })
        }
    }

    fun getCachedFile(trackId: UUID): File? {
        val entry = db.getMediaCacheRecord(trackId) ?: return null

        if (entry.state == ContentState.COMPLETE) {
            db.updateLastAccessTime(trackId, System.currentTimeMillis())
            return entry.getFile(tracksDir).takeIf { it.exists() }
        }

        return null
    }

    fun openFileForStreaming(trackId: UUID, sizeBytes: Long): RefCountedAsyncFileChannel? {
        try {
            return getFileHandle(trackId, sizeBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sparse cache file for $trackId", e)
            return null
        } finally {
            db.markOpenedForStreaming(trackId)
        }
    }

    suspend fun onReadTrack(
        trackId: UUID,
        sizeBytes: Long,
        jellyfinClient: JellyfinClientManager,
        offset: Long,
        size: Int,
    ) {
        val entry = db.getMediaCacheRecord(trackId) ?: return
        val chunks = trackBitSets.getOrPut(trackId) { entry.getChunks() }

        val startChunk = (offset / MediaCacheRecord.CHUNK_SIZE).toInt()
        val endChunk = ((offset + size - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()
        val totalChunks =
            ((sizeBytes + MediaCacheRecord.CHUNK_SIZE - 1) / MediaCacheRecord.CHUNK_SIZE).toInt()

        // Downloading required chunks and prefetch the next one
        for (chunk in startChunk..minOf(endChunk + 1, totalChunks - 1)) {
            val key = trackId to chunk
            val isDownloaded = synchronized(chunks) { chunks.get(chunk) }

            if (isDownloaded || activeChunkDownloads.containsKey(key)) {
                continue
            }

            activeChunkDownloads[key] = scope.launch {
                try {
                    val chunkOffset = chunk.toLong() * MediaCacheRecord.CHUNK_SIZE
                    val chunkLength = minOf(MediaCacheRecord.CHUNK_SIZE, sizeBytes - chunkOffset)

                    val success = jellyfinClient.downloadTrack(
                        trackId = trackId,
                        outputFile = getFileHandle(trackId, sizeBytes),
                        byteOffset = chunkOffset,
                        byteLength = chunkLength,
                        onProgress = { absoluteProgress ->
                            chunkProgress[key] = absoluteProgress
                            progressSignal.tryEmit(Unit)
                        })

                    if (success) {
                        synchronized(chunks) { chunks.set(chunk) }
                        chunkProgress.remove(key)
                        progressSignal.tryEmit(Unit)

                        if (synchronized(chunks) { chunks.cardinality() } >= totalChunks) {
                            db.updateState(trackId, ContentState.COMPLETE)
                            Log.d(
                                TAG, "jellyfin track=${trackId} marked COMPLETE!"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(
                        TAG, "Chunk $size at $offset download ended for $trackId with ${e.message}"
                    )
                } finally {
                    activeChunkDownloads.remove(key)
                    chunkProgress.remove(key)
                    db.updateChunks(
                        trackId, synchronized(chunks) { chunks })
                    progressSignal.tryEmit(Unit)
                }
            }
        }

        // wait until requested amount of data is available
        val requiredEnd = offset + size
        val checkReady: () -> Boolean = {
            (startChunk..endChunk).all { chunk ->
                if (synchronized(chunks) { chunks.get(chunk) }) {
                    true
                } else {
                    val progress = chunkProgress[trackId to chunk] ?: 0L
                    val chunkEnd = (chunk + 1) * MediaCacheRecord.CHUNK_SIZE
                    val neededInThisChunk = minOf(chunkEnd, requiredEnd)

                    progress >= neededInThisChunk
                }
            }
        }

        if (checkReady()) return
        progressSignal.first {
            checkReady()
        }
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
                    record.getChunks().cardinality() * MediaCacheRecord.CHUNK_SIZE
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

        val dbFile = context.getDatabasePath("jellyfin_media_cache.db")
        val dbSize = if (dbFile.exists()) dbFile.length() else 0L

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
        private const val TAG = "TrackCacheManager"
    }
}