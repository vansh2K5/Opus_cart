package com.studytimelapse.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.domain.AchievementType
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.GoalPeriod
import com.studytimelapse.app.ui.components.ChoicePill
import com.studytimelapse.app.ui.components.Hairline
import com.studytimelapse.app.ui.components.KeyValueRow
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.ProgressBar
import com.studytimelapse.app.ui.components.StatRow
import com.studytimelapse.app.ui.components.StatTile
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import com.studytimelapse.app.worker.SyncWorker
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(container: AppContainer, nav: NavController) {
    val dashboard by container.stats.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val achievements by container.achievements.unlocked.collectAsStateWithLifecycle(initialValue = emptyList())
    val timelapseBytes by container.sessions.timelapseBytes.collectAsStateWithLifecycle(initialValue = 0L)
    var editing by remember { mutableStateOf(false) }
    val d = dashboard

    Page(
        title = null,
        actions = {
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
        },
    ) {
        if (d == null) return@Page
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(d.settings.avatar, style = MaterialTheme.typography.headlineLarge) }
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(d.settings.username.ifBlank { "You" }, style = MaterialTheme.typography.headlineMedium)
                Text(container.auth.currentUser?.email ?: "On-device profile", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
            }
            IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit profile") }
        }

        SurfaceCard {
            StatRow {
                StatTile("Current streak", "🔥 ${d.streak.current}", Modifier.weight(1f), caption = "days")
                StatTile("Longest streak", "${d.streak.longest}", Modifier.weight(1f), caption = "days")
            }
            Spacer(Modifier.height(Spacing.l))
            StatRow {
                StatTile("Total", Format.duration(d.allTime.totalMs), Modifier.weight(1f), spoken = Format.spokenDuration(d.allTime.totalMs))
                StatTile("Sessions", "${d.allTime.sessions}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(Spacing.l))
            StatRow {
                StatTile("Timelapses", "${d.allTime.timelapses}", Modifier.weight(1f), caption = com.studytimelapse.app.util.Storage.formatBytes(timelapseBytes))
                StatTile("Days studied", "${d.streak.daysStudied}", Modifier.weight(1f))
            }
        }

        // Goals
        com.studytimelapse.app.ui.components.SectionHeader("Goals", action = "Edit", onAction = { nav.navigate(Routes.GOALS) })
        if (d.goals.isEmpty()) {
            Text("No goals yet.", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
        }
        d.goals.forEach { g ->
            SurfaceCard {
                Row {
                    Kicker(
                        when (g.goal.period) {
                            GoalPeriod.DAY -> "Daily goal"
                            GoalPeriod.WEEK -> "Weekly goal"
                            GoalPeriod.MONTH -> "Monthly goal"
                        },
                        Modifier.weight(1f),
                    )
                    Text("${g.percent}%", style = MaterialTheme.typography.labelLarge)
                }
                Text("${Format.duration(g.achievedMs)} / ${Format.duration(g.targetMs)}", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(Spacing.s))
                ProgressBar(g.fraction)
            }
        }

        // Personal records
        com.studytimelapse.app.ui.components.SectionHeader("Personal records")
        SurfaceCard {
            val p = d.personal
            KeyValueRow("Longest session", Format.duration(p.longestSessionMs))
            Hairline()
            KeyValueRow("Most in a day", Format.duration(p.mostInDayMs) + (p.mostInDayDate?.let { " · ${Dates.short(it)}" } ?: ""))
            Hairline()
            KeyValueRow("Most in a week", Format.duration(p.mostInWeekMs))
            Hairline()
            KeyValueRow("Longest streak", "${d.streak.longest} days")
            Hairline()
            KeyValueRow("Most sessions in a day", "${p.mostSessionsInDay}")
            Hairline()
            KeyValueRow("Top subject", p.topSubject ?: "—")
            p.bestHour?.let {
                Hairline()
                KeyValueRow("Best study hour", "%02d:00".format(it))
            }
        }

        // Achievements
        com.studytimelapse.app.ui.components.SectionHeader("Achievements", action = "All", onAction = { nav.navigate(Routes.ACHIEVEMENTS) })
        if (achievements.isEmpty()) {
            Text("Your first achievement is one session away.", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                achievements.take(6).forEach { a ->
                    val t = runCatching { AchievementType.valueOf(a.type) }.getOrNull() ?: return@forEach
                    ChoicePill("✓ ${t.title}", false, { nav.navigate(Routes.ACHIEVEMENTS) })
                }
            }
        }
    }

    if (editing && d != null) {
        EditProfileDialog(container, d.settings.username, d.settings.avatar) { editing = false }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProfileDialog(container: AppContainer, username: String, avatar: String, onDone: () -> Unit) {
    var name by remember { mutableStateOf(username) }
    var av by remember { mutableStateOf(avatar) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Edit profile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(30) }, label = { Text("Username") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AVATARS.forEach { a -> ChoicePill(a, a == av, { av = a }) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    container.settings.update { it.copy(username = name.trim(), avatar = av) }
                    SyncWorker.enqueue(context)
                }
                onDone()
            }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

@Composable
fun AchievementsScreen(container: AppContainer, nav: NavController) {
    val unlocked by container.achievements.unlocked.collectAsStateWithLifecycle(initialValue = emptyList())
    val zone = java.time.ZoneId.systemDefault()
    val byType = unlocked.associateBy { it.type }
    Page(title = "Achievements", onBack = { nav.popBackStack() }) {
        Text(
            "${byType.size} of ${AchievementType.entries.size} unlocked",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalExtraColors.current.slate,
        )
        AchievementType.entries.forEach { t ->
            val a = byType[t.name]
            SurfaceCard(
                modifier = Modifier
                    .alpha(if (a != null) 1f else 0.55f)
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${t.title}: ${t.description} ${if (a != null) "Unlocked" else "Locked"}"
                    },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (a != null) "✓" else "○", style = MaterialTheme.typography.headlineSmall, color = if (a != null) MaterialTheme.colorScheme.primary else LocalExtraColors.current.slate)
                    Spacer(Modifier.width(Spacing.l))
                    Column(Modifier.weight(1f)) {
                        Text(t.title, style = MaterialTheme.typography.titleMedium)
                        Text(t.description, style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
                        a?.let { Text("Unlocked ${Dates.date(it.unlockedAt, zone)}", style = MaterialTheme.typography.labelSmall, color = LocalExtraColors.current.slate) }
                    }
                }
            }
        }
    }
}

@Composable
fun GoalsScreen(container: AppContainer, nav: NavController) {
    val goals by container.stats.goals.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    Page(title = "Goals", onBack = { nav.popBackStack() }) {
        Text(
            "Set any combination of daily, weekly and monthly goals. Only actual study time counts, not pauses.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalExtraColors.current.slate,
        )
        GoalPeriod.entries.forEach { period ->
            val goal = goals.firstOrNull { it.period == period }
            val max = when (period) {
                GoalPeriod.DAY -> 12 * 60
                GoalPeriod.WEEK -> 60 * 60
                GoalPeriod.MONTH -> 200 * 60
            }
            val step = when (period) {
                GoalPeriod.DAY -> 15
                GoalPeriod.WEEK -> 60
                GoalPeriod.MONTH -> 300
            }
            var value by remember(goal?.targetMinutes) { mutableFloatStateOf((goal?.targetMinutes ?: (max / 6)).toFloat()) }
            SurfaceCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (period) {
                            GoalPeriod.DAY -> "Daily"
                            GoalPeriod.WEEK -> "Weekly"
                            GoalPeriod.MONTH -> "Monthly"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (goal != null) {
                        TextButton(onClick = { scope.launch { container.stats.removeGoal(goal.id) } }) { Text("Remove") }
                    }
                }
                Text(
                    if (goal == null) "Not set" else Format.duration(value.toLong() * 60_000L),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Slider(
                    value = value,
                    onValueChange = { value = (Math.round(it / step) * step).toFloat().coerceAtLeast(step.toFloat()) },
                    onValueChangeFinished = { scope.launch { container.stats.setGoal(period, value.toInt()) } },
                    valueRange = step.toFloat()..max.toFloat(),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${period.name.lowercase()} goal" },
                )
                if (goal == null) {
                    TextButton(onClick = { scope.launch { container.stats.setGoal(period, value.toInt()) } }) {
                        Text("Set ${Format.duration(value.toLong() * 60_000L)} goal")
                    }
                }
            }
        }
        Hairline()
        SettingsLinkRow("Streak minimum", "A day counts toward your streak after this much study") { nav.navigate(Routes.SETTINGS) }
    }
}

@Composable
fun SettingsLinkRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    SurfaceCard(onClick = onClick) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate) }
    }
}
