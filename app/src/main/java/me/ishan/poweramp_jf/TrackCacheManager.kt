package me.ishan.poweramp_jf;

import android.content.Context
import android.graphics.Point
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okio.IOException
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
    private val onClosed: (() -> Unit)
) {
    private val lock = ReentrantReadWriteLock()
    private var refCount = 0
    private var channel: AsynchronousFileChannel? = null


    companion object {
        private const val TAG = "RefCountedAsyncFileChannel"
    }

    fun acquire(): AsynchronousFileChannel {

        return lock.write {
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
                channel?.close()
                channel = null
                onClosed.invoke()
            }
        }
    }
}

class TrackCacheManager(
    context: Context, maxCacheSizeMB: Long = 2048
) {
    private val cacheDir = File(context.cacheDir, "jellyfin_tracks").apply { mkdirs() }
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

    private fun getFileHandle(trackId: UUID, size: Long): RefCountedAsyncFileChannel {
        return fileChannels.computeIfAbsent(trackId) {
            RefCountedAsyncFileChannel(
                File(cacheDir, "$trackId.cache"), "rw", size, onClosed = {
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
            return entry.getFile(cacheDir).takeIf { it.exists() }
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
                                TAG, "jellyfin track=${entry.id} marked COMPLETE!"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(
                        TAG, "Chunk $size at $offset download ended for $trackId with ${e.message}"
                    )
                } finally {
                    activeChunkDownloads.remove(key)?.cancel()
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

    /**
     * Get cached thumbnail
     */
    fun getCachedThumbnail(itemId: UUID): File? {
        val thumbFile = File(thumbDir, "$itemId.jpg")
        return thumbFile.takeIf { it.exists() }
    }

    /**
     * Download and cache thumbnail
     */
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

    fun shutdown() {
        scope.cancel()
    }

    companion object {
        private const val TAG = "TrackCacheManager"
    }
}