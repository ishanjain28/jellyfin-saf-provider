package me.ishan.jellyfin_saf.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.jellyfin.sdk.model.UUID
import me.ishan.jellyfin_saf.ContentState

@Dao
interface TrackDao {
    // track_metadata
    @Query("SELECT * FROM track_metadata WHERE id = :id")
    fun getTrackById(id: UUID): TrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrack(track: TrackEntity)

    @Update
    fun updateTrack(track: TrackEntity)

    @Query("DELETE FROM track_metadata")
    fun deleteAllMetadata()

    @Query("SELECT id FROM track_metadata")
    fun getAllTrackIds(): List<UUID>

    @Query("SELECT id FROM track_metadata WHERE is_favourite = 0")
    fun getTrackIdsExcludingFav(): List<UUID>

    @Query("DELETE FROM track_metadata WHERE is_favourite = 0")
    fun deleteTracksExcludingFav()

    @Query("SELECT id FROM track_metadata WHERE is_favourite = 1")
    fun getFavouriteTrackIds(): List<UUID>

    @Query("DELETE FROM track_metadata WHERE is_favourite = 1")
    fun deleteFavouriteTracks()

    @Query("SELECT * FROM track_metadata")
    fun getAllTracks(): List<TrackEntity>

    @Query("DELETE FROM track_metadata WHERE id = :id")
    fun deleteTrackById(id: UUID)

    // track_state
    @Query("SELECT * FROM track_state WHERE id = :id")
    fun getTrackStateById(id: UUID): TrackStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrackState(state: TrackStateEntity)

    @Update
    fun updateTrackState(state: TrackStateEntity)

    @Query("DELETE FROM track_state")
    fun deleteAllStates()

    @Query("DELETE FROM track_state WHERE id = :id")
    fun deleteTrackStateById(id: UUID)

    @Query("SELECT id FROM track_state WHERE state = :state")
    fun getTrackIdsByState(state: ContentState): List<UUID>

    @Query("SELECT s.id FROM track_state s INNER JOIN track_metadata m ON s.id = m.id WHERE s.state = :state AND m.is_favourite = 0")
    fun getTrackIdsByStateExcludingFav(state: ContentState): List<UUID>

    @Query("DELETE FROM track_state WHERE id IN (SELECT s.id FROM track_state s INNER JOIN track_metadata m ON s.id = m.id WHERE s.state = :state AND m.is_favourite = 0)")
    fun deleteTracksByStateExcludingFav(state: ContentState)

    @Query("DELETE FROM track_state WHERE state = :state")
    fun deleteTracksByState(state: ContentState)

    @Query("SELECT * FROM track_state")
    fun getAllTrackStates(): List<TrackStateEntity>

    @Query("SELECT s.* FROM track_state s INNER JOIN track_metadata m ON s.id = m.id WHERE m.is_favourite = 0")
    fun getOldestAccessedTrackStatesExcludingFav(): List<TrackStateEntity>

    // track_lyrics
    @Query("SELECT * FROM track_lyrics WHERE id = :id")
    fun getTrackLyricsById(id: UUID): TrackLyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTrackLyrics(lyrics: TrackLyricsEntity)

    @Query("DELETE FROM track_lyrics")
    fun deleteAllLyrics()

    @Query("DELETE FROM track_lyrics WHERE id = :id")
    fun deleteTrackLyricsById(id: UUID)
}