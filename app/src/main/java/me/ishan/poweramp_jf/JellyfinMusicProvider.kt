package me.ishan.poweramp_jf

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.UUID
import java.io.FileNotFoundException


// TODO: Add FLAG_SUPPORTS_METADATA
class JellyfinMusicProvider : DocumentsProvider() {

    private lateinit var jellyfinClient: JellyfinClientManager
    private lateinit var cacheManager: TrackCacheManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val TAG = "JellyfinMusicProvider"

        private const val ROOT_ID = "jellyfin_root"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,

            // Poweramp metadata
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.IS_MUSIC,

            // Poweramp flags
            "com.maxmpz.poweramp.provider.COLUMN_FLAGS"
        )
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            jellyfinClient = JellyfinClientManager(
                context = ctx,
            )

            cacheManager = TrackCacheManager(
                context = ctx, maxCacheSizeMB = jellyfinClient.getMaxCacheSize()
            )

            Log.i(TAG, "JellyfinMusicProvider initialized with server: ${jellyfinClient.getUrl()}")

            return true
        }
        return false
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "audio/*")
            add(
                DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
            )
            add(DocumentsContract.Root.COLUMN_TITLE, "Jellyfin Music")
            add(
                DocumentsContract.Root.COLUMN_SUMMARY, "${cacheManager.getStats().usedMB}MB cached"
            )
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        }

        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): MatrixCursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        if (documentId == ROOT_ID) {
            // Root folder
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_ID)
                add(
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.MIME_TYPE_DIR
                )
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "Jellyfin Music")
                add(
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.FLAG_DIR_PREFERS_GRID
                )
            }
        } else if (documentId.startsWith("album:")) {
            // Album folder
            val albumId = documentId.removePrefix("album:")
            runBlocking {
                try {
                    val tracks = jellyfinClient.getAlbumTracks(UUID.fromString(albumId))
                    if (tracks.isNotEmpty()) {
                        val album = tracks.first()
                        result.newRow().apply {
                            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                            add(
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.MIME_TYPE_DIR
                            )
                            add(
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                                album.album ?: "Unknown Album"
                            )
                            add(
                                DocumentsContract.Document.COLUMN_FLAGS,
                                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying album", e)
                }
            }
        } else {
            // Individual track
            runBlocking withContext@{
                try {
                    val metadata = jellyfinClient.getTrackMetadata(UUID.fromString(documentId))
                        ?: return@withContext result

                    result.newRow().apply {
                        add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                        add(DocumentsContract.Document.COLUMN_MIME_TYPE, metadata.mimeType)
                        add(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME, metadata.title
                        )
                        add(DocumentsContract.Document.COLUMN_SIZE, metadata.sizeBytes)
                        add(
                            DocumentsContract.Document.COLUMN_LAST_MODIFIED, metadata.dateModifiedMs
                        )
                        add(
                            DocumentsContract.Document.COLUMN_FLAGS,
                            DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                        )

                        // Poweramp metadata
                        add(MediaStore.Audio.Media.TITLE, metadata.title)
                        add(MediaStore.Audio.Media.ARTIST, metadata.artist)
                        add(MediaStore.Audio.Media.ALBUM, metadata.album)
                        add(MediaStore.Audio.Media.DURATION, metadata.durationMs)
                        add(MediaStore.Audio.Media.YEAR, metadata.year)
                        add(MediaStore.Audio.Media.TRACK, metadata.trackNumber)
                        add(MediaStore.Audio.Media.IS_MUSIC, 1)

                        // Poweramp flags (0x1 = FLAG_SUPPORTS_THUMBNAIL)
                        add("com.maxmpz.poweramp.provider.COLUMN_FLAGS", 0x1)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying track", e)
                }
            }
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        runBlocking {
            try {
                when {
                    parentDocumentId == ROOT_ID -> {
                        // Show albums
                        val albums = jellyfinClient.getAlbums(limit = 1000)

                        albums.forEach { album ->
                            result.newRow().apply {
                                add(
                                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                    "album:${album.id}"
                                )
                                add(
                                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                                    DocumentsContract.Document.MIME_TYPE_DIR
                                )
                                add(
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, album.name
                                )
                                add(
                                    DocumentsContract.Document.COLUMN_FLAGS,
                                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                                )
                            }
                        }
                    }

                    parentDocumentId.startsWith("album:") -> {
                        // Show tracks in album
                        val albumId = parentDocumentId.removePrefix("album:")
                        val tracks = jellyfinClient.getAlbumTracks(UUID.fromString(albumId))

                        tracks.forEach withContext@{ track ->
                            val metadata =
                                jellyfinClient.getTrackMetadata(track.id) ?: return@withContext

                            result.newRow().apply {
                                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, track.id)
                                add(DocumentsContract.Document.COLUMN_MIME_TYPE, metadata.mimeType)
                                add(
                                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, metadata.title
                                )
                                add(DocumentsContract.Document.COLUMN_SIZE, metadata.sizeBytes)
                                add(
                                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                                    metadata.dateModifiedMs
                                )
                                add(
                                    DocumentsContract.Document.COLUMN_FLAGS,
                                    DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                                )

                                // Poweramp metadata
                                add(MediaStore.Audio.Media.TITLE, metadata.title)
                                add(MediaStore.Audio.Media.ARTIST, metadata.artist)
                                add(MediaStore.Audio.Media.ALBUM, metadata.album)
                                add(MediaStore.Audio.Media.DURATION, metadata.durationMs)
                                add(MediaStore.Audio.Media.YEAR, metadata.year)
                                add(MediaStore.Audio.Media.TRACK, metadata.trackNumber)
                                add(MediaStore.Audio.Media.IS_MUSIC, 1)

                                // Poweramp flags
                                add("com.maxmpz.poweramp.provider.COLUMN_FLAGS", 0x1)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying children", e)
            }
        }

        return result
    }

    /*
    openDocument is only ever used for audio content
     */
    override fun openDocument(
        documentId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        Log.d(TAG, "jellyfin openDocument: $documentId")

        // 1. Check if fully cached
        val cachedFile = cacheManager.getCachedFile(UUID.fromString(documentId))
        if (cachedFile != null) {
            Log.d(TAG, "Serving from full cache: $documentId")
            return ParcelFileDescriptor.open(
                cachedFile, ParcelFileDescriptor.MODE_READ_ONLY
            )
        }

        // 2. Return a proxy FD for seekable streaming (Android 8+)
        return openViaProxyFd(documentId, signal)
    }

    private fun openViaProxyFd(
        documentId: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val trackId = UUID.fromString(documentId)
        Log.d(TAG, "jellyfin openViaProxyFd $trackId")

        val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        // We need metadata for size
        val metadata = runBlocking { jellyfinClient.getTrackMetadata(trackId) }
            ?: throw InterruptedException("Failed to read track metadata")
        val totalSize = metadata.sizeBytes

        val handlerThread = HandlerThread("ProxyFD-$trackId")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)

        val cacheFile =
            cacheManager.openFileForStreaming(trackId, metadata) ?: throw FileNotFoundException(
                "Cache file not found"
            )
        val channel = cacheFile.acquire()

        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = totalSize

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset >= totalSize) return 0

                    // Check if the required chunks are available to fulfill request.
                    // If not, start a download in the background 1MB chunks to fulfill request and
                    // return as soon as offset...offset+size is available
                    runBlocking {
                        cacheManager.onReadTrack(
                            trackId, metadata, jellyfinClient, offset, size
                        )
                    }

                    val read = runBlocking { channel.readAt(data, offset, 0, size) }

                    return if (read < 0) 0 else read
                }

                override fun onRelease() {
                    Log.d(TAG, "openViaProxyFd onRelease $trackId")
                    cacheFile.release()
                    handlerThread.quitSafely()
                }
            }, handler
        )
    }

    override fun openDocumentThumbnail(
        documentId: String, sizeHint: Point?, signal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor {

        Log.d(TAG, "jellyfin openDocumentThumbnail $documentId")

        var documentId = documentId
        if (documentId.startsWith("album:")) {
            documentId = documentId.removePrefix("album:")
        }

        var itemId = UUID.fromString(documentId)

        // Get album ID for track
        itemId = runBlocking {
            jellyfinClient.getTrackMetadata(itemId)?.albumId ?: itemId
        }

        // Check thumbnail cache
        var thumbFile = cacheManager.getCachedThumbnail(itemId)

        if (thumbFile == null) {
            // Download thumbnail
            Log.w(TAG, "jellyfin openDocumentThumbnail item=$itemId")
            thumbFile = runBlocking {
                cacheManager.downloadThumbnail(itemId, jellyfinClient)
            } ?: throw FileNotFoundException("Thumbnail not available")
        }

        return android.content.res.AssetFileDescriptor(
            ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    override fun shutdown() {
        scope.cancel()
        cacheManager.shutdown()
        super.shutdown()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return true

        // Everything we serve is under the root
        if (parentDocumentId == ROOT_ID) return true

        // Tracks are children of albums
        if (parentDocumentId.startsWith("album:") && !documentId.startsWith("album:")) {
            // In our system, tracks have raw UUIDs, albums have "album:" prefix
            return true
        }

        return false
    }
}