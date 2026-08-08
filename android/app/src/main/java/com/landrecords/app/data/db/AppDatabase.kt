package com.landrecords.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PropertyEntity::class, SurveyEntity::class, RecordEntity::class],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun propertyDao(): PropertyDao
    abstract fun surveyDao(): SurveyDao
    abstract fun recordDao(): RecordDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * v1 → v2: add the nullable `mark` colour column to records. A real migration (not a
         * destructive fallback) so dad's already-fetched surveys/records survive the update —
         * a wipe would orphan every PDF he pulled after the seeded install.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE records ADD COLUMN mark TEXT")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "land_records.db",
                ).addMigrations(MIGRATION_1_2).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
