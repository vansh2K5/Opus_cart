package com.studytimelapse.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.data.repository.Dashboard
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.PeriodStats
import com.studytimelapse.app.domain.StatsCalculator
import com.studytimelapse.app.domain.StudyRecord
import com.studytimelapse.app.ui.components.ChoicePill
import com.studytimelapse.app.ui.components.EmptyState
import com.studytimelapse.app.ui.components.Hairline
import com.studytimelapse.app.ui.components.KeyValueRow
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.MonthCalendar
import com.studytimelapse.app.ui.components.StudyHeatmap
import com.studytimelapse.app.ui.components.SubjectBars
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
fun ActivityScreen(container: AppContainer, nav: NavController) {
    val dashboard by container.stats.dashboard.collectAsStateWithLifecycle(initialValue = null)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val d = dashboard

    Page(title = "Activity") {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf("Feed", "Calendar", "Stats").forEachIndexed { i, label -> ChoicePill(label, tab == i, { tab = i }) }
        }
        if (d == null) return@Page
        if (d.records.isEmpty()) {
            EmptyState("Your desk is waiting.", "Finished sessions show up here.", "Start your first session") { nav.navigate(Routes.SETUP) }
            return@Page
        }
        when (tab) {
            0 -> Feed(d, nav)
            1 -> CalendarTab(d, nav)
            else -> StatsTab(d)
        }
    }
}

@Composable
private fun Feed(d: Dashboard, nav: NavController) {
    d.records.take(100).forEachIndexed { index, r ->
        ActivityCard(r, d, nav, showStreak = index == 0 && d.streak.current > 1)
    }
    if (d.records.size > 100) {
        Text("Older sessions are in the calendar.", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
    }
}

@Composable
fun ActivityCard(r: StudyRecord, d: Dashboard, nav: NavController, showStreak: Boolean = false) {
    SurfaceCard(onClick = { nav.navigate(Routes.session(r.id)) }) {
        Kicker(Dates.relative(r.startUtc, d.zone))
        Spacer(Modifier.height(Spacing.s))
        Text("📚 ${r.subject}", style = MaterialTheme.typography.titleMedium)
        Text(Format.duration(r.studyMs), style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            if (showStreak) Text("🔥 ${d.streak.current}-day streak", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (r.hasTimelapse) Text("▶ Timelapse", style = MaterialTheme.typography.labelLarge, color = LocalExtraColors.current.slate)
        }
    }
}

@Composable
private fun CalendarTab(d: Dashboard, nav: NavController) {
    var monthOffset by rememberSaveable { mutableIntStateOf(0) }
    val month = YearMonth.from(d.today).minusMonths(monthOffset.toLong())
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthOffset++ }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous month") }
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = { if (monthOffset > 0) monthOffset-- }, enabled = monthOffset > 0) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
        Spacer(Modifier.height(Spacing.m))
        MonthCalendar(month, d.daily, d.today, d.settings.streakThresholdMs) { nav.navigate(Routes.day(it)) }
        Spacer(Modifier.height(Spacing.s))
        Text(
            "Dots mark days that count toward your streak (${d.settings.streakThresholdMinutes}+ min).",
            style = MaterialTheme.typography.bodySmall,
            color = LocalExtraColors.current.slate,
        )
    }
    SurfaceCard {
        Kicker("Last 20 weeks")
        Spacer(Modifier.height(Spacing.m))
        StudyHeatmap(d.daily, d.today) { nav.navigate(Routes.day(it)) }
    }
    SurfaceCard {
        KeyValueRow("Current streak", "${d.streak.current} days")
        Hairline()
        KeyValueRow("Longest streak", "${d.streak.longest} days")
        Hairline()
        KeyValueRow("Days studied", "${d.streak.daysStudied}")
        Hairline()
        KeyValueRow("This month's consistency", "${d.month.consistencyPercent}%")
    }
}

@Composable
private fun StatsTab(d: Dashboard) {
    var period by rememberSaveable { mutableStateOf("Week") }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        listOf("Day", "Week", "Month", "All time").forEach { ChoicePill(it, period == it, { period = it }) }
    }
    val stats = when (period) {
        "Day" -> d.todayStats
        "Week" -> d.week
        "Month" -> d.month
        else -> d.allTime
    }
    PeriodCard(stats, period, d)
    if (stats.subjects.isNotEmpty()) {
        SurfaceCard {
            Kicker("Subjects")
            Spacer(Modifier.height(Spacing.m))
            SubjectBars(stats.subjects)
        }
    }
}

@Composable
private fun PeriodCard(s: PeriodStats, period: String, d: Dashboard) {
    SurfaceCard {
        Kicker(if (period == "All time") "All time" else "This ${period.lowercase()}")
        Text(Format.duration(s.totalMs), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(Spacing.s))
        KeyValueRow("Sessions", "${s.sessions}")
        Hairline()
        KeyValueRow("Average session", Format.duration(s.averageSessionMs))
        Hairline()
        KeyValueRow("Longest session", Format.duration(s.longestSessionMs))
        if (period != "Day") {
            Hairline()
            KeyValueRow("Average per day", Format.duration(s.averageDailyMs))
            s.bestDay?.let {
                Hairline()
                KeyValueRow("Best day", "${Dates.short(it)} · ${Format.duration(s.bestDayMs)}")
            }
            Hairline()
            KeyValueRow("Consistency", "${s.consistencyPercent}%")
            Hairline()
            KeyValueRow("Days studied", "${s.daysStudied}")
        }
        Hairline()
        KeyValueRow("Timelapses", "${s.timelapses}")
        if (period == "Week") {
            Hairline()
            KeyValueRow("Streak", "${d.streak.current} days")
        }
        if (period == "Month") {
            val bestWeek = s.let {
                val perWeek = d.daily.filterKeys { k -> !k.isBefore(it.start) && !k.isAfter(it.endInclusive) }
                    .entries.groupBy { e -> StatsCalculator.weekStart(e.key) }
                    .mapValues { e -> e.value.sumOf { v -> v.value } }
                perWeek.maxByOrNull { e -> e.value }
            }
            bestWeek?.let {
                Hairline()
                KeyValueRow("Best week", "${Dates.short(it.key)} · ${Format.duration(it.value)}")
            }
        }
        if (period == "All time") {
            Hairline()
            KeyValueRow("Longest streak", "${d.streak.longest} days")
            d.personal.bestHour?.let {
                Hairline()
                KeyValueRow("Best study hour", "%02d:00".format(it))
            }
        }
    }
}

@Composable
fun DayScreen(container: AppContainer, nav: NavController, date: LocalDate) {
    val dashboard by container.stats.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val d = dashboard
    Page(title = Dates.date(date), onBack = { nav.popBackStack() }) {
        if (d == null) return@Page
        val sessions = d.records.filter { StatsCalculator.dateOf(it.startUtc, d.zone) == date }
        val total = d.daily[date] ?: 0
        SurfaceCard {
            Kicker("Total study time")
            Text(Format.duration(total), style = MaterialTheme.typography.displaySmall)
            Text(
                "${sessions.size} session${if (sessions.size == 1) "" else "s"} · ${sessions.map { it.subject }.distinct().joinToString()}" +
                    " · ${sessions.count { it.hasTimelapse }} timelapses",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalExtraColors.current.slate,
            )
            if (total >= d.settings.streakThresholdMs) {
                Text("Counts toward your streak ✓", style = MaterialTheme.typography.labelLarge, color = LocalExtraColors.current.sage)
            }
        }
        if (sessions.isEmpty()) {
            Text("No sessions started this day.", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            sessions.forEach { ActivityCard(it, d, nav) }
        }
    }
}
