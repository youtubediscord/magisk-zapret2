package com.zapret2.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.topjohnwu.superuser.Shell
import com.zapret2.app.ui.navigation.AppNavHost
import com.zapret2.app.ui.navigation.Screen
import com.zapret2.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Pre-warm root shell so screens don't wait
        lifecycleScope.launch(Dispatchers.IO) {
            try { Shell.getShell() } catch (_: Exception) {}
        }

        setContent {
            ZapretTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Find current screen title
    val currentTitle = remember(currentRoute) {
        val allScreens = Screen.mainScreens + Screen.configScreens + Screen.dataScreens + Screen.systemScreens
        allScreens.find { it.route == currentRoute }?.title
            ?: if (currentRoute?.startsWith("hostlist_content") == true) "Hostlist"
            else "Zapret2"
    }

    // Back press handling
    var backPressedTime by remember { mutableLongStateOf(0L) }
    BackHandler {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            currentRoute != Screen.Control.route -> navController.navigate(Screen.Control.route) {
                popUpTo(Screen.Control.route) { inclusive = true }
            }
            else -> {
                if (backPressedTime + 2000 > System.currentTimeMillis()) {
                    (context as? ComponentActivity)?.finish()
                } else {
                    Toast.makeText(context, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    backPressedTime = System.currentTimeMillis()
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Surface,
                drawerContentColor = TextPrimary
            ) {
                // Header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BackgroundDarker)
                        .padding(24.dp)
                ) {
                    Icon(
                        painter = painterResource(R.mipmap.ic_launcher),
                        contentDescription = null,
                        tint = AccentLightBlue,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Zapret2", fontSize = 20.sp, color = TextPrimary)
                    Text("DPI Bypass Module", fontSize = 13.sp, color = TextSecondary)
                    Text("v${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = TextTertiary)
                }

                Spacer(Modifier.height(8.dp))

                // Main group
                DrawerSectionHeader("Main")
                Screen.mainScreens.forEach { screen ->
                    DrawerItem(screen, currentRoute == screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(Screen.Control.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                        scope.launch { drawerState.close() }
                    }
                }

                // Configuration group
                DrawerSectionHeader("Configuration")
                Screen.configScreens.forEach { screen ->
                    DrawerItem(screen, currentRoute == screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(Screen.Control.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                        scope.launch { drawerState.close() }
                    }
                }

                // Data group
                DrawerSectionHeader("Data")
                Screen.dataScreens.forEach { screen ->
                    DrawerItem(screen, currentRoute == screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(Screen.Control.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                        scope.launch { drawerState.close() }
                    }
                }

                // System group
                DrawerSectionHeader("System")
                Screen.systemScreens.forEach { screen ->
                    DrawerItem(screen, currentRoute == screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(Screen.Control.route) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                        scope.launch { drawerState.close() }
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(currentTitle) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = BackgroundDarker,
                        titleContentColor = TextPrimary,
                        navigationIconContentColor = TextPrimary
                    )
                )
            },
            containerColor = BackgroundDark
        ) { padding ->
            AppNavHost(navController, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = SectionHeaderStyle,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun DrawerItem(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(screen.icon, contentDescription = null) },
        label = { Text(screen.title) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = NavItemBackgroundChecked,
            unselectedContainerColor = Surface,
            selectedIconColor = AccentLightBlue,
            unselectedIconColor = TextQuaternary,
            selectedTextColor = AccentLightBlue,
            unselectedTextColor = TextSecondary
        )
    )
}
