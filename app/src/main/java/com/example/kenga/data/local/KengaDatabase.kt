package com.example.kenga.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        VideoEntity::class,
        SetLogEntity::class,
        ExerciseEntity::class,
        TailSnapshotEntity::class,
        PbEntity::class,
        TailHabitEntryEntity::class,
        TailWeightSetEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class KengaDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao
    abstract fun setLogDao(): SetLogDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun tailSnapshotDao(): TailSnapshotDao
    abstract fun pbDao(): PbDao
    abstract fun tailHabitEntryDao(): TailHabitEntryDao
    abstract fun tailWeightSetDao(): TailWeightSetDao

    companion object {
        const val NAME = "kenga.db"

        /**
         * v1 → v2 (Phase 4): transcript columns on `videos` so the fetched
         * transcript survives process death and is never re-scraped (Octo
         * `OctoMigrations` pattern; schema exported to app/schemas/2.json).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE videos ADD COLUMN transcriptText TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE videos ADD COLUMN transcriptSource TEXT NOT NULL DEFAULT 'NONE'"
                )
            }
        }

        /**
         * v3 → v4: `pbs.lastDate` — the last day each exercise was done
         * (from Tail's per-exercise PB rows), so the body figure counts
         * DISTINCT exercises per day instead of day-slot aggregates only.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pbs ADD COLUMN lastDate TEXT")
            }
        }

        /**
         * v2 → v3: `tail_habit_entries` — cached Tail body-weight habit
         * day-aggregates (habit + subtype + date → count) backing the Log
         * tab's "Done in Tail" rows and the body-figure set counts.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tail_habit_entries` (" +
                        "`habitName` TEXT NOT NULL, `subtype` TEXT NOT NULL, " +
                        "`date` TEXT NOT NULL, `value` INTEGER NOT NULL, `ts` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`habitName`, `subtype`, `date`))"
                )
            }
        }

        /**
         * v4 → v5: `tail_weight_sets` — mirror of Tail's per-set weights
         * sidecar (`/v3/weights/sets`): one row per individually logged set,
         * distinguishing multiple exercises done on the same day/slot (the
         * day-aggregated snapshot cannot).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tail_weight_sets` (" +
                        "`habitName` TEXT NOT NULL, `date` TEXT NOT NULL, " +
                        "`time` TEXT NOT NULL, `exerciseName` TEXT NOT NULL, " +
                        "`machine` INTEGER NOT NULL, `weightGrams` INTEGER NOT NULL, " +
                        "`reps` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`habitName`, `date`, `time`, `exerciseName`))"
                )
            }
        }

        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}

/** Process-wide singleton; owned by [com.example.kenga.KengaApplication.AppContainer]. */
object DatabaseProvider {

    @Volatile
    private var instance: KengaDatabase? = null

    fun get(context: Context): KengaDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KengaDatabase::class.java,
                KengaDatabase.NAME,
            )
                .addMigrations(*KengaDatabase.ALL_MIGRATIONS)
                .build().also { instance = it }
        }
}
