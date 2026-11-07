package com.shadowbox.x.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shadowbox.x.di.AppContainer
import com.shadowbox.x.ui.components.ShadowBackground
import com.shadowbox.x.ui.screens.*
import com.shadowbox.x.ui.theme.Navy
import com.shadowbox.x.work.BackupScheduler

@Composable
fun ShadowApp(c: AppContainer) {
    val setUp by c.vault.setUp.collectAsState()
    val unlocked by c.vault.unlocked.collectAsState()
    val recovery by c.pendingRecovery.collectAsState()
    val ctx = LocalContext.current

    LaunchedEffect(unlocked) {
        if (unlocked) {
            val s = c.settings.current()
            if (s.backupDue && s.backupDays > 0) BackupScheduler.runNow(ctx.applicationContext)
        }
    }

    ShadowBackground {
        val r = recovery
        when {
            r != null && unlocked -> RecoveryKeyScreen(r) { c.pendingRecovery.value = null }
            !setUp -> SetupScreen(c)
            !unlocked -> LockScreen(c)
            else -> MainShell(c)
        }
    }
}

private data class NavTab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun MainShell(c: AppContainer) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabs = listOf(
        NavTab("home", "Home", Icons.Filled.Home), NavTab("vault", "Vault", Icons.Filled.Lock),
        NavTab("passwords", "Passwords", Icons.Filled.VpnKey), NavTab("notes", "Notes", Icons.Filled.StickyNote2),
        NavTab("settings", "Settings", Icons.Filled.Settings)
    )
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets.navigationBars,
        bottomBar = {
            if (route in tabs.map { it.route }) {
                NavigationBar(containerColor = Navy.copy(alpha = 0.94f)) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, t.label) }, label = { Text(t.label) }
                        )
                    }
                }
            }
        }
    ) { pad ->
        NavHost(
            nav, startDestination = "home", modifier = Modifier.padding(pad),
            enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 14 } },
            exitTransition = { fadeOut(tween(140)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(160)) }
        ) {
            composable("home") { HomeScreen(c) { nav.navigate(it) } }
            composable("vault") { VaultScreen(c, null, false, nav) }
            composable("folders") { VaultScreen(c, null, true, nav) }
            composable("folder/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                VaultScreen(c, it.arguments?.getString("id"), false, nav)
            }
            composable("viewer/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                ViewerScreen(c, it.arguments?.getString("id") ?: "") { nav.popBackStack() }
            }
            composable("gallery") { GalleryScreen(c, nav) }
            composable("passwords") { PasswordsScreen(c, nav) }
            composable("password/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                PasswordEditScreen(c, it.arguments?.getString("id") ?: "new") { nav.popBackStack() }
            }
            composable("notes") { NotesScreen(c, nav) }
            composable("note/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
                NoteEditScreen(c, it.arguments?.getString("id") ?: "new", nav)
            }
            composable("sync") { SyncScreen(c) { nav.popBackStack() } }
            composable("backup") { BackupScreen(c) { nav.popBackStack() } }
            composable("settings") { SettingsScreen(c) }
        }
    }
}
