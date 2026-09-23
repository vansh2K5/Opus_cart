package com.studytimelapse.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.studytimelapse.app.domain.Format
import com.studytimelapse.app.domain.StatsCalculator
import com.studytimelapse.app.domain.SubjectTime
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * GitHub-style study heatmap: one column per week (Monday on top), newest week on the right.
 * Intensity is always paired with the spoken duration for screen readers, never colour alone.
 */
@Composable
fun StudyHeatmap(
    daily: Map<LocalDate, Long>,
    today: LocalDate,
    weeks: Int = 20,
    onDayClick: (LocalDate) -> Unit,
) {
    val heat = LocalExtraColors.current.heat
    val start = StatsCalculator.weekStart(today).minusWeeks((weeks - 1).toLong())
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
    val cell = 16.dp
    val gap = 4.dp
    Column {
        Row {
            Column(Modifier.padding(end = Spacing.s), verticalArrangement = Arrangement.spacedBy(gap)) {
                DayOfWeek.entries.forEach { dow ->
                    Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                        if (dow.value % 2 == 1) {
                            Text(
                                dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocalExtraColors.current.slate,
                            )
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (w in 0 until weeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        for (d in 0 until 7) {
                            val date = start.plusWeeks(w.toLong()).plusDays(d.toLong())
                            val ms = daily[date] ?: 0
                            val future = date.isAfter(today)
                            val level = StatsCalculator.heatLevel(ms)
                            Box(
                                Modifier
                                    .size(cell)
                                    .background(if (future) heat[0].copy(alpha = 0.35f) else heat[level], RoundedCornerShape(4.dp))
                                    .then(if (date == today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onBackground, RoundedCornerShape(4.dp)) else Modifier)
                                    .then(if (!future) Modifier.clickable { onDayClick(date) } else Modifier)
                                    .semantics {
                                        contentDescription = "${date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))}: " +
                                            if (ms > 0) Format.spokenDuration(ms) else "no study"
                                    },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(Spacing.m))
        HeatLegend()
    }
}

@Composable
fun HeatLegend() {
    val heat = LocalExtraColors.current.heat
    val labels = listOf("0h", "<1h", "1h", "2h", "3h", "4h+")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            Box(Modifier.size(12.dp).background(heat[i], RoundedCornerShape(3.dp)))
            Text(label, style = MaterialTheme.typography.labelSmall, color = LocalExtraColors.current.slate)
            Spacer(Modifier.width(2.dp))
        }
    }
}

/** Month grid calendar. Days show their study time; tap opens the day. */
@Composable
fun MonthCalendar(
    month: YearMonth,
    daily: Map<LocalDate, Long>,
    today: LocalDate,
    thresholdMs: Long,
    onDayClick: (LocalDate) -> Unit,
) {
    val heat = LocalExtraColors.current.heat
    val first = month.atDay(1)
    val offset = first.dayOfWeek.value - 1 // Monday first
    val days = month.lengthOfMonth()
    val rows = (offset + days + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach {
                Text(
                    it.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalExtraColors.current.slate,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 7) {
                    val dayNum = r * 7 + c - offset + 1
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        if (dayNum in 1..days) {
                            val date = month.atDay(dayNum)
                            val ms = daily[date] ?: 0
                            val level = StatsCalculator.heatLevel(ms)
                            val qualifies = ms >= thresholdMs
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .background(heat[level], RoundedCornerShape(10.dp))
                                    .then(if (date == today) Modifier.border(1.5.dp, MaterialTheme.colorScheme.onBackground, RoundedCornerShape(10.dp)) else Modifier)
                                    .clickable(enabled = !date.isAfter(today)) { onDayClick(date) }
                                    .semantics {
                                        contentDescription = "${date.format(DateTimeFormatter.ofPattern("MMMM d"))}: " +
                                            (if (ms > 0) Format.spokenDuration(ms) else "no study") +
                                            if (qualifies) ", counts toward streak" else ""
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "$dayNum",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (level >= 3) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onBackground,
                                )
                                // A dot marks streak days so meaning never relies on colour alone.
                                if (qualifies) {
                                    Box(
                                        Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 4.dp)
                                            .size(4.dp)
                                            .background(
                                                if (level >= 3) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                                CircleShape,
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Horizontal bars for subject distribution. */
@Composable
fun SubjectBars(subjects: List<SubjectTime>, max: Int = 6) {
    val top = subjects.take(max)
    val total = top.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        top.forEach { s ->
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Row(Modifier.fillMaxWidth()) {
                    Text(s.subject, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(Format.duration(s.ms), style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(6.dp))
                ProgressBar(s.ms.toFloat() / total, height = 6.dp)
            }
        }
    }
}

/** Seven vertical bars for the current week. */
@Composable
fun WeekBars(daily: Map<LocalDate, Long>, weekStart: LocalDate, today: LocalDate, goalMs: Long?) {
    val values = (0..6).map { daily[weekStart.plusDays(it.toLong())] ?: 0L }
    val max = maxOf(values.maxOrNull() ?: 0L, goalMs ?: 0L, 3_600_000L)
    Row(
        Modifier.fillMaxWidth().height(140.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEachIndexed { i, v ->
            val date = weekStart.plusDays(i.toLong())
            Column(
                Modifier.weight(1f).semantics(mergeDescendants = true) {
                    contentDescription = "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}: ${Format.spokenDuration(v)}"
                },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((v.toFloat() / max).coerceIn(0.02f, 1f))
                            .background(
                                if (date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                RoundedCornerShape(8.dp),
                            ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalExtraColors.current.slate,
                )
            }
        }
    }
}
