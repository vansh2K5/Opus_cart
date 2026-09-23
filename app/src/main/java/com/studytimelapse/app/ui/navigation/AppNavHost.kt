package com.studytimelapse.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.ViewTimeline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.studytimelapse.app.AppContainer
import com.studytimelapse.app.notifications.Notifier
import com.studytimelapse.app.ui.screens.AchievementsScreen
import com.studytimelapse.app.ui.screens.ActivityScreen
import com.studytimelapse.app.ui.screens.AuthScreen
import com.studytimelapse.app.ui.screens.DayScreen
import com.studytimelapse.app.ui.screens.FriendsScreen
import com.studytimelapse.app.ui.screens.GoalsScreen
import com.studytimelapse.app.ui.screens.HomeScreen
import com.studytimelapse.app.ui.screens.OnboardingScreen
import com.studytimelapse.app.ui.screens.PlayerScreen
import com.studytimelapse.app.ui.screens.ProfileScreen
import com.studytimelapse.app.ui.screens.ScreenOffTestScreen
import com.studytimelapse.app.ui.screens.SessionDetailScreen
import com.studytimelapse.app.ui.screens.SessionSetupScreen
import com.studytimelapse.app.ui.screens.SessionSummaryScreen
import com.studytimelapse.app.ui.screens.SettingsScreen
import com.studytimelapse.app.ui.screens.StorageScreen
import com.studytimelapse.app.ui.screens.StudyModeScreen
import com.studytimelapse.app.ui.screens.TimelapsesScreen
import com.studytimelapse.app.ui.theme.LocalExtraColors
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val ACTIVITY = "activity"
    const val TIMELAPSES = "timelapses"
    const val FRIENDS = "friends"
    const val PROFILE = "profile"
    const val SETUP = "setup"
    const val STUDY = "study?confirm={confirm}"
    const val SUMMARY = "summary/{id}"
    const val SESSION = "session/{id}"
    const val PLAYER = "player/{id}"
    const val DAY = "day/{date}"
    const val SETTINGS = "settings"
    const val GOALS = "goals"
    const val STORAGE = "storage"
    const val SCREEN_OFF_TEST = "screen-off-test"
    const val AUTH = "auth"
    const val ACHIEVEMENTS = "achievements"

    fun study(confirmFinish: Boolean = false) = "study?confirm=$confirmFinish"
    fun summary(id: String) = "summary/$id"
    fun session(id: String) = "session/$id"
    fun player(id: String) = "player/$id"
    fun day(date: LocalDate) = "day/$date"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Home", Icons.Outlined.Home),
    Tab(Routes.ACTIVITY, "Activity", Icons.Outlined.ViewTimeline),
    Tab(Routes.TIMELAPSES, "Timelapses", Icons.Outlined.PlayCircle),
    Tab(Routes.FRIENDS, "Friends", Icons.Outlined.Group),
    Tab(Routes.PROFILE, "Profile", Icons.Outlined.Person),
)

@Composable
fun AppNavHost(container: AppContainer, onboardingDone: Boolean, pendingOpen: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val start = remember { if (onboardingDone) Routes.HOME else Routes.ONBOARDING }
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    // Notification taps.
    val open by pendingOpen.collectAsStateWithLifecycle()
    val live by container.sessionManager.live.collectAsStateWithLifecycle()
    LaunchedEffect(open, live != null) {
        val target = open ?: return@LaunchedEffect
        if (!onboardingDone) return@LaunchedEffect
        when (target) {
            Notifier.OPEN_STUDY, Notifier.OPEN_FINISH -> {
                // The session may still be restoring after a cold start; wait for it.
                if (live == null) return@LaunchedEffect
                nav.navigate(Routes.study(confirmFinish = target == Notifier.OPEN_FINISH)) { launchSingleTop = true }
            }
        }
        pendingOpen.value = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { if (showBar) BottomBar(nav, currentRoute) },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = start,
            // Study mode is edge-to-edge black; every other screen sits above the system bars.
            modifier = Modifier.padding(
                bottom = if (currentRoute?.startsWith("study") == true) 0.dp else padding.calculateBottomPadding(),
            ),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(container, onDone = {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                })
            }
            composable(Routes.HOME) { HomeScreen(container, nav) }
            composable(Routes.ACTIVITY) { ActivityScreen(container, nav) }
            composable(Routes.TIMELAPSES) { TimelapsesScreen(container, nav) }
            composable(Routes.FRIENDS) { FriendsScreen(container, nav) }
            composable(Routes.PROFILE) { ProfileScreen(container, nav) }
            composable(Routes.SETUP) { SessionSetupScreen(container, nav) }
            composable(
                Routes.STUDY,
                arguments = listOf(navArgument("confirm") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                StudyModeScreen(container, nav, confirmFinish = entry.arguments?.getBoolean("confirm") ?: false)
            }
            composable(Routes.SUMMARY) { entry -> SessionSummaryScreen(container, nav, entry.arguments?.getString("id") ?: "") }
            composable(Routes.SESSION) { entry -> SessionDetailScreen(container, nav, entry.arguments?.getString("id") ?: "") }
            composable(Routes.PLAYER) { entry -> PlayerScreen(container, nav, entry.arguments?.getString("id") ?: "") }
            composable(Routes.DAY) { entry ->
                val date = runCatching { LocalDate.parse(entry.arguments?.getString("date")) }.getOrNull() ?: LocalDate.now()
                DayScreen(container, nav, date)
            }
            composable(Routes.SETTINGS) { SettingsScreen(container, nav) }
            composable(Routes.GOALS) { GoalsScreen(container, nav) }
            composable(Routes.STORAGE) { StorageScreen(container, nav) }
            composable(Routes.SCREEN_OFF_TEST) { ScreenOffTestScreen(container, nav) }
            composable(Routes.AUTH) { AuthScreen(container, onDone = { nav.popBackStack() }) }
            composable(Routes.ACHIEVEMENTS) { AchievementsScreen(container, nav) }
        }
    }
}

@Composable
private fun BottomBar(nav: NavHostController, current: String?) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
        tabs.forEach { tab ->
            NavigationBarItem(
                selected = current == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = LocalExtraColors.current.slate,
                    unselectedTextColor = LocalExtraColors.current.slate,
                ),
            )
        }
    }
}

