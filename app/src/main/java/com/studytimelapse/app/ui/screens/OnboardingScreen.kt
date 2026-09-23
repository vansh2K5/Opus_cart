package com.studytimelapse.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.domain.GoalPeriod
import com.studytimelapse.app.ui.components.ChoicePill
import com.studytimelapse.app.ui.components.Kicker
import com.studytimelapse.app.ui.components.PrimaryButton
import com.studytimelapse.app.ui.components.SecondaryButton
import com.studytimelapse.app.ui.components.SurfaceCard
import com.studytimelapse.app.ui.theme.LocalExtraColors
import com.studytimelapse.app.ui.theme.Spacing
import com.studytimelapse.app.worker.SyncWorker
import kotlinx.coroutines.launch

val AVATARS = listOf("📚", "🦉", "🌿", "☕", "🔥", "🌙", "🧠", "✏️")

/** Five short steps: welcome, account, name, goal, permissions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var username by rememberSaveable { mutableStateOf("") }
    var avatar by rememberSaveable { mutableStateOf(AVATARS.first()) }
    var goalMinutes by rememberSaveable { mutableIntStateOf(120) }
    val context = LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        Kicker("Step ${step + 1} of 5")
        when (step) {
            0 -> {
                Spacer(Modifier.height(48.dp))
                Text("Study better.\nSee your progress.", style = MaterialTheme.typography.displaySmall)
                Text(
                    "Put your phone on the desk, press start, and study. We keep the time, the streak and a quiet timelapse of your work.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = LocalExtraColors.current.slate,
                )
                Spacer(Modifier.height(48.dp))
                PrimaryButton("Get started", { step = 1 })
            }
            1 -> {
                Text("Your account", style = MaterialTheme.typography.headlineLarge)
                if (container.auth.available) {
                    Text(
                        "An account lets you compete with a friend. Your timelapses never leave this phone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalExtraColors.current.slate,
                    )
                    AuthForm(container, onSignedIn = { step = 2 })
                    TextButton(onClick = { step = 2 }) { Text("Continue without an account") }
                } else {
                    Text(
                        "This build runs in private, on-device mode. You can add an account later to compete with a friend.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LocalExtraColors.current.slate,
                    )
                    PrimaryButton("Continue", { step = 2 })
                }
            }
            2 -> {
                Text("What should we call you?", style = MaterialTheme.typography.headlineLarge)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it.take(30) },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Kicker("Avatar")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    AVATARS.forEach { a -> ChoicePill(a, a == avatar, { avatar = a }) }
                }
                Spacer(Modifier.height(Spacing.l))
                PrimaryButton("Continue", { step = 3 }, enabled = username.isNotBlank())
            }
            3 -> {
                Text("Daily study goal", style = MaterialTheme.typography.headlineLarge)
                Text("You can change this any time.", style = MaterialTheme.typography.bodyLarge, color = LocalExtraColors.current.slate)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    listOf(30, 60, 90, 120, 180, 240, 300).forEach { m ->
                        ChoicePill(if (m < 60) "${m}m" else if (m % 60 == 0) "${m / 60}h" else "${m / 60}h ${m % 60}m", goalMinutes == m, { goalMinutes = m })
                    }
                }
                Spacer(Modifier.height(Spacing.l))
                PrimaryButton("Continue", { step = 4 })
            }
            else -> {
                PermissionsStep(onFinish = {
                    // App scope: this must complete even though the screen leaves composition.
                    container.appScope.launch {
                        container.stats.setGoal(GoalPeriod.DAY, goalMinutes)
                        container.stats.setGoal(GoalPeriod.WEEK, goalMinutes * 6)
                        container.settings.update { it.copy(onboardingDone = true, username = username.trim(), avatar = avatar) }
                        SyncWorker.enqueue(context)
                    }
                    onDone()
                })
            }
        }
    }
}

@Composable
private fun PermissionsStep(onFinish: () -> Unit) {
    val context = LocalContext.current
    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var camera by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notif by remember { mutableStateOf(!needsNotif || granted(Manifest.permission.POST_NOTIFICATIONS)) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera = it }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it }

    Text("Two permissions", style = MaterialTheme.typography.headlineLarge)
    SurfaceCard {
        Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.s))
        Text("Camera", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used only to record your study timelapse. Videos stay on this phone unless you share them.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalExtraColors.current.slate,
        )
        Spacer(Modifier.height(Spacing.m))
        if (camera) GrantedRow() else SecondaryButton("Allow camera", { cameraLauncher.launch(Manifest.permission.CAMERA) })
    }
    SurfaceCard {
        Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(Spacing.s))
        Text("Notifications", style = MaterialTheme.typography.titleMedium)
        Text(
            "Shows your running session and warns you if recording stops. No spam.",
            style = MaterialTheme.typography.bodyMedium,
            color = LocalExtraColors.current.slate,
        )
        Spacer(Modifier.height(Spacing.m))
        if (notif) GrantedRow() else SecondaryButton("Allow notifications", {
            if (needsNotif) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        })
    }
    Spacer(Modifier.height(Spacing.l))
    PrimaryButton("Start using StudyTimelapse", onFinish)
    if (!camera) {
        Text(
            "Without the camera you can still track study time; timelapses will be off.",
            style = MaterialTheme.typography.bodySmall,
            color = LocalExtraColors.current.slate,
        )
    }
}

@Composable
private fun GrantedRow() {
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = LocalExtraColors.current.sage)
        Spacer(Modifier.padding(4.dp))
        Text("Allowed", style = MaterialTheme.typography.labelLarge, color = LocalExtraColors.current.sage)
    }
}

/** Email/password sign-in and sign-up. Used by onboarding and the Auth screen. */
@Composable
fun AuthForm(container: AppContainer, onSignedIn: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var creating by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        PrimaryButton(
            if (busy) "Please wait…" else if (creating) "Create account" else "Sign in",
            enabled = !busy && email.contains('@') && password.length >= 6,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    val r = if (creating) container.auth.signUp(email, password) else container.auth.signIn(email, password)
                    busy = false
                    r.onSuccess {
                        SyncWorker.enqueue(context)
                        onSignedIn()
                    }.onFailure { message = it.message }
                }
            },
        )
        Row2(
            left = if (creating) "I already have an account" else "Create a new account",
            onLeft = { creating = !creating; message = null },
            right = if (!creating) "Forgot password?" else null,
            onRight = {
                scope.launch {
                    message = container.auth.resetPassword(email).fold(
                        onSuccess = { "Password reset email sent." },
                        onFailure = { it.message },
                    )
                }
            },
        )
    }
}

@Composable
private fun Row2(left: String, onLeft: () -> Unit, right: String?, onRight: () -> Unit) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = onLeft) { Text(left) }
        if (right != null) TextButton(onClick = onRight) { Text(right) }
    }
}

/** Stand-alone sign-in screen (from Friends or Settings). */
@Composable
fun AuthScreen(container: AppContainer, onDone: () -> Unit) {
    Page(title = "Account", onBack = onDone) {
        if (!container.auth.available) {
            Text(
                "Accounts aren't configured in this build. Follow docs/BACKEND_SETUP.md to add Firebase, then rebuild.",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalExtraColors.current.slate,
            )
        } else {
            Text(
                "Only study statistics are shared with your friend. Timelapses and notes stay on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = LocalExtraColors.current.slate,
            )
            AuthForm(container, onSignedIn = onDone)
        }
    }
}
