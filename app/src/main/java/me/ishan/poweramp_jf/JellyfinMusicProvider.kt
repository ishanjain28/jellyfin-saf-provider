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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ishan.poweramp_jf.DocumentId.ROOT_ID
import org.jellyfin.sdk.model.UUID
import java.io.FileNotFoundException
import java.io.IOException


// TODO: Add FLAG_SUPPORTS_METADATA
class JellyfinMusicProvider : DocumentsProvider() {

    private lateinit var jellyfinClient: JellyfinClientManager
    private lateinit var cacheManager: TrackCacheManager
    private lateinit var handlerThread: HandlerThread
    private lateinit var db: MediaDatabaseHelper

    companion object {
        private const val TAG = "JellyfinMusicProvider"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_MIME_TYPES,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_SUMMARY,

            // Poweramp metadata
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.IS_MUSIC,

            // Lyrics
            "lyrics",
            "lyrics_synced",

            // Poweramp flags
            "com.maxmpz.poweramp.provider.COLUMN_FLAGS"
        )

        private const val COLUMN_TRACK_LYRICS = "lyrics"
        private const val COLUMN_TRACK_LYRICS_SYNCED = "lyrics_synced"
    }

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            jellyfinClient = JellyfinClientManager(
                context = ctx,
            )

            cacheManager = TrackCacheManagerSingleton.getInstance(context = ctx)

            handlerThread = HandlerThread("JellyfinSAFProxyThread")
            handlerThread.start()

            db = DatabaseManager.getInstance(ctx)

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
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Your Music Library")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_ID)
        }

        return result
    }

    override fun queryDocument(docId: String, projection: Array<out String>?): MatrixCursor {
        val documentId = DocumentId.parse(docId)
        Log.i(TAG, "queryDocument $documentId")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        when (documentId) {
            is DocumentId.Type.Root -> {
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
            }

            is DocumentId.Type.Album -> {
                throw IOException("queryDocument called on type album? $documentId")
            }

            is DocumentId.Type.Track -> {
                try {
                    val (meta, lyrics) = runBlocking {
                        val p1 = async {
                            jellyfinClient.getTrackMetadata(documentId.trackId)
                        }

                        // Fetch lyrics if requested
                        var p2 = if (projection != null && (projection.contains(
                                COLUMN_TRACK_LYRICS
                            ) || projection.contains(
                                COLUMN_TRACK_LYRICS_SYNCED
                            ))
                        ) {
                            async { jellyfinClient.getLyrics(documentId.trackId) }
                        } else {
                            async { null }
                        }

                        p1.await() to p2.await()
                    }
                    val metadata = meta ?: return result

                    result.newRow().apply {
                        add(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentId.forTrack(
                                metadata.albumId ?: UUID.randomUUID(),
                                metadata.id,
                                metadata.sizeBytes
                            )
                        )
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
                            DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                        )

                        // Poweramp metadata
                        add(MediaStore.Audio.Media.TITLE, metadata.title)
                        add(
                            MediaStore.Audio.Media.ARTIST,
                            metadata.artists.joinToString(separator = "; ")
                        )
                        add(MediaStore.Audio.Media.ALBUM, metadata.album)
                        add(MediaStore.Audio.Media.DURATION, metadata.durationMs)
                        add(MediaStore.Audio.Media.YEAR, metadata.year)
                        add(MediaStore.Audio.Media.TRACK, metadata.trackNumber)
                        add(MediaStore.Audio.AudioColumns.TRACK, metadata.trackNumber)
                        add(MediaStore.Audio.Media.DISC_NUMBER, metadata.discNumber)
                        add(MediaStore.Audio.Media.DATE_ADDED, metadata.dateCreated)
                        add(MediaStore.Audio.Media.IS_FAVORITE, metadata.isFavourite)
                        add(MediaStore.Audio.Media.ALBUM_ARTIST, metadata.albumArtist)
                        add(
                            MediaStore.Audio.Media.GENRE,
                            metadata.genres.joinToString(separator = "; ")
                        )
                        add(MediaStore.Audio.Media.IS_MUSIC, 1)

                        if (lyrics != null) {
                            add(COLUMN_TRACK_LYRICS, lyrics.content)
                            if (lyrics.isSynced) {
                                add(COLUMN_TRACK_LYRICS_SYNCED, lyrics.content)
                            }
                        }

                        // Poweramp flags (0x1 = FLAG_SUPPORTS_THUMBNAIL)
                        add("com.maxmpz.poweramp.provider.COLUMN_FLAGS", 0x1)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error querying track", e)
                }
            }

            else -> {
                throw IOException("unimplemented $documentId!!")
            }
        }

        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String, projection: Array<out String>?, sortOrder: String?
    ): Cursor {
        val parentDocumentId = DocumentId.parse(parentDocumentId)
        Log.i(TAG, "queryChildDocument $parentDocumentId")

        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)

        try {
            when (parentDocumentId) {
                is DocumentId.Type.Root -> {
                    // TODO: List all albums
                    val albums = runBlocking { jellyfinClient.getAlbums(limit = 1000) }

                    albums.forEach { album ->
                        result.newRow().apply {
                            add(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                                DocumentId.forAlbum(album.id)
                            )
                            add(
                                DocumentsContract.Document.COLUMN_MIME_TYPE,
                                DocumentsContract.Document.MIME_TYPE_DIR
                            )
                            add(
                                DocumentsContract.Document.COLUMN_DISPLAY_NAME, album.name
                            )
                            add(
                                DocumentsContract.Document.COLUMN_SUMMARY,
                                "${album.songCount} Songs"
                            )
                            add(
                                DocumentsContract.Document.COLUMN_FLAGS,
                                DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL or DocumentsContract.Document.FLAG_SUPPORTS_METADATA
                            )
                        }
                    }
                }

                is DocumentId.Type.Album -> {
                    // Show tracks in album
                    val tracks =
                        runBlocking { jellyfinClient.getAlbumTracks(parentDocumentId.albumId) }

                    tracks.forEach withContext@{ track ->

                        val (meta, lyrics) = runBlocking {
                            val p1 = async {
                                jellyfinClient.getTrackMetadata(track.id)
                            }

                            // Fetch lyrics!
                            var p2 = if (projection != null && (projection.contains(
                                    COLUMN_TRACK_LYRICS
                                ) || projection.contains(
                                    COLUMN_TRACK_LYRICS_SYNCED
                                ))
                            ) {
                                async { jellyfinClient.getLyrics(track.id) }
                            } else {
                                async { null }
                            }

                            p1.await() to p2.await()
                        }
                        val metadata = meta ?: return@withContext

                        result.newRow().apply {
                            add(
                                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentId.forTrack(
                                    track.albumId ?: UUID.randomUUID(), track.id, metadata.sizeBytes
                                )
                            )
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
                            add(
                                MediaStore.Audio.Media.ARTIST,
                                metadata.artists.joinToString(separator = "; ")
                            )
                            add(MediaStore.Audio.Media.ALBUM, metadata.album)
                            add(MediaStore.Audio.Media.DURATION, metadata.durationMs)
                            add(MediaStore.Audio.Media.YEAR, metadata.year)
                            add(MediaStore.Audio.Media.TRACK, metadata.trackNumber)
                            add(MediaStore.Audio.Media.DISC_NUMBER, metadata.discNumber)
                            add(MediaStore.Audio.AudioColumns.TRACK, metadata.trackNumber)
                            add(MediaStore.Audio.Media.DATE_ADDED, metadata.dateCreated)
                            add(MediaStore.Audio.Media.IS_FAVORITE, metadata.isFavourite)
                            add(MediaStore.Audio.Media.ALBUM_ARTIST, metadata.albumArtist)
                            add(
                                MediaStore.Audio.Media.GENRE,
                                metadata.genres.joinToString(separator = "; ")
                            )
                            add(MediaStore.Audio.Media.IS_MUSIC, 1)
                            if (lyrics != null) {
                                add(COLUMN_TRACK_LYRICS, lyrics.content)
                                if (lyrics.isSynced) {
                                    add(COLUMN_TRACK_LYRICS_SYNCED, lyrics.content)
                                }
                            }

                            // Poweramp flags
                            add("com.maxmpz.poweramp.provider.COLUMN_FLAGS", 0x1)
                        }
                    }
                }

                is DocumentId.Type.Track -> {
                    throw IOException("Should never query child documents of a Track!")
                }

                is DocumentId.Type.Lyric -> {
                    throw IOException("Should never query child documents of a Lyric!")
                }

                is DocumentId.Type.Thumb -> {
                    throw IOException("Should never query child documents of a Thumb!")
                }

                else -> {
                    throw IOException("Unknown branch in queryChildDocument!")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying children", e)
        }

        return result
    }

    /*
    openDocument is only ever used for audio content
     */
    override fun openDocument(
        docId: String, mode: String, signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val documentId = DocumentId.parse(docId)
        Log.i(TAG, "jellyfin openDocument: $documentId mode=$mode")

        if (documentId !is DocumentId.Type.Track) {
            throw IOException("openDocument is not supported for any type except Tracks! type=$documentId")
        }

        signal?.throwIfCanceled()

        // 1. Check if fully cached
        val cachedFile = cacheManager.getCachedFile(documentId.trackId)
        if (cachedFile != null) {
            Log.d(TAG, "Serving from full cache: $documentId")
            return ParcelFileDescriptor.open(
                cachedFile, ParcelFileDescriptor.MODE_READ_ONLY
            )
        }

        // 2. Return a proxy FD for seekable streaming (Android 8+)
        return openViaProxyFd(documentId)
    }

    private fun openViaProxyFd(documentId: DocumentId.Type.Track): ParcelFileDescriptor {
        Log.d(TAG, "jellyfin openViaProxyFd ${documentId.trackId} from ${documentId.albumId}")

        val storageManager = context?.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        synchronized(this) {
            if (!handlerThread.isAlive) {
                handlerThread = HandlerThread("JellyfinSAFProxyThread")
                handlerThread.start()
                Log.d(TAG, "HandlerThread recreated after shutdown")
            }
        }

        val handler = Handler(handlerThread.looper)
        val cacheFile = cacheManager.openFileForStreaming(documentId.trackId, documentId.sizeBytes)
            ?: throw FileNotFoundException(
                "Cache file not found"
            )
        val channel = cacheFile.acquire()

        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = documentId.sizeBytes

                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (offset >= documentId.sizeBytes) return 0

                    // Check if the required chunks are available to fulfill request.
                    // If not, start a download in the background 1MB chunks to fulfill request and
                    // return as soon as offset...offset+size is available
                    runBlocking {
                        cacheManager.onReadTrack(
                            documentId.trackId, documentId.sizeBytes, jellyfinClient, offset, size
                        )
                    }

                    val read = runBlocking { channel.readAt(data, offset, 0, size) }

                    return if (read < 0) 0 else read
                }

                override fun onRelease() {
                    Log.d(
                        TAG,
                        "openViaProxyFd onRelease ${documentId.trackId} from ${documentId.albumId}"
                    )
                    cacheFile.release()
                }
            }, handler
        )
    }

    override fun openDocumentThumbnail(
        documentId: String, sizeHint: Point?, signal: CancellationSignal?
    ): android.content.res.AssetFileDescriptor {
        val albumId = when (val docId = DocumentId.parse(documentId)) {
            is DocumentId.Type.Album -> {
                docId.albumId
            }

            is DocumentId.Type.Track -> {
                docId.albumId
            }

            else -> {
                throw IOException("openDocumentThumbnail is only supported for albums & tracks. Got $docId")
            }
        }


        // Check thumbnail cache
        var thumbFile = cacheManager.getCachedThumbnail(albumId)

        if (thumbFile == null) {
            Log.i(
                TAG,
                "openDocumentThumbnail document=$documentId album=$albumId sizeHint=$sizeHint downloading!"
            )

            // Download thumbnail
            thumbFile = runBlocking {
                cacheManager.downloadThumbnail(albumId, jellyfinClient, sizeHint)
            } ?: throw FileNotFoundException("Thumbnail not available")
        } else {
            Log.i(
                TAG,
                "openDocumentThumbnail document=$documentId album=$albumId sizeHint=$sizeHint served from cache"
            )
        }
        return android.content.res.AssetFileDescriptor(
            ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH
        )
    }

    override fun shutdown() {
        cacheManager.shutdown()
        handlerThread.quitSafely()
        super.shutdown()
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == documentId) return true

        // Everything we serve is under the root
        if (parentDocumentId == ROOT_ID) return true

        val parentId = DocumentId.parse(parentDocumentId)
        val docId = DocumentId.parse(documentId)

        // Tracks are children of albums
        return parentId is DocumentId.Type.Album && (docId !is DocumentId.Type.Album)
    }
}