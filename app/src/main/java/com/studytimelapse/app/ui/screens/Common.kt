package com.studytimelapse.app.ui.screens

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studytimelapse.app.ui.theme.Spacing
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Scrollable page with an optional back arrow and a large title (DESIGN.md editorial header). */
@Composable
fun Page(
    title: String?,
    onBack: (() -> Unit)? = null,
    actions: @Composable () -> Unit = {},
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.section),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.padding(end = Spacing.xs)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
            Box(Modifier.weight(1f))
            actions()
        }
        if (title != null) {
            Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(Spacing.l))
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m), content = content)
    }
}

/** Monotonic "now" that ticks every [periodMs] while the composable is visible. */
@Composable
fun rememberElapsedNow(periodMs: Long = 1000): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(periodMs) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(periodMs - (now % periodMs))
        }
    }
    return now
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

object Dates {
    private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val medium = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    private val dayMonth = DateTimeFormatter.ofPattern("EEE, MMM d")

    fun time(utc: Long, zone: ZoneId): String = Instant.ofEpochMilli(utc).atZone(zone).format(time)
    fun date(utc: Long, zone: ZoneId): String = Instant.ofEpochMilli(utc).atZone(zone).format(medium)
    fun date(d: LocalDate): String = d.format(medium)
    fun short(d: LocalDate): String = d.format(dayMonth)

    /** "Today — 7:42 PM", "Yesterday — 9:10 AM", "Mon, Aug 24 — 6:00 PM". */
    fun relative(utc: Long, zone: ZoneId): String {
        val z = Instant.ofEpochMilli(utc).atZone(zone)
        val today = LocalDate.now(zone)
        val day = when (z.toLocalDate()) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> z.toLocalDate().format(dayMonth)
        }
        return "$day — ${z.format(time)}"
    }
}
