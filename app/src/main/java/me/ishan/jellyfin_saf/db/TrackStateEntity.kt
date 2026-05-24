package me.ishan.jellyfin_saf.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.jellyfin.sdk.model.UUID
import java.io.File
import java.util.BitSet
import me.ishan.jellyfin_saf.ContentState

@Entity(
    tableName = "track_state",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TrackStateEntity(
    @PrimaryKey val id: UUID,
    val state: ContentState = ContentState.PARTIAL,
    val chunks: BitSet = BitSet(),
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long
) {
    fun getFile(filesDir: File): File {
        return File(filesDir, "$id.cache")
    }

    companion object {
        const val CHUNK_SIZE: Long = 256 * 1024 // 256KiB
    }
}