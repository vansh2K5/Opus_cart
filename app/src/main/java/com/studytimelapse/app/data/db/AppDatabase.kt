package com.studytimelapse.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class,
        SpanEntity::class,
        SegmentEntity::class,
        SubjectEntity::class,
        GoalEntity::class,
        AchievementEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun spans(): SpanDao
    abstract fun segments(): SegmentDao
    abstract fun subjects(): SubjectDao
    abstract fun goals(): GoalDao
    abstract fun achievements(): AchievementDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "studytimelapse.db")
                // Future schema changes must ship explicit migrations: study history is never
                // silently dropped. (No fallbackToDestructiveMigration on purpose.)
                .build()
    }
}
