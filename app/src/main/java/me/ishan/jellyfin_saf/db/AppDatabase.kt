package me.ishan.jellyfin_saf.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
	entities = [TrackEntity::class, TrackStateEntity::class, TrackLyricsEntity::class],
	version = 2,
	exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
	abstract fun trackDao(): TrackDao
	
	companion object {
		@Volatile
		private var INSTANCE: AppDatabase? = null
		
		fun getDatabase(context: Context): AppDatabase {
			return INSTANCE ?: synchronized(this) {
				val instance = Room.databaseBuilder(
					context.applicationContext,
					AppDatabase::class.java,
					"jellyfin_media_cache_room.db"
				).fallbackToDestructiveMigration(true).build()
				INSTANCE = instance
				instance
			}
		}
	}
}