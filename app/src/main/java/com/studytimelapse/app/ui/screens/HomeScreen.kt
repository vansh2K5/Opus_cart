package com.studytimelapse.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.StatsCalculator
import com.studytimelapse.app.ui.components.EmptyState
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.PrimaryButton
import com.studytimelapse.app.ui.components.ProgressRing
import com.studytimelapse.app.ui.components.SecondaryButton
import com.studytimelapse.app.ui.components.StatRow
import com.studytimelapse.app.ui.components.StatTile
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.components.WeekBars
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.LocalReducedMotion
import com.studytimelapse.app.ui.theme.Spacing
import java.time.LocalTime

@Composable
fun HomeScreen(container: AppContainer, nav: NavController) {
    val dashboard by container.stats.dashboard.collectAsStateWithLifecycle(initialValue = null)
    val live by container.sessionManager.live.collectAsStateWithLifecycle()
    val d = dashboard

    Page(
        title = null,
        actions = {
            IconButton(onClick = { nav.navigate(Routes.SETTINGS) }) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        },
    ) {
        val hour = LocalTime.now().hour
        val greeting = when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
        val name = d?.settings?.username?.takeIf { it.isNotBlank() }
        Kicker(d?.today?.let { Dates.short(it) } ?: "")
        Text(if (name != null) "$greeting, $name" else greeting, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(Spacing.s))

        live?.let { l ->
            val now = rememberElapsedNow()
            SurfaceCard(onClick = { nav.navigate(Routes.study()) }, color = MaterialTheme.colorScheme.primaryContainer) {
                Kicker(if (l.interrupted) "Session interrupted" else if (l.running) "Studying now" else "Paused", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("${l.subject} · ${Format.clock(l.studyMsAt(now))}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Tap to return", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }

        if (d == null) return@Page

        // Today + goal ring
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Kicker("Today's study time")
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        Format.duration(d.todayStats.totalMs),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.semantics { contentDescription = "Today: ${Format.spokenDuration(d.todayStats.totalMs)}" },
                    )
                    d.dailyGoal?.let { g ->
                        Text(
                            "Goal ${Format.duration(g.achievedMs)} / ${Format.duration(g.targetMs)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LocalExtraColors.current.slate,
                        )
                    }
                }
                d.dailyGoal?.let { g ->
                    ProgressRing(g.fraction, size = 96.dp, stroke = 9.dp) {
                        Text("${g.percent.coerceAtMost(999)}%", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        SurfaceCard {
            StatRow {
                StreakTile(d.streak.current, Modifier.weight(1f))
                StatTile("This week", Format.duration(d.week.totalMs), Modifier.weight(1f), spoken = Format.spokenDuration(d.week.totalMs))
            }
            Spacer(Modifier.height(Spacing.l))
            StatRow {
                StatTile("Sessions", "${d.week.sessions}", Modifier.weight(1f), caption = "this week")
                StatTile("Personal best", Format.duration(d.personal.longestSessionMs), Modifier.weight(1f), caption = "longest session")
            }
        }

        Spacer(Modifier.height(Spacing.s))
        if (live == null) {
            PrimaryButton("Start study session", { nav.navigate(Routes.SETUP) }, icon = Icons.Rounded.PlayArrow)
        }
        SecondaryButton("View timelapses", {
            nav.navigate(Routes.TIMELAPSES) { launchSingleTop = true }
        }, icon = Icons.Outlined.PlayCircle)

        if (d.records.isEmpty()) {
            EmptyState("Your desk is waiting.", "Your first session starts the streak.")
        } else {
            SurfaceCard {
                Row {
                    Kicker("This week", Modifier.weight(1f))
                    d.weeklyGoal?.let { g ->
                        Text("${Format.duration(g.achievedMs)} / ${Format.duration(g.targetMs)}", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.height(Spacing.m))
                WeekBars(d.daily, StatsCalculator.weekStart(d.today), d.today, d.dailyGoal?.targetMs)
            }
        }
    }
}

@Composable
private fun StreakTile(streak: Int, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    Column(modifier.semantics(mergeDescendants = true) { contentDescription = "Current streak: $streak days" }) {
        Kicker("Current streak")
        Spacer(Modifier.height(Spacing.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔥", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(6.dp))
            AnimatedContent(
                targetState = streak,
                transitionSpec = { if (reduced) fadeIn(snapSpec()) togetherWith fadeOut(snapSpec()) else fadeIn() togetherWith fadeOut() },
                label = "streak",
            ) { value ->
                Text("$value ${if (value == 1) "day" else "days"}", style = MaterialTheme.typography.headlineSmall)
            }
        }
    }
}

private fun <T> snapSpec() = androidx.compose.animation.core.snap<T>()
