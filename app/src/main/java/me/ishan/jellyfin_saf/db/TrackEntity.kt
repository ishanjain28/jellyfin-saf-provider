package me.ishan.jellyfin_saf.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import org.jellyfin.sdk.model.UUID

@Entity(
    tableName = "track_metadata",
    indices = [
        Index(value = ["last_fetched"], name = "idx_last_fetched"),
        Index(value = ["is_favourite"], name = "idx_is_favourite"),
        Index(value = ["album_id"], name = "idx_album")
    ]
)
data class TrackEntity(
    @PrimaryKey val id: UUID,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(name = "album_id") val albumId: UUID? = null,
    val duration: Long? = null,
    val size: Long? = null,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "track_number") val trackNumber: Int? = null,
    @ColumnInfo(name = "disc_number") val discNumber: Int? = null,
    @ColumnInfo(name = "album_artist") val albumArtist: String? = null,
    @ColumnInfo(name = "date_modified") val dateModified: String? = null,
    @ColumnInfo(name = "date_created") val dateCreated: String? = null,
    @ColumnInfo(name = "is_favourite") val isFavourite: Boolean = false,
    @ColumnInfo(name = "num_tracks") val numTracks: Int = 0,
    val year: Int? = null,
    val genres: String? = null,
    @ColumnInfo(name = "last_fetched") val lastFetched: Long
)