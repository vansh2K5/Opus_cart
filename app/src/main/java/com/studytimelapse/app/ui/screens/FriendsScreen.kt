package com.studytimelapse.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.data.remote.Challenge
import com.studytimelapse.app.data.remote.Friendship
import com.studytimelapse.app.data.remote.RemoteProfile
import com.studytimelapse.app.domain.ChallengeCalculator
import com.studytimelapse.app.domain.ChallengeType
import com.studytimelapse.app.domain.CompetitorStats
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.GoalPeriod
import com.studytimelapse.app.domain.LeaderboardCalculator
import com.studytimelapse.app.domain.LeaderboardMetric
import com.studytimelapse.app.domain.StatsCalculator
import com.studytimelapse.app.domain.StudyRecord
import com.studytimelapse.app.ui.components.ChoicePill
import com.studytimelapse.app.ui.components.EmptyState
import com.studytimelapse.app.ui.components.Hairline
import com.studytimelapse.app.ui.components.KeyValueRow
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.PrimaryButton
import com.studytimelapse.app.ui.components.ProgressBar
import com.studytimelapse.app.ui.components.SecondaryButton
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.navigation.Routes
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

private const val FRIEND_HISTORY_DAYS = 400L

@Composable
fun FriendsScreen(container: AppContainer, nav: NavController) {
    val user by container.auth.user.collectAsStateWithLifecycle(initialValue = container.auth.currentUser)
    Page(title = "Friends") {
        when {
            !container.auth.available -> EmptyState(
                "Add a friend to start competing.",
                "This build runs in private, on-device mode. Add Firebase (docs/BACKEND_SETUP.md) to enable accounts and friends.",
            )
            user == null -> EmptyState(
                "Add a friend to start competing.",
                "Create an account to compare study time with one friend. Only statistics are shared — never your timelapses or notes.",
                action = "Sign in or create account",
                onAction = { nav.navigate(Routes.AUTH) },
            )
            else -> SignedInFriends(container, user!!.uid)
        }
    }
}

@Composable
private fun SignedInFriends(container: AppContainer, me: String) {
    val social = container.social
    val friendships by remember(me) { social.observeFriendships() }.collectAsStateWithLifecycle(initialValue = null)
    val list = friendships ?: return
    val friend = list.firstOrNull { it.accepted }
    if (friend == null) {
        NoFriendYet(container, me, list)
    } else {
        FriendDashboard(container, me, friend)
    }
}

@Composable
private fun NoFriendYet(container: AppContainer, me: String, friendships: List<Friendship>) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var code by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)

    friendships.filter { it.receiver == me }.forEach { f ->
        SurfaceCard(color = MaterialTheme.colorScheme.primaryContainer) {
            Text("${f.requesterName} wants to be study friends", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                TextButton(onClick = { scope.launch { runCatching { container.social.acceptFriend(f.id) } } }) { Text("Accept") }
                TextButton(onClick = { scope.launch { runCatching { container.social.removeFriend(f.id) } } }) { Text("Decline") }
            }
        }
    }
    friendships.filter { it.requester == me }.forEach { f ->
        SurfaceCard {
            Text("Waiting for your friend to accept…", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { scope.launch { runCatching { container.social.removeFriend(f.id) } } }) { Text("Cancel request") }
        }
    }

    Text("Add a friend to start competing.", style = MaterialTheme.typography.titleLarge)
    SurfaceCard {
        Kicker("Invite a friend")
        Spacer(Modifier.height(Spacing.s))
        if (code == null) {
            Text("Create a code and send it to your friend.", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
            Spacer(Modifier.height(Spacing.m))
            SecondaryButton(if (busy) "Creating…" else "Create invite code", {
                busy = true
                scope.launch {
                    runCatching { container.social.createInviteCode() }
                        .onSuccess { code = it }
                        .onFailure { message = it.message }
                    busy = false
                }
            }, enabled = !busy)
        } else {
            Text(
                code!!,
                style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Invite code ${code!!.toCharArray().joinToString(" ")}" },
                textAlign = TextAlign.Center,
            )
            Text("Valid for 7 days.", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.m))
            SecondaryButton("Send code", {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "Study with me on StudyTimelapse! My invite code: $code")
                context.startActivity(Intent.createChooser(send, "Send invite code"))
            })
        }
    }
    SurfaceCard {
        Kicker("Have a code?")
        Spacer(Modifier.height(Spacing.s))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(6) },
            label = { Text("6-character code") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        )
        Spacer(Modifier.height(Spacing.m))
        PrimaryButton(if (busy) "Adding…" else "Add friend", {
            busy = true
            message = null
            scope.launch {
                runCatching { container.social.redeemInviteCode(input, settings?.username ?: "Friend") }
                    .onSuccess { message = "Request sent. Your friend just needs to accept it." ; input = "" }
                    .onFailure { message = it.message }
                busy = false
            }
        }, enabled = !busy && input.length == 6)
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate) }
}

@Composable
private fun FriendDashboard(container: AppContainer, me: String, friendship: Friendship) {
    val social = container.social
    val friendUid = friendship.other(me)
    val since = remember { System.currentTimeMillis() - FRIEND_HISTORY_DAYS * 86_400_000L }
    val profile by remember(friendUid) { social.observeProfile(friendUid) }.collectAsStateWithLifecycle(initialValue = null)
    val friendRecords by remember(friendUid) { social.observeSessions(friendUid, since) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val challenges by remember(me) { social.observeChallenges() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val myRecords by container.sessions.records.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val goals by container.stats.goals.collectAsStateWithLifecycle(initialValue = emptyList())
    val s = settings ?: return
    val p = profile
    val name = p?.username ?: "Your friend"
    val myZone = s.zone
    val friendZone = p?.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: myZone
    val today = LocalDate.now(myZone)
    val threshold = s.streakThresholdMs
    val myWeeklyGoal = goals.firstOrNull { it.period == GoalPeriod.WEEK }?.targetMinutes

    val meStats = remember(myRecords, myWeeklyGoal, today) {
        LeaderboardCalculator.weekly(myRecords, myZone, today, threshold, myWeeklyGoal)
    }
    val friendStats = remember(friendRecords, p?.weeklyGoalMinutes, today) {
        LeaderboardCalculator.weekly(friendRecords, friendZone, LocalDate.now(friendZone), threshold, p?.weeklyGoalMinutes)
    }

    LiveStatus(p)
    Leaderboard(meStats, friendStats, name)
    ChallengesCard(container, challenges, myRecords, friendRecords, myZone, friendZone, today, threshold, friendUid, name)
    FriendProfileCard(name, p, friendRecords, friendZone, threshold)
    FriendActivity(name, friendRecords, friendZone, friendStats)

    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { confirmRemove = true }) { Text("Remove friend", color = MaterialTheme.colorScheme.error) }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove $name?",
            text = "You'll stop seeing each other's statistics. Your own history is not affected.",
            confirm = "Remove",
            destructive = true,
            onDismiss = { confirmRemove = false },
            onConfirm = {
                confirmRemove = false
                scope.launch { runCatching { social.removeFriend(friendship.id) } }
            },
        )
    }
}

@Composable
private fun LiveStatus(p: RemoteProfile?) {
    val started = p?.liveStartedAt ?: return
    val subject = p.liveSubject ?: return
    // Ignore stale "studying now" flags (e.g. the friend's phone died mid-session).
    if (System.currentTimeMillis() - started > 12 * 3_600_000L) return
    SurfaceCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Kicker("Studying now", color = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            "${p.username} started ${p.liveTargetMinutes?.let { "a ${Format.duration(it * 60_000L)} " } ?: "a "}session · $subject",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text("since ${Dates.time(started, ZoneId.systemDefault())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Leaderboard(me: CompetitorStats, friend: CompetitorStats, friendName: String) {
    var metric by rememberSaveable { mutableStateOf(LeaderboardMetric.STUDY_TIME) }
    SurfaceCard {
        Kicker("This week")
        Spacer(Modifier.height(Spacing.s))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            ChoicePill("Study time", metric == LeaderboardMetric.STUDY_TIME, { metric = LeaderboardMetric.STUDY_TIME })
            ChoicePill("Sessions", metric == LeaderboardMetric.SESSIONS, { metric = LeaderboardMetric.SESSIONS })
            ChoicePill("Streak", metric == LeaderboardMetric.STREAK, { metric = LeaderboardMetric.STREAK })
        }
        Spacer(Modifier.height(Spacing.l))
        val lead = LeaderboardCalculator.lead(me, friend, metric)
        val headline = when {
            lead == 0L -> "You're tied with $friendName."
            metric == LeaderboardMetric.STUDY_TIME ->
                if (lead > 0) "You're ${Format.duration(lead)} ahead of $friendName." else "$friendName is ${Format.duration(-lead)} ahead."
            metric == LeaderboardMetric.SESSIONS ->
                if (lead > 0) "You've done $lead more session${if (lead == 1L) "" else "s"}." else "$friendName has ${-lead} more session${if (lead == -1L) "" else "s"}."
            else -> if (lead > 0) "Your streak is $lead day${if (lead == 1L) "" else "s"} longer." else "$friendName's streak is ${-lead} day${if (lead == -1L) "" else "s"} longer."
        }
        Text(headline, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.m))
        Row(Modifier.fillMaxWidth()) {
            Text("", Modifier.weight(1.4f))
            Text("Me", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End)
            Text(friendName, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, maxLines = 1)
        }
        Hairline(Modifier.padding(vertical = Spacing.s))
        CompareRow("Study time", Format.duration(me.studyMs), Format.duration(friend.studyMs), metric == LeaderboardMetric.STUDY_TIME)
        CompareRow("Sessions", "${me.sessions}", "${friend.sessions}", metric == LeaderboardMetric.SESSIONS)
        CompareRow("Current streak", "${me.streak}", "${friend.streak}", metric == LeaderboardMetric.STREAK)
        CompareRow("Weekly goal", me.weeklyGoalPercent?.let { "$it%" } ?: "—", friend.weeklyGoalPercent?.let { "$it%" } ?: "—", false)
        Spacer(Modifier.height(Spacing.s))
        Text(
            "Sessions under 10 minutes don't count as sessions, and overlapping time is never counted twice.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalExtraColors.current.slate,
        )
    }
}

@Composable
private fun CompareRow(label: String, me: String, friend: String, highlight: Boolean) {
    val style = if (highlight) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1.4f), style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
        Text(me, Modifier.weight(1f), style = style, textAlign = TextAlign.End)
        Text(friend, Modifier.weight(1f), style = style, textAlign = TextAlign.End)
    }
}

@Composable
private fun ChallengesCard(
    container: AppContainer,
    challenges: List<Challenge>,
    mine: List<StudyRecord>,
    theirs: List<StudyRecord>,
    myZone: ZoneId,
    friendZone: ZoneId,
    today: LocalDate,
    threshold: Long,
    friendUid: String,
    friendName: String,
) {
    var creating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val active = challenges.filter { runCatching { !LocalDate.parse(it.endDate).isBefore(today.minusDays(7)) }.getOrDefault(false) }
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Kicker("Challenges", Modifier.weight(1f))
            TextButton(onClick = { creating = true }) { Text("New") }
        }
        if (active.isEmpty()) {
            Text("No challenge yet. Keep it simple: one goal, one week.", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
        }
        active.forEach { c ->
            val start = LocalDate.parse(c.startDate)
            val end = LocalDate.parse(c.endDate)
            val progress = ChallengeCalculator.progress(c.type, c.targetMinutes, mine, theirs, myZone, friendZone, start, end, today, threshold)
            Spacer(Modifier.height(Spacing.m))
            Text(
                when (c.type) {
                    ChallengeType.WEEKLY_HOURS -> "Study ${Format.duration(c.targetMinutes * 60_000L)} this week"
                    ChallengeType.LONGEST_SESSION -> "Longest session (target ${Format.duration(c.targetMinutes * 60_000L)})"
                    ChallengeType.CONSISTENCY_7 -> "7-day consistency"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text("${Dates.short(start)} – ${Dates.short(end)}${if (end.isBefore(today)) " · ended" else ""}", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
            fun show(v: Long) = if (c.type == ChallengeType.CONSISTENCY_7) "$v / 7 days" else Format.duration(v)
            Spacer(Modifier.height(Spacing.s))
            ChallengeBar("Me", show(progress.meValue), progress.meValue, progress.target, progress.meDone)
            ChallengeBar(friendName, show(progress.friendValue), progress.friendValue, progress.target, progress.friendDone)
            TextButton(onClick = { scope.launch { runCatching { container.social.deleteChallenge(c.id) } } }) {
                Text("Remove", color = LocalExtraColors.current.slate)
            }
        }
    }
    if (creating) {
        NewChallengeDialog(
            onDismiss = { creating = false },
            onCreate = { type, minutes ->
                creating = false
                val start = StatsCalculator.weekStart(today)
                scope.launch {
                    runCatching { container.social.createChallenge(friendUid, type, minutes, start.toString(), start.plusDays(6).toString()) }
                }
            },
        )
    }
}

@Composable
private fun ChallengeBar(who: String, value: String, v: Long, target: Long, done: Boolean) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(who, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value + if (done) " ✓" else "", style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.height(4.dp))
        ProgressBar(if (target > 0) v.toFloat() / target else 0f, height = 6.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewChallengeDialog(onDismiss: () -> Unit, onCreate: (ChallengeType, Int) -> Unit) {
    var type by remember { mutableStateOf(ChallengeType.WEEKLY_HOURS) }
    var hours by remember { mutableIntStateOf(15) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New weekly challenge") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    ChallengeType.entries.forEach { t -> ChoicePill(t.title, type == t, { type = t }) }
                }
                if (type != ChallengeType.CONSISTENCY_7) {
                    Kicker("Target")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                        val options = if (type == ChallengeType.WEEKLY_HOURS) listOf(5, 10, 15, 20, 30) else listOf(1, 2, 3, 4)
                        options.forEach { h -> ChoicePill("${h}h", hours == h, { hours = h }) }
                    }
                } else {
                    Text("Study at least your streak minimum every day this week.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(type, hours * 60) }) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun FriendProfileCard(name: String, p: RemoteProfile?, records: List<StudyRecord>, zone: ZoneId, threshold: Long) {
    val today = LocalDate.now(zone)
    val daily = remember(records) { StatsCalculator.dailyTotals(LeaderboardCalculator.sanitize(records), zone) }
    val streak = StatsCalculator.streak(daily, today, threshold)
    val clean = remember(records) { LeaderboardCalculator.sanitize(records) }
    val weekStart = StatsCalculator.weekStart(today)
    val week = StatsCalculator.periodStats(clean, zone, weekStart, weekStart.plusDays(6), today, threshold)
    val monthStart = StatsCalculator.monthStart(today)
    val month = StatsCalculator.periodStats(clean, zone, monthStart, monthStart.withDayOfMonth(monthStart.lengthOfMonth()), today, threshold)
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p?.avatar ?: "📚", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.padding(6.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleLarge)
                Text("Statistics only · timelapses stay private", style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
            }
        }
        Spacer(Modifier.height(Spacing.m))
        KeyValueRow("Current streak", "${streak.current} days")
        Hairline()
        KeyValueRow("Longest streak", "${streak.longest} days")
        Hairline()
        KeyValueRow("This week", Format.duration(week.totalMs))
        Hairline()
        KeyValueRow("This month", Format.duration(month.totalMs))
        Hairline()
        KeyValueRow("Total (last 400 days)", Format.duration(clean.sumOf { it.studyMs }))
        Hairline()
        KeyValueRow("Sessions", "${clean.size}")
        Hairline()
        KeyValueRow("Personal best", Format.duration(clean.maxOfOrNull { it.studyMs } ?: 0))
        if (!p?.achievements.isNullOrEmpty()) {
            Hairline()
            KeyValueRow("Achievements", "${p!!.achievements.size}")
        }
    }
}

/** Lightweight accountability updates, not a feed to scroll forever: at most 8 items. */
@Composable
private fun FriendActivity(name: String, records: List<StudyRecord>, zone: ZoneId, stats: CompetitorStats) {
    val recent = records.sortedByDescending { it.endUtc }.take(6)
    SurfaceCard {
        Kicker("$name's activity")
        Spacer(Modifier.height(Spacing.s))
        if (stats.streak >= 3) Text("🔥 $name is on a ${stats.streak}-day streak", style = MaterialTheme.typography.bodyLarge)
        if (stats.studyMs >= 3_600_000L) Text("$name has studied ${Format.duration(stats.studyMs)} this week", style = MaterialTheme.typography.bodyLarge)
        if (recent.isEmpty()) {
            Text("No sessions yet.", style = MaterialTheme.typography.bodyMedium, color = LocalExtraColors.current.slate)
        }
        recent.forEach { r ->
            Spacer(Modifier.height(Spacing.s))
            Text("$name studied ${r.subject} for ${Format.duration(r.studyMs)}", style = MaterialTheme.typography.bodyLarge)
            Text(Dates.relative(r.endUtc, ZoneId.systemDefault()), style = MaterialTheme.typography.bodySmall, color = LocalExtraColors.current.slate)
        }
    }
}
