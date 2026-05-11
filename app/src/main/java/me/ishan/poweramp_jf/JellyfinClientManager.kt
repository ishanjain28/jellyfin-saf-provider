package me.ishan.poweramp_jf

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class JellyfinClientManager(private val context: Context? = null) {
    private val cryptoManager = CryptoManager()

    private val httpClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).build()

    private val prefs: SharedPreferences? =
        context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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
        context?.let { ctx ->
            jellyfin = createJellyfin {
                context = ctx
                clientInfo = ClientInfo(
                    name = "Android Jellyfin Provider", version = "0.0.1"
                )
                deviceInfo = DeviceInfo(
                    id = getDeviceId(), name = android.os.Build.MODEL
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
            true

        } catch (e: Exception) {
            Log.e(TAG, "Login failed", e)
            false
        }
    }

    /**
     * Logout and clear credentials
     */
    fun logout() {
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
     * Get audio items from library
     */
    suspend fun getAudioItems(
        parentId: UUID? = null, recursive: Boolean = true, limit: Int? = null
    ): List<BaseItemDto> = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext emptyList()
        val userId = currentUserId ?: return@withContext emptyList()

        try {
            val result = apiClient.itemsApi.getItems(
                userId = userId,
                parentId = parentId,
                includeItemTypes = setOf(BaseItemKind.AUDIO),
                recursive = recursive,
                fields = setOf(
                    ItemFields.MEDIA_SOURCES,
                    ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
                    ItemFields.OVERVIEW,
                    ItemFields.GENRES,
                    ItemFields.TAGS
                ),
                limit = limit
            )

            result.content.items
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get audio items", e)
            emptyList()
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
                sortBy = setOf(ItemSortBy.SORT_NAME),
                fields = setOf(
                    ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES, ItemFields.OVERVIEW
                )
            )

            result.content.items
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
                sortBy = setOf(ItemSortBy.SORT_NAME),
                limit = 1000,
                fields = setOf(
                    ItemFields.MEDIA_SOURCES, ItemFields.GENRES
                )
            )

            result.content.items
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get album tracks", e)
            emptyList()
        }
    }

    /**
     * Get single item by ID
     */
    suspend fun getItem(itemId: UUID): BaseItemDto? = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext null
        val userId = currentUserId ?: return@withContext null

        try {

            val result = apiClient.userLibraryApi.getItem(
                userId = userId, itemId = itemId
            )
            result.content
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get item", e)
            null
        }
    }

    /**
     * Get track metadata
     */
    suspend fun getTrackMetadata(trackId: UUID): TrackMetadata? = withContext(Dispatchers.IO) {
        val item = getItem(trackId) ?: return@withContext null
        val container = item.mediaSources?.firstOrNull()?.container

        TrackMetadata(
            id = trackId,
            title = item.name.orEmpty(),
            artist = item.albumArtist ?: item.artists?.firstOrNull() ?: "Unknown",
            album = item.album ?: "Unknown",
            albumId = item.albumId,
            year = item.productionYear,
            durationMs = item.runTimeTicks?.div(10000) ?: 0L,
            sizeBytes = item.mediaSources?.firstOrNull()?.size ?: 0L,
            genres = item.genres?.joinToString(", ") ?: "",
            trackNumber = item.indexNumber,
            dateModifiedMs = item.dateCreated,
            mimeType = "audio/${container ?: "mpeg"}"
        )
    }

    /**
     * Download track using OkHttpClient for direct streaming
     */
    suspend fun downloadTrack(
        trackId: UUID,
        outputFile: RefCountedAsyncFileChannel,
        offset: Long = 0L,
        length: Long = -1L,
        onProgress: ((Long) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val url = if (length > 0) getStreamUrl(trackId, 0L) else getStreamUrl(trackId, offset)
        if (url == null) return@withContext false
        val token = accessToken ?: return@withContext false
        val channel = outputFile.acquire()

        try {
            val requestBuilder = Request.Builder().url(url).header("X-Emby-Token", token)

            if (length > 0) {
                requestBuilder.header("Range", "bytes=$offset-${offset + length - 1}")
            }

            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    Log.e(TAG, "Download failed with code: ${response.code}")
                    return@withContext false
                }


                val body = response.body
                val input = body.byteStream()
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalDownloaded = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    channel.writeAt(buffer, offset + totalDownloaded, 0, bytesRead)
                    totalDownloaded += bytesRead
                    onProgress?.invoke(offset + totalDownloaded)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error for track $trackId", e)
            false
        } finally {
            outputFile.release()
        }
    }

    /**
     * Get track stream URL using SDK's URL builder
     */
    fun getStreamUrl(trackId: UUID, offset: Long = 0L): String? {
        val apiClient = getApiClient() ?: return null

        // Use SDK's URL construction with static=true
        return apiClient.audioApi.getAudioStreamUrl(
            itemId = trackId,
            static = true,
            deviceId = getDeviceId(),
            startTimeTicks = offset * 10000
        )
    }

    /**
     * Download album art using SDK's image API
     */
    suspend fun downloadAlbumArt(
        itemId: UUID, outputFile: File, maxWidth: Int = 1000, maxHeight: Int = 1000
    ): Boolean = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext false

        try {
            // Use SDK's image API
            val response = apiClient.imageApi.getItemImage(
                itemId = itemId,
                imageType = ImageType.PRIMARY,
                maxWidth = maxWidth,
                maxHeight = maxHeight,
                quality = 90
            )
            outputFile.writeBytes(response.content)

            true
        } catch (e: Exception) {
            Log.e(TAG, "Album art download error for item $itemId", e)
            false
        }
    }

    /**
     * Get album art URL using SDK
     */
    fun getAlbumArtUrl(
        itemId: UUID, maxWidth: Int = 512, maxHeight: Int = 512
    ): String? {
        val apiClient = getApiClient() ?: return null

        // Use SDK's URL construction
        return apiClient.imageApi.getItemImageUrl(
            itemId = itemId,
            imageType = ImageType.PRIMARY,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            quality = 90
        )
    }

    /**
     * Get album art as InputStream using SDK
     */
    suspend fun getAlbumArtStream(
        itemId: UUID, maxSize: Int = 512
    ): InputStream? = withContext(Dispatchers.IO) {
        val apiClient = getApiClient() ?: return@withContext null

        try {
            val response = apiClient.imageApi.getItemImage(
                itemId = itemId,
                imageType = ImageType.PRIMARY,
                maxWidth = maxSize,
                maxHeight = maxSize,
                quality = 90
            )

            response.content.inputStream()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get album art stream", e)
            null
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

class CryptoManager {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private fun getKey(): SecretKey {
        val existingKey = keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry
        return existingKey?.secretKey ?: createKey()
    }

    private fun createKey(): SecretKey {
        val keyGenerator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return keyGenerator.generateKey()
    }

    fun encrypt(data: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(data.toByteArray())
        val combined = ByteArray(iv.size + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
        return Base64.encodeToString(combined, Base64.DEFAULT)
    }

    fun decrypt(data: String): String {
        val combined = Base64.decode(data, Base64.DEFAULT)
        val iv = combined.sliceArray(0 until 12)
        val encrypted = combined.sliceArray(12 until combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted))
    }

    companion object {
        private const val ALIAS = "jellyfin_secret_key"
    }
}

data class TrackMetadata(
    val id: UUID,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: UUID?,
    val year: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val genres: String,
    val trackNumber: Int?,
    val dateModifiedMs: DateTime?,
    val mimeType: String
)