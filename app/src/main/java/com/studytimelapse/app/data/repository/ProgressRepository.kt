package com.studytimelapse.app.data.repository

import com.studytimelapse.app.data.db.AchievementEntity
import com.studytimelapse.app.data.db.AppDatabase
import com.studytimelapse.app.data.db.SubjectEntity
import com.studytimelapse.app.domain.AchievementEvaluator
import com.studytimelapse.app.domain.AchievementType
import com.studytimelapse.app.domain.StudyRecord
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

class AchievementRepository(private val db: AppDatabase) {
    val unlocked: Flow<List<AchievementEntity>> = db.achievements().observeAll()

    /** Evaluates all achievements and stores new unlocks. Returns only the newly unlocked ones. */
    suspend fun evaluate(records: List<StudyRecord>, zone: ZoneId, today: LocalDate, thresholdMs: Long): List<AchievementType> {
        val already = db.achievements().unlockedTypes().toSet()
        val now = System.currentTimeMillis()
        val fresh = AchievementEvaluator.evaluate(records, zone, today, thresholdMs).filter { it.name !in already }
        if (fresh.isNotEmpty()) db.achievements().insertAll(fresh.map { AchievementEntity(it.name, now) })
        return fresh
    }
}

class SubjectRepository(private val db: AppDatabase) {
    val subjects: Flow<List<SubjectEntity>> = db.subjects().observeAll()

    suspend fun seedDefaults() {
        if (db.subjects().count() > 0) return
        DEFAULTS.forEach { db.subjects().insert(SubjectEntity(name = it)) }
    }

    suspend fun add(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        db.subjects().insert(SubjectEntity(name = clean, lastUsedUtc = System.currentTimeMillis()))
        db.subjects().touch(clean, System.currentTimeMillis())
    }

    suspend fun touch(name: String) = db.subjects().touch(name, System.currentTimeMillis())

    suspend fun archive(id: Long) = db.subjects().archive(id)

    companion object {
        val DEFAULTS = listOf("Computer Science", "Mathematics", "Cybersecurity", "Japanese", "Physics", "Other")
    }
}
