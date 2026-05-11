package me.ishan.poweramp_jf;
//
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.net.Uri
//import android.util.Log
//import androidx.core.content.ContextCompat
//import kotlinx.coroutines.*
//import org.jellyfin.sdk.model.UUID
//
//class CachePrefetchManager(
//    private val context: Context,
//    private val cacheManager: TrackCacheManager,
//    private val jellyfinClient: JellyfinClientManager
//) {
//    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//
//    private val powerampReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context, intent: Intent) {
//            when (intent.action) {
//                ACTION_TRACK_CHANGED -> {
//                    val trackUri = intent.data
//                    val trackId = extractTrackId(trackUri)
//
//                    trackId?.let {
//                        scope.launch {
//                            handleTrackChanged(UUID.fromString(it))
//                        }
//                    }
//                }
//            }
//        }
//    }
//
//    fun start() {
//        val filter = IntentFilter(ACTION_TRACK_CHANGED)
//        ContextCompat.registerReceiver(
//            context, powerampReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
//        )
//    }
//
//    fun stop() {
//        context.unregisterReceiver(powerampReceiver)
//        scope.cancel()
//    }
//
//    private suspend fun handleTrackChanged(trackId: UUID) {
//        try {
//            // Report playback to Jellyfin
//            //TODO
////            jellyfinClient.reportPlaybackStart(trackId)
//
//            // Prefetch next 3 tracks
//            prefetchUpcoming(trackId)
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error handling track change", e)
//        }
//    }
//
//    private suspend fun prefetchUpcoming(currentTrackId: UUID) {
//        // Get album for current track
//        val metadata = jellyfinClient.getTrackMetadata(currentTrackId)
//        val albumId = metadata?.albumId ?: return
//
//        val albumTracks = jellyfinClient.getAlbumTracks(albumId)
//        val currentIndex = albumTracks.indexOfFirst { it.id == currentTrackId }
//
//        if (currentIndex < 0) return
//
//        // Prefetch next 3 tracks
//        for (i in 1..3) {
//            val nextIndex = currentIndex + i
//            if (nextIndex < albumTracks.size) {
//                val nextTrack = albumTracks[nextIndex]
//                val nextMetadata = jellyfinClient.getTrackMetadata(nextTrack.id)
//
//                cacheManager.downloadTrack(
//                    trackId = nextTrack.id.toString(),
//                    metadata = nextMetadata,
//                    jellyfinClient = jellyfinClient,
//                    priority = i
//                )
//            }
//        }
//    }
//
//    private fun extractTrackId(uri: Uri?): String? {
//        // Parse Jellyfin track ID from content:// URI
//        return uri?.lastPathSegment
//    }
//
//    companion object {
//        private const val TAG = "CachePrefetchManager"
//        private const val ACTION_TRACK_CHANGED = "com.maxmpz.audioplayer.TRACK_CHANGED"
//    }
//}