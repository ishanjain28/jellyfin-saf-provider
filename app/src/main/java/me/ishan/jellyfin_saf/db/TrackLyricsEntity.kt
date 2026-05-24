package me.ishan.jellyfin_saf.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.jellyfin.sdk.model.UUID

@Entity(
    tableName = "track_lyrics",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TrackLyricsEntity(
    @PrimaryKey val id: UUID,
    val content: String,
    @ColumnInfo(name = "is_synced") val isSynced: Boolean,
    @ColumnInfo(name = "last_fetched") val lastFetched: Long
) {
    fun isStale(): Boolean {
        return (System.currentTimeMillis() - lastFetched) >= STALE_LIMIT
    }

    companion object {
        const val STALE_LIMIT: Long = 7 * 24 * 60 * 60 * 1000 // 7 Days
    }
}