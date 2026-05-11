package me.ishan.poweramp_jf;

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.LongArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousFileChannel
import java.nio.file.StandardOpenOption
import java.util.BitSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write


@Serializable
data class CacheEntry(
    @Serializable(with = UUIDSerializer::class) val trackId: UUID,

    var sizeBytes: Long,
    var lastAccessTime: Long,
    var state: CacheState,
    var downloadedBytes: Long = 0,

    @Serializable(with = BitSetSerializer::class) var downloadedBitSet: BitSet = BitSet(512)
) {
    fun getFile(cacheDir: File): File {
        return File(cacheDir, "$trackId.cache")
    }

    companion object {
        const val CHUNK_SIZE: Long = 1024 * 1024 // 1MB / bit in downloadedBitSet

    }
}

@Serializable
enum class CacheState {
    COMPLETE, DOWNLOADING, PARTIAL, FAILED
}


object UUIDSerializer : KSerializer<UUID> {
    // TODO: optimize
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("UUID", PrimitiveKind.STRING) // Or use a LongArray descriptor

    override fun serialize(encoder: Encoder, value: UUID) {
        // Convert BitSet to a serializable format (e.g., LongArray)
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): UUID {
        val data = decoder.decodeString()
        return UUID.fromString(data)
    }
}


object BitSetSerializer : KSerializer<BitSet> {
    // TODO: optimize
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("BitSet", PrimitiveKind.STRING) // Or use a LongArray descriptor

    override fun serialize(encoder: Encoder, value: BitSet) {
        // Convert BitSet to a serializable format (e.g., LongArray)
        encoder.encodeSerializableValue(LongArraySerializer(), value.toLongArray())
    }

    override fun deserialize(decoder: Decoder): BitSet {
        val array = decoder.decodeSerializableValue(LongArraySerializer())
        return BitSet.valueOf(array)
    }
}


class RefCountedAsyncFileChannel(
    private val file: File, private val mode: String = "rw", private val size: Long
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
            }
        }
    }
}


class TrackCacheManager(
    context: Context, maxCacheSizeMB: Long = 2048
) {
    private val cacheDir = File(context.cacheDir, "jellyfin_tracks").apply { mkdirs() }
    private val thumbDir = File(context.cacheDir, "jellyfin_thumbs").apply { mkdirs() }
    private val maxCacheSizeBytes = maxCacheSizeMB * 1024 * 1024

    private val lock = ReentrantReadWriteLock()

    // Access-order LinkedHashMap for LRU
    private val lruCache = object : LinkedHashMap<UUID, CacheEntry>(
        100, 0.75f, true // accessOrder = true for LRU
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, CacheEntry>?): Boolean {
            return false // Manual eviction based on size
        }
    }

    @Volatile
    private var currentCacheSizeBytes = 0L

    private val activeChunkDownloads = ConcurrentHashMap<Pair<UUID, Int>, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val fileChannels = ConcurrentHashMap<UUID, RefCountedAsyncFileChannel>()

    private fun getFileHandle(trackId: UUID, size: Long): RefCountedAsyncFileChannel {
        return fileChannels.computeIfAbsent(trackId) {
            RefCountedAsyncFileChannel(File(cacheDir, "$trackId.cache"), "rw", size)
        }
    }

    init {
        loadCacheState()
    }

    /**
     * Get cached file if available
     */
    fun getCachedFile(trackId: UUID): File? = lock.read {
        val entry = lruCache[trackId] ?: return null

        if (entry.state == CacheState.COMPLETE) {
            entry.lastAccessTime = System.currentTimeMillis()
            return entry.getFile(cacheDir).takeIf { it.exists() }
        }

        return null
    }

    fun openFileForStreaming(trackId: UUID, metadata: TrackMetadata): RefCountedAsyncFileChannel? {
        try {
            return getFileHandle(trackId, metadata.sizeBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create sparse cache file for $trackId", e)
            return null
        } finally {
            lock.write {
                lruCache[trackId] ?: CacheEntry(
                    trackId = trackId,
                    sizeBytes = metadata.sizeBytes,
                    lastAccessTime = System.currentTimeMillis(),
                    state = CacheState.PARTIAL,
                ).also { lruCache[trackId] = it }
            }
        }
    }

    /**
     * Check if track is cached
     */
    fun isCached(trackId: UUID): Boolean = lock.read {
        lruCache[trackId]?.state == CacheState.COMPLETE
    }

    /**
     * Check if track is currently downloading
     */
    fun isDownloading(trackId: UUID): Boolean = lock.read {
        return lruCache[trackId]?.state == CacheState.DOWNLOADING
    }

    private val chunkProgress = ConcurrentHashMap<Pair<UUID, Int>, Long>()

    suspend fun onReadTrack(
        trackId: UUID,
        metadata: TrackMetadata,
        jellyfinClient: JellyfinClientManager,
        offset: Long,
        size: Int,
    ) {
        val entry = lock.read { lruCache[trackId] } ?: return
        val startChunk = (offset / CacheEntry.CHUNK_SIZE).toInt()
        val endChunk = ((offset + size - 1) / CacheEntry.CHUNK_SIZE).toInt()
        val totalChunks =
            ((metadata.sizeBytes + CacheEntry.CHUNK_SIZE - 1) / CacheEntry.CHUNK_SIZE).toInt()

        // Downloading required chunks and prefetch the next one
        for (chunk in startChunk..minOf(endChunk + 1, totalChunks - 1)) {
            val key = trackId to chunk
            val isDownloaded = lock.read { entry.downloadedBitSet.get(chunk) }

            if (isDownloaded || activeChunkDownloads.containsKey(key)) {
                continue
            }

            activeChunkDownloads.getOrPut(key) {
                scope.launch {
                    try {
                        val chunkOffset = chunk.toLong() * CacheEntry.CHUNK_SIZE
                        val chunkLength =
                            minOf(CacheEntry.CHUNK_SIZE, metadata.sizeBytes - chunkOffset)

                        Log.d(
                            TAG, "Fetching chunk $chunk for $trackId ($chunkOffset, $chunkLength)"
                        )

                        val success = jellyfinClient.downloadTrack(
                            trackId = trackId,
                            outputFile = getFileHandle(trackId, metadata.sizeBytes),
                            offset = chunkOffset,
                            length = chunkLength,
                            onProgress = { absoluteProgress ->
                                chunkProgress[key] = absoluteProgress
                            })

                        if (success) {
                            lock.write {
                                entry.downloadedBitSet.set(chunk)
                                chunkProgress.remove(key)

                                if (entry.downloadedBitSet.cardinality() >= totalChunks) {
                                    entry.state = CacheState.COMPLETE
                                    Log.d(
                                        TAG, "jellyfin track=${entry.trackId} marked COMPLETE!"
                                    )
                                    saveCacheState()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Chunk download error for $trackId", e)
                    } finally {
                        activeChunkDownloads.remove(key)
                        chunkProgress.remove(key)
                    }
                }
            }
        }

        // wait until requested amount of data is available
        val requiredEnd = offset + size
        withTimeoutOrNull(20_000) {
            while (true) {
                val ready = (startChunk..endChunk).all { chunk ->
                    lock.read {
                        if (entry.downloadedBitSet.get(chunk)) return@read true
                        val progress = chunkProgress[trackId to chunk] ?: 0L
                        val chunkEnd = (chunk + 1) * CacheEntry.CHUNK_SIZE
                        val neededInThisChunk = minOf(chunkEnd, requiredEnd)
                        progress >= neededInThisChunk
                    }
                }

                if (ready) {
                    Log.d(
                        TAG,
                        "jellyfin data ready: offset=$offset, size=$size, requiredEnd=$requiredEnd"
                    )
                    return@withTimeoutOrNull true
                }
                yield()
            }
        } ?: throw IOException("Timeout waiting for data at offset $offset")
    }


    /**
     * Evict least recently used tracks
     */
    private fun evictIfNeeded(requiredBytes: Long) {
        if (currentCacheSizeBytes + requiredBytes <= maxCacheSizeBytes) {
            return
        }

        val toFree = (currentCacheSizeBytes + requiredBytes) - maxCacheSizeBytes
        var freed = 0L

        val iterator = lruCache.entries.iterator()
        while (iterator.hasNext() && freed < toFree) {
            val (trackId, entry) = iterator.next()

            // Don't evict currently downloading
            if (entry.state == CacheState.DOWNLOADING) {
                continue
            }

            val file = entry.getFile(cacheDir)
            if (file.exists()) {
                val size = file.length()
                if (file.delete()) {
                    freed += size
                    currentCacheSizeBytes -= size
                }
            }

            iterator.remove()
            Log.d(TAG, "Evicted: $trackId (${freed / 1024 / 1024}MB freed)")
        }

        if (freed > 0) {
            saveCacheState()
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
        itemId: UUID, jellyfinClient: JellyfinClientManager
    ): File? = withContext(Dispatchers.IO) {
        val thumbFile = File(thumbDir, "$itemId.jpg")

        if (thumbFile.exists()) {
            return@withContext thumbFile
        }

        val success = jellyfinClient.downloadAlbumArt(itemId, thumbFile)
        if (success) thumbFile else null
    }

    /**
     * Persist cache state
     */
    private fun saveCacheState() {
        try {
            val state = lock.read {
                lruCache.values.toList()
            }

            val json = Json.encodeToString(state)
            val file = File(cacheDir, "cache_state.json")
            if (!file.exists()) {
                file.createNewFile()
            }
            file.writeText(json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cache state", e)
        }
    }

    /**
     * Load cache state from disk
     */
    private fun loadCacheState() {
        try {
            val stateFile = File(cacheDir, "cache_state.json")
            if (!stateFile.exists()) return

            val json = stateFile.readText()
            val entries = Json.decodeFromString<List<CacheEntry>>(json)

            lock.write {
                entries.forEach { entry ->
                    val file = entry.getFile(cacheDir)
                    if (file.exists()) {
                        lruCache[entry.trackId] = entry
                        currentCacheSizeBytes += entry.sizeBytes
                    }
                }
            }

            Log.d(
                TAG,
                "Loaded ${lruCache.size} cached tracks " + "(${currentCacheSizeBytes / 1024 / 1024}MB)"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cache state", e)
        }
    }

    /**
     * Clear all cache
     */
    fun clearCache() = lock.write {
        lruCache.values.forEach { entry ->
            entry.getFile(cacheDir).delete()
        }
        lruCache.clear()
        currentCacheSizeBytes = 0
        saveCacheState()
    }

    /**
     * Get cache statistics
     */
    fun getStats(): CacheStats = lock.read {
        CacheStats(
            trackCount = lruCache.size,
            usedBytes = currentCacheSizeBytes,
            maxBytes = maxCacheSizeBytes
        )
    }

    fun shutdown() {
        saveCacheState()
        scope.cancel()
    }

    companion object {
        private const val TAG = "TrackCacheManager"
    }
}

data class CacheStats(
    val trackCount: Int, val usedBytes: Long, val maxBytes: Long
) {
    val usedMB: Long get() = usedBytes / 1024 / 1024
    val maxMB: Long get() = maxBytes / 1024 / 1024
    val percentUsed: Int get() = ((usedBytes * 100) / maxBytes).toInt()
}