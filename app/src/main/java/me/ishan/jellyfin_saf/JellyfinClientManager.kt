package me.ishan.jellyfin_saf

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Point
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.lyricsApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import java.io.File
import java.util.concurrent.TimeUnit

class JellyfinClientManager(private val context: Context? = null) {
    private val cryptoManager = CryptoManager()

    private val httpClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).connectionPool(
            ConnectionPool(
                maxIdleConnections = 5, keepAliveDuration = 5, TimeUnit.MINUTES
            )
        ).retryOnConnectionFailure(true).build()

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val db = DatabaseManager.getInstance(context)
    private var jellyfin: Jellyfin? = null
    private var api: ApiClient? = null
    private var currentUserId: UUID? = null

    // Credentials
    private var serverUrl: String = ""
    private var username: String = ""
    private var password: String = ""
    private var accessToken: String? = null

    init {
        loadCredentials()
        initializeJellyfin()
    }

    private fun initializeJellyfin() {
        context.let { ctx ->
            jellyfin = createJellyfin {
                context = ctx
                clientInfo = ClientInfo(
                    name = "Android Jellyfin Provider", version = "0.0.1"
                )
                deviceInfo = DeviceInfo(
                    id = getDeviceId(), name = Build.MODEL
                )
            }
        }
    }

    private fun loadCredentials() {
        prefs?.let { p ->
            serverUrl = p.getString(KEY_SERVER_URL, "") ?: ""
            username = p.getString(KEY_USERNAME, "") ?: ""

            // Decrypt sensitive data
            val encryptedPassword = p.getString(KEY_PASSWORD, null)
            password = if (!encryptedPassword.isNullOrEmpty()) {
                try {
                    cryptoManager.decrypt(encryptedPassword)
                } catch (e: Exception) {
                    ""
                }
            } else ""

            val encryptedToken = p.getString(KEY_ACCESS_TOKEN, null)
            accessToken = if (!encryptedToken.isNullOrEmpty()) {
                try {
                    cryptoManager.decrypt(encryptedToken)
                } catch (e: Exception) {
                    null
                }
            } else null

            currentUserId = p.getString(KEY_USER_ID, null)?.let { UUID.fromString(it) }
        }
    }

    fun getUrl(): String = serverUrl
    fun getUsername(): String = username
    fun getPassword(): String = password
    fun getUserId(): UUID? = currentUserId
    fun getAccessToken(): String? = accessToken
    fun isAuthenticated(): Boolean {
        if (accessToken == null || currentUserId == null) {
            loadCredentials()
        }
        return accessToken != null && currentUserId != null
    }

    fun getMaxCacheSize(): Long = prefs?.getLong(KEY_MAX_CACHE_SIZE, 2048) ?: 2048

    fun setMaxCacheSize(sizeMB: Long) {
        prefs?.edit { putLong(KEY_MAX_CACHE_SIZE, sizeMB) }
    }

    fun updateCredentials(url: String, user: String, pass: String) {
        serverUrl = url.trimEnd('/')
        username = user
        password = pass
    }

    private fun persistCredentials() {
        prefs?.edit {
            putString(KEY_SERVER_URL, serverUrl)
            putString(KEY_USERNAME, username)

            // Encrypt password
            val encryptedPassword = try {
                cryptoManager.encrypt(password)
            } catch (e: Exception) {
                ""
            }
            putString(KEY_PASSWORD, encryptedPassword)

            // Encrypt token
            val encryptedToken = try {
                accessToken?.let { cryptoManager.encrypt(it) }
            } catch (e: Exception) {
                null
            }
            putString(KEY_ACCESS_TOKEN, encryptedToken)

            putString(KEY_USER_ID, currentUserId.toString())
        }
    }

    /**
     * Authenticate with Jellyfin server
     */
    suspend fun login(): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isEmpty() || username.isEmpty()) {
            Log.e(TAG, "Server URL or username is empty")
            return@withContext false
        }

        try {
            val jellyfinInstance = jellyfin ?: run {
                Log.e(TAG, "Jellyfin not initialized")
                return@withContext false
            }

            api = jellyfinInstance.createApi(baseUrl = serverUrl)

            // Authenticate using SDK
            val authResult = api!!.userApi.authenticateUserByName(
                username = username, password = password
            )

            val token = authResult.content.accessToken
            val userId = authResult.content.user?.id

            if (token == null || userId == null) {
                Log.e(TAG, "Authentication failed: no token or user ID")
                return@withContext false
            }

            // Update API with token
            api!!.update(accessToken = token)

            // Save credentials
            accessToken = token
            currentUserId = userId

            persistCredentials()

            Log.d(TAG, "Login successful: userId=$currentUserId")

            // TODO: notify documents provider
            true

        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            false
        }
    }

    /**
     * Logout and clear credentials
     */
    suspend fun logout() {
        // invalidate the access token on remote server
        getApiClient()?.sessionApi?.reportSessionEnded();

        accessToken = null
        currentUserId = null

        prefs?.edit {
            remove(KEY_ACCESS_TOKEN)
            remove(KEY_USER_ID)
        }
    }

    /**
     * Get authenticated API client
     */
    fun getApiClient(): ApiClient? {
        if (api == null && isAuthenticated()) {
            initializeApi()
        }

        if (!isAuthenticated()) {
            Log.w(TAG, "Not authenticated")
            return null
        }
        return api
    }

    private fun initializeApi() {
        if (serverUrl.isEmpty() || accessToken.isNullOrEmpty()) return

        try {
            val jellyfinInstance = jellyfin ?: return
            api = jellyfinInstance.createApi(baseUrl = serverUrl)
            api!!.update(accessToken = accessToken!!)
            Log.d(TAG, "API re-initialized from stored credentials")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-initialize API", e)
        }
    }

    /**
     * Test connection to server
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        if (serverUrl.isEmpty()) return@withContext false

        try {
            val jellyfinInstance = jellyfin ?: return@withContext false
            val testApi = jellyfinInstance.createApi(baseUrl = serverUrl)

            // Use SDK's system API
            val systemInfo = testApi.systemApi.getPublicSystemInfo()
            Log.d(TAG, "Server connection successful: ${systemInfo.content.serverName}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }

    /**
     * Get albums from library
     */
    suspend fun getAlbums(
        limit: Int? = null, startIndex: Int? = null
    ): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext emptyList()
        val userId = currentUserId ?: return@withContext emptyList()

        try {
            val result = apiClient.itemsApi.getItems(
                userId = userId,
                includeItemTypes = setOf(BaseItemKind.MUSIC_ALBUM),
                recursive = true,
                limit = limit,
                startIndex = startIndex,
                fields = setOf(ItemFields.GENRES)
            )

            result.content.items
        } catch (e: InvalidStatusException) {
            Log.w(TAG, "getAlbums limit=$limit startIndex=$startIndex received ${e.status} code")
            if (e.status == 401) {
                accessToken = null
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get albums", e)
            emptyList()
        }
    }

    /**
     * Get tracks in an album
     */
    suspend fun getAlbumTracks(albumId: UUID): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext emptyList()
        val userId = currentUserId ?: return@withContext emptyList()

        try {
            val result = apiClient.itemsApi.getItems(
                userId = userId,
                parentId = albumId,
                includeItemTypes = setOf(BaseItemKind.AUDIO),
                limit = 100,
                fields = setOf(
                    ItemFields.MEDIA_SOURCES, ItemFields.GENRES
                )
            )

            result.content.items
        } catch (e: InvalidStatusException) {
            Log.w(TAG, "getAlbumTracks $albumId received ${e.status} code")
            if (e.status == 401) {
                accessToken = null
            }
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get album tracks", e)
            emptyList()
        }
    }

    /**
     * Get single track metadata by ID
     */
    suspend fun getTrackMetadata(trackId: UUID): TrackMetadata? = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext null
        val userId = currentUserId ?: return@withContext null

        val meta = db.getCachedMetadata(trackId)
        if (meta != null && !meta.isStale()) {
            return@withContext meta
        }

        try {
            val result = apiClient.userLibraryApi.getItem(
                userId = userId, itemId = trackId
            )

            val item = result.content
            val metadata = TrackMetadata(
                id = trackId,
                title = item.name.orEmpty(),
                artists = item.artists ?: emptyList(),
                albumId = item.albumId,
                album = item.album ?: "Unknown Album",
                year = item.productionYear,
                durationMs = item.runTimeTicks?.div(10000) ?: 0L,
                sizeBytes = item.mediaSources?.firstOrNull()?.size ?: 0L,
                genres = item.genres ?: emptyList<String>(),
                trackNumber = item.indexNumber,
                numTracks = item.indexNumberEnd,
                discNumber = item.parentIndexNumber,
                dateModifiedMs = item.dateCreated,
                mimeType = "audio/${item.mediaSources?.firstOrNull()?.container ?: "mpeg"}",
                albumArtist = item.albumArtist ?: "",
                dateCreated = item.dateCreated,
                isFavourite = item.userData?.isFavorite ?: false,
            )

            db.saveMetadata(metadata)

            return@withContext metadata
        } catch (e: InvalidStatusException) {
            Log.w(TAG, "getItem item=$trackId received ${e.status} code, returning stale data")
            if (e.status == 401) {
                accessToken = null
            }
            meta
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get item, returning stale data", e)
            meta
        }
    }

    /**
     * Download track using OkHttpClient for direct streaming
     */
    suspend fun downloadTrack(
        trackId: UUID,
        outputFile: RefCountedAsyncFileChannel,
        byteOffset: Long = 0L,
        byteLength: Long = -1L,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val url = getStreamUrl(trackId) ?: return@withContext false
        val token = accessToken ?: return@withContext false
        val channel = outputFile.acquire()

        try {
            val requestBuilder = Request.Builder().url(url).header("X-Emby-Token", token)
            if (byteLength > 0) {
                requestBuilder.header("Range", "bytes=$byteOffset-${byteOffset + byteLength - 1}")
            }

            val call = httpClient.newCall(requestBuilder.build())

            // Cancel okhttp download on coroutine cancellation
            coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) {
                    call.cancel()
                }
            }

            call.execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    Log.e(TAG, "Download failed with code: ${response.code}")
                    return@withContext false
                }

                val body = response.body
                val input = body.byteStream()
                val buffer = ByteArray(32 * 1024)
                var bytesRead: Int
                var totalDownloaded = 0L
                var lastProgressBoundary = 0L
                val progressThreshold = 64 * 1024  // 64KB - Poweramp's typical read size

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    channel.writeAt(buffer, byteOffset + totalDownloaded, 0, bytesRead)
                    totalDownloaded += bytesRead

                    // Only invoke progress callback when crossing 64KB boundaries
                    val currentBoundary = totalDownloaded / progressThreshold
                    if (currentBoundary > lastProgressBoundary) {
                        onProgress?.invoke(byteOffset + totalDownloaded)
                        lastProgressBoundary = currentBoundary
                    }
                }

                // Final progress update to ensure we report completion
                onProgress?.invoke(byteOffset + totalDownloaded)
            }
            true
        } catch (e: InvalidStatusException) {
            Log.w(
                TAG,
                "downloadTrack track=$trackId offset=$byteOffset length=$byteLength received ${e.status} code"
            )
            if (e.status == 401) {
                accessToken = null
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "Download ended for track $trackId: ${e.message}")
            false
        } finally {
            outputFile.release()
        }
    }

    /**
     * Get track stream URL using SDK's URL builder
     */
    fun getStreamUrl(trackId: UUID): String? {
        val apiClient = getApiClient() ?: return null

        // Use SDK's URL construction with static=true
        return apiClient.audioApi.getAudioStreamUrl(
            itemId = trackId,
            static = true,
            deviceId = getDeviceId(),
        )
    }

    /**
     * Download album art using SDK's image API
     */
    suspend fun downloadAlbumArt(
        itemId: UUID, outputFile: File, sizeHint: Point?,
    ): Boolean = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext false

        // Use SDK's image API
        try {
            val response = apiClient.imageApi.getItemImage(
                itemId = itemId,
                imageType = ImageType.PRIMARY,
                maxWidth = sizeHint?.x ?: 1000,
                maxHeight = sizeHint?.y ?: 1000,
                quality = 90
            )

            outputFile.writeBytes(response.content)
            true
        } catch (e: InvalidStatusException) {
            Log.w(TAG, "downloadAlbumArt item=$itemId sizeHint=$sizeHint received ${e.status} code")
            if (e.status == 401) {
                accessToken = null
            }
            false
        } catch (e: Exception) {
            // run this just in case it failed because parent dir was wiped out after "Clean Cache"
            outputFile.parentFile?.mkdirs()
            Log.e(TAG, "Failed to get Album Art for $itemId", e)
            false
        }
    }


    /**
     * Download lyrics using SDK's lyrics API and format as LRC if synced
     */
    @SuppressLint("DefaultLocale")
    suspend fun getLyrics(trackId: UUID): LyricsMetadata? {
        val apiClient = getApiClient() ?: return null

        val data = db.getCachedLyrics(trackId)
        if (data != null && !data.isStale()) {
            return data
        }

        return try {
            val response = apiClient.lyricsApi.getLyrics(trackId)
            val lyricDto = response.content

            val isSynced = lyricDto.metadata.isSynced ?: (lyricDto.lyrics.any { it.start != null })

            val sb = StringBuilder()
            lyricDto.lyrics.forEach { line ->
                if (line.start != null) {
                    val ticks = line.start!!
                    val totalMilliseconds = ticks / 10_000
                    val minutes = totalMilliseconds / 60_000
                    val seconds = (totalMilliseconds % 60_000) / 1000
                    val hundredths = (totalMilliseconds % 1000) / 10
                    sb.append(
                        String.format(
                            "[%02d:%02d.%02d]%s\n", minutes, seconds, hundredths, line.text
                        )
                    )
                } else {
                    sb.append(line.text).append("\n")
                }
            }
            val lyrics = sb.toString()

            db.saveLyrics(trackId, lyrics, isSynced)

            LyricsMetadata(trackId, lyrics, isSynced)
        } catch (e: InvalidStatusException) {
            Log.w(TAG, "getLyrics track=$trackId received ${e.status} code, returning stale data")
            if (e.status == 401) {
                accessToken = null
            }
            data
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get lyrics for $trackId, returning stale data", e)
            data
        }
    }


//    /**
//     * Report playback start using SDK
//     */
//    suspend fun reportPlaybackStart(
//        trackId: UUID, canSeek: Boolean = true
//    ) = withContext(Dispatchers.IO) {
//        val apiClient = getApiClient() ?: return@withContext
//
//        try {
//            apiClient.playStateApi.reportPlaybackStart(
//                playbackStartInfo = PlaybackStartInfo(
//                    itemId = trackId,
//                    canSeek = canSeek,
//                    isPaused = false,
//                    isMuted = false,
//                    repeatMode = RepeatMode.REPEAT_NONE
//                )
//            )
//            Log.d(TAG, "Reported playback start for track $trackId")
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to report playback start", e)
//        }
//    }

//    /**
//     * Report playback progress using SDK
//     */
//    suspend fun reportPlaybackProgress(
//        trackId: UUID, positionTicks: Long, isPaused: Boolean = false, isMuted: Boolean = false
//    ) = withContext(Dispatchers.IO) {
//        val apiClient = getApiClient() ?: return@withContext
//
//        try {
//            apiClient.playStateApi.reportPlaybackProgress(
//                playbackProgressInfo = PlaybackProgressInfo(
//                    itemId = trackId,
//                    positionTicks = positionTicks,
//                    isPaused = isPaused,
//                    isMuted = isMuted,
//                    canSeek = true,
//                    repeatMode = RepeatMode.REPEAT_NONE
//                )
//            )
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to report playback progress", e)
//        }
//    }
//
//    /**
//     * Report playback stopped / Scrobble using SDK
//     */
//    suspend fun reportPlaybackStopped(
//        trackId: UUID, positionTicks: Long
//    ) = withContext(Dispatchers.IO) {
//        val apiClient = getApiClient() ?: return@withContext
//
//        try {
//            apiClient.playStateApi.reportPlaybackStopped(
//                playbackStopInfo = PlaybackStopInfo(
//                    itemId = trackId, positionTicks = positionTicks
//                )
//            )
//            Log.d(TAG, "Reported playback stopped for track $trackId")
//        } catch (e: Exception) {
//            Log.e(TAG, "Failed to report playback stopped", e)
//        }
//    }

    private fun getDeviceId(): String {
        val deviceId = prefs?.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs?.edit { putString(KEY_DEVICE_ID, id) }
            id
        }
        return deviceId
    }

    companion object {
        private const val TAG = "JellyfinClientManager"

        private const val PREFS_NAME = "jellyfin_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MAX_CACHE_SIZE = "max_cache_size"
    }
}
