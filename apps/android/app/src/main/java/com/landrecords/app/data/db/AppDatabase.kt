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
    version = 3,
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

        /**
         * v2 → v3: add the synced tables (docs/specs/2026-08-14-unified-db-and-autofetch-design.md
         * §1–§3). Purely additive — every existing table is untouched, so dad's already-fetched
         * surveys, records and marks survive. The new tables start empty and are populated by
         * the migration in [com.landrecords.app.data.sync.LegacyMigration], which is re-runnable
         * and can therefore be retried if it is interrupted.
         *
         * The schema is generated from SyncSchema rather than written out here, so the app and
         * the desktop cannot drift apart by a typo in a CREATE TABLE.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                com.landrecords.app.data.sync.SyncDb.createTables(db)
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "land_records.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
