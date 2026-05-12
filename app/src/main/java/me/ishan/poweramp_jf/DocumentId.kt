package me.ishan.poweramp_jf

import org.jellyfin.sdk.model.UUID

object DocumentId {
    private const val TAG = "DocumentId"
    private const val SEP = ";"
    const val ROOT_ID = "jellyfin_root"

    // prefixes for each type
    private const val P_ALB = "alb"
    private const val P_TRK = "trk"
    private const val P_THM = "thm"
    private const val P_LYR = "lyr"

    // builders
    fun forRoot() = "jellyfin_root"
    fun forAlbum(albumId: UUID) = "$P_ALB$SEP$albumId"
    fun forTrack(albumId: UUID, trackId: UUID, sizeBytes: Long) =
        "$P_TRK$SEP$albumId$SEP$trackId$SEP$sizeBytes"

    fun forThumb(albumId: UUID) = "$P_THM$SEP$albumId"
    fun forLyrics(trackId: UUID) = "$P_LYR$SEP$trackId"

    // parsers
    sealed class Type {
        object Root : Type()
        data class Album(val albumId: UUID) : Type()
        data class Track(val albumId: UUID, val trackId: UUID, val sizeBytes: Long) : Type()
        data class Thumb(val albumId: UUID) : Type()
        data class Lyric(val trackId: UUID) : Type()
    }

    fun parse(id: String): Type? {
        if (id == ROOT_ID) {
            return Type.Root
        }

        try {
            val firstSep = id.indexOf(SEP)
            if (firstSep == -1) return null

            val prefix = id.substring(0, firstSep)
            var rest = id.substring(firstSep + 1)

            return when (prefix) {
                P_ALB -> Type.Album(UUID.fromString(rest))

                P_THM -> Type.Thumb(UUID.fromString(rest))

                P_LYR -> Type.Lyric(UUID.fromString(rest))

                P_TRK -> {
                    val secondSep = rest.indexOf(SEP)
                    val albumId = UUID.fromString(rest.substring(0, secondSep))
                    val rest = rest.substring(secondSep + 1)
                    val thirdSep = rest.indexOf(SEP)
                    val trackId = UUID.fromString(rest.substring(0, thirdSep))
                    val sizeBytes = rest.substring(thirdSep + 1).toLong()

                    Type.Track(albumId, trackId, sizeBytes)
                }

                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }
}