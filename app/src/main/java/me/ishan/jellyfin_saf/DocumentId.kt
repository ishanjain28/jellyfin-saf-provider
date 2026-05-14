package me.ishan.jellyfin_saf

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
    fun forTrack(albumId: UUID, trackId: UUID, sizeBytes: Long, durationMs: Long) =
        "$P_TRK$SEP$albumId$SEP$trackId$SEP$sizeBytes$SEP$durationMs"

    fun forThumb(albumId: UUID) = "$P_THM$SEP$albumId"
    fun forLyrics(trackId: UUID) = "$P_LYR$SEP$trackId"

    // parsers
    sealed class Type {
        object Root : Type()
        data class Album(val albumId: UUID) : Type()
        data class Track(
            val albumId: UUID, val trackId: UUID, val sizeBytes: Long, val durationMs: Long
        ) : Type()

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
            val rest = id.substring(firstSep + 1)

            return when (prefix) {
                P_ALB -> Type.Album(UUID.fromString(rest))

                P_THM -> Type.Thumb(UUID.fromString(rest))

                P_LYR -> Type.Lyric(UUID.fromString(rest))

                P_TRK -> {
                    val sep1 = rest.indexOf(SEP)
                    val sep2 = rest.indexOf(SEP, sep1 + 1)
                    val sep3 = rest.indexOf(SEP, sep2 + 1)

                    Type.Track(
                        albumId = UUID.fromString(rest.substring(0, sep1)),
                        trackId = UUID.fromString(rest.substring(sep1 + 1, sep2)),
                        sizeBytes = rest.substring(sep2 + 1, sep3).toLong(),
                        durationMs = rest.substring(sep3 + 1).toLong()
                    )
                }

                else -> null
            }
        } catch (e: Exception) {
            return null
        }
    }
}