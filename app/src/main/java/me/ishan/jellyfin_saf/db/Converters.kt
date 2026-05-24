package me.ishan.jellyfin_saf.db

import androidx.room.TypeConverter
import org.jellyfin.sdk.model.UUID
import java.util.BitSet
import me.ishan.jellyfin_saf.ContentState

class Converters {
    @TypeConverter
    fun fromUUID(uuid: UUID?): String? {
        return uuid?.toString()
    }

    @TypeConverter
    fun toUUID(uuid: String?): UUID? {
        return uuid?.let { UUID.fromString(it) }
    }

    @TypeConverter
    fun fromBitSet(bitSet: BitSet?): ByteArray? {
        return bitSet?.toByteArray()
    }

    @TypeConverter
    fun toBitSet(byteArray: ByteArray?): BitSet? {
        return byteArray?.let { BitSet.valueOf(it) }
    }

    @TypeConverter
    fun fromContentState(state: ContentState?): String? {
        return state?.name
    }

    @TypeConverter
    fun toContentState(state: String?): ContentState? {
        return state?.let { ContentState.valueOf(it) }
    }
}