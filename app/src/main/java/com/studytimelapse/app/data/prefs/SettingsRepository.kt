package com.studytimelapse.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.ZoneId

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Encoder quality. Bitrates are for the 30 fps output video. */
enum class VideoQuality(val label: String, val bitrate720: Int, val bitrate1080: Int) {
    LOW("Low", 1_500_000, 3_000_000),
    MEDIUM("Medium", 3_000_000, 5_000_000),
    HIGH("High", 5_000_000, 9_000_000),
}

enum class CaptureResolution(val label: String, val longEdge: Int, val shortEdge: Int) {
    P480("480p", 640, 480),
    P720("720p", 1280, 720),
    P1080("1080p", 1920, 1080),
}

enum class CaptureOrientation(val label: String) { AUTO("Auto"), PORTRAIT("Portrait"), LANDSCAPE("Landscape") }

enum class CameraFacing { BACK, FRONT }

/**
 * How the screen behaves during a session.
 * DIM keeps the app visible on a black, minimum-brightness screen: works on every device.
 * SCREEN_OFF lets the user lock the phone; capture continues only where the device allows it,
 * which the app verifies frame-by-frame and reports honestly.
 */
enum class StudyScreenMode { DIM, SCREEN_OFF }

data class AppSettings(
    val onboardingDone: Boolean = false,
    val username: String = "",
    val avatar: String = "📚",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Empty = follow the device time zone. */
    val timeZoneId: String = "",
    val streakThresholdMinutes: Int = 30,
    val defaultSubject: String = "",
    val captureIntervalSec: Int = 2,
    val quality: VideoQuality = VideoQuality.MEDIUM,
    val resolution: CaptureResolution = CaptureResolution.P720,
    val orientation: CaptureOrientation = CaptureOrientation.AUTO,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val batterySaver: Boolean = false,
    val screenMode: StudyScreenMode = StudyScreenMode.DIM,
    val timelapseEnabled: Boolean = true,
    val showBatteryWarning: Boolean = true,
    val notifyGoals: Boolean = true,
    val notifyStreaks: Boolean = true,
    val notifyFriends: Boolean = true,
    val remindersEnabled: Boolean = false,
    val reminderMinuteOfDay: Int = 18 * 60,
    /** Bit mask, bit 0 = Monday ... bit 6 = Sunday. */
    val reminderDays: Int = 0b0011111,
    val shareSubjectsWithFriend: Boolean = true,
    val shareActivityWithFriend: Boolean = true,
    val longestStreakEver: Int = 0,
    /** Result of the on-device screen-off capture test: -1 unknown, 0 failed, 1 works. */
    val screenOffCapability: Int = -1,
) {
    val zone: ZoneId
        get() = runCatching { if (timeZoneId.isBlank()) ZoneId.systemDefault() else ZoneId.of(timeZoneId) }
            .getOrDefault(ZoneId.systemDefault())

    val streakThresholdMs: Long get() = streakThresholdMinutes * 60_000L

    /** Effective interval/resolution after Battery Saver Timelapse is applied. */
    val effectiveIntervalSec: Int get() = if (batterySaver) maxOf(captureIntervalSec, 10) else captureIntervalSec
    val effectiveResolution: CaptureResolution get() = if (batterySaver) CaptureResolution.P480 else resolution
    val effectiveQuality: VideoQuality get() = if (batterySaver) VideoQuality.LOW else quality

    fun bitrate(): Int = when (effectiveResolution) {
        CaptureResolution.P1080 -> effectiveQuality.bitrate1080
        CaptureResolution.P720 -> effectiveQuality.bitrate720
        CaptureResolution.P480 -> effectiveQuality.bitrate720 / 2
    }
}

class SettingsRepository(private val context: Context) {

    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val username = stringPreferencesKey("username")
        val avatar = stringPreferencesKey("avatar")
        val theme = stringPreferencesKey("theme")
        val zone = stringPreferencesKey("zone")
        val threshold = intPreferencesKey("streak_threshold_min")
        val defaultSubject = stringPreferencesKey("default_subject")
        val interval = intPreferencesKey("capture_interval")
        val quality = stringPreferencesKey("quality")
        val resolution = stringPreferencesKey("resolution")
        val orientation = stringPreferencesKey("orientation")
        val facing = stringPreferencesKey("facing")
        val batterySaver = booleanPreferencesKey("battery_saver")
        val screenMode = stringPreferencesKey("screen_mode")
        val timelapse = booleanPreferencesKey("timelapse_enabled")
        val batteryWarning = booleanPreferencesKey("battery_warning")
        val notifyGoals = booleanPreferencesKey("notify_goals")
        val notifyStreaks = booleanPreferencesKey("notify_streaks")
        val notifyFriends = booleanPreferencesKey("notify_friends")
        val reminders = booleanPreferencesKey("reminders")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val reminderDays = intPreferencesKey("reminder_days")
        val shareSubjects = booleanPreferencesKey("share_subjects")
        val shareActivity = booleanPreferencesKey("share_activity")
        val longestStreak = intPreferencesKey("longest_streak")
        val screenOffCapability = intPreferencesKey("screen_off_capability")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { read(it, AppSettings()) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val s = transform(read(p, AppSettings()))
            p[K.onboarding] = s.onboardingDone
            p[K.username] = s.username
            p[K.avatar] = s.avatar
            p[K.theme] = s.themeMode.name
            p[K.zone] = s.timeZoneId
            p[K.threshold] = s.streakThresholdMinutes
            p[K.defaultSubject] = s.defaultSubject
            p[K.interval] = s.captureIntervalSec
            p[K.quality] = s.quality.name
            p[K.resolution] = s.resolution.name
            p[K.orientation] = s.orientation.name
            p[K.facing] = s.cameraFacing.name
            p[K.batterySaver] = s.batterySaver
            p[K.screenMode] = s.screenMode.name
            p[K.timelapse] = s.timelapseEnabled
            p[K.batteryWarning] = s.showBatteryWarning
            p[K.notifyGoals] = s.notifyGoals
            p[K.notifyStreaks] = s.notifyStreaks
            p[K.notifyFriends] = s.notifyFriends
            p[K.reminders] = s.remindersEnabled
            p[K.reminderMinute] = s.reminderMinuteOfDay
            p[K.reminderDays] = s.reminderDays
            p[K.shareSubjects] = s.shareSubjectsWithFriend
            p[K.shareActivity] = s.shareActivityWithFriend
            p[K.longestStreak] = s.longestStreakEver
            p[K.screenOffCapability] = s.screenOffCapability
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }

    private fun read(p: Preferences, d: AppSettings) = AppSettings(
        onboardingDone = p[K.onboarding] ?: d.onboardingDone,
        username = p[K.username] ?: d.username,
        avatar = p[K.avatar] ?: d.avatar,
        themeMode = enumOr(p[K.theme], d.themeMode),
        timeZoneId = p[K.zone] ?: d.timeZoneId,
        streakThresholdMinutes = p[K.threshold] ?: d.streakThresholdMinutes,
        defaultSubject = p[K.defaultSubject] ?: d.defaultSubject,
        captureIntervalSec = p[K.interval] ?: d.captureIntervalSec,
        quality = enumOr(p[K.quality], d.quality),
        resolution = enumOr(p[K.resolution], d.resolution),
        orientation = enumOr(p[K.orientation], d.orientation),
        cameraFacing = enumOr(p[K.facing], d.cameraFacing),
        batterySaver = p[K.batterySaver] ?: d.batterySaver,
        screenMode = enumOr(p[K.screenMode], d.screenMode),
        timelapseEnabled = p[K.timelapse] ?: d.timelapseEnabled,
        showBatteryWarning = p[K.batteryWarning] ?: d.showBatteryWarning,
        notifyGoals = p[K.notifyGoals] ?: d.notifyGoals,
        notifyStreaks = p[K.notifyStreaks] ?: d.notifyStreaks,
        notifyFriends = p[K.notifyFriends] ?: d.notifyFriends,
        remindersEnabled = p[K.reminders] ?: d.remindersEnabled,
        reminderMinuteOfDay = p[K.reminderMinute] ?: d.reminderMinuteOfDay,
        reminderDays = p[K.reminderDays] ?: d.reminderDays,
        shareSubjectsWithFriend = p[K.shareSubjects] ?: d.shareSubjectsWithFriend,
        shareActivityWithFriend = p[K.shareActivity] ?: d.shareActivityWithFriend,
        longestStreakEver = p[K.longestStreak] ?: d.longestStreakEver,
        screenOffCapability = p[K.screenOffCapability] ?: d.screenOffCapability,
    )

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> runCatching { enumValueOf<T>(n) }.getOrNull() } ?: default
}
