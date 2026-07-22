package com.zapret2.app

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zapret2.app.ui.components.LocalReducedMotionEnabled
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.AppSnackbarMessage
import com.zapret2.app.ui.UiText
import com.zapret2.app.ui.navigation.AppNavHost
import com.zapret2.app.ui.navigation.AppBackAction
import com.zapret2.app.ui.navigation.Screen
import com.zapret2.app.ui.navigation.resolveAppBackAction
import com.zapret2.app.ui.navigation.popDetailOrOpenHostlists
import com.zapret2.app.ui.navigation.popOrOpenControl
import com.zapret2.app.ui.theme.MotionTokens
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.ui.theme.ZapretTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZapretTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
internal fun MainScreen(
    appContent: @Composable (NavHostController, Boolean, Modifier) -> Unit =
        { controller, reducedMotion, modifier ->
            AppNavHost(
                navController = controller,
                modifier = modifier,
                reduceMotion = reducedMotion,
            )
        },
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reduceMotion = LocalReducedMotionEnabled.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val navigationSelectionRoute = Screen.navigationSelectionForRoute(currentRoute)
    val currentTitle = stringResource(Screen.titleForRoute(currentRoute))
    val snackbarHostState = remember { SnackbarHostState() }
    var snackbarMessage by remember { mutableStateOf<AppSnackbarMessage?>(null) }

    AppSnackbarEffect(
        message = snackbarMessage,
        hostState = snackbarHostState,
        onConsumed = { consumed -> if (snackbarMessage === consumed) snackbarMessage = null },
    )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactShell = maxWidth < SizeTokens.MediumBreakpoint
        var backPressedTime by remember(currentRoute, drawerState.currentValue, compactShell) {
            mutableLongStateOf(0L)
        }
        LaunchedEffect(currentRoute, compactShell, drawerState.currentValue) {
            snackbarMessage = null
            snackbarHostState.currentSnackbarData?.dismiss()
        }
        LaunchedEffect(compactShell) {
            if (!compactShell) drawerState.close()
        }
        BackHandler {
            val now = SystemClock.elapsedRealtime()
            when (
                resolveAppBackAction(
                    drawerOpen = compactShell && drawerState.isOpen,
                    currentRoute = currentRoute,
                    nowMillis = now,
                    lastBackMillis = backPressedTime,
                )
            ) {
                AppBackAction.CLOSE_NAVIGATION -> {
                    backPressedTime = 0L
                    scope.launch { drawerState.close() }
                }
                AppBackAction.POP_DETAIL -> {
                    backPressedTime = 0L
                    navController.popDetailOrOpenHostlists()
                }
                AppBackAction.NAVIGATE_CONTROL -> {
                    backPressedTime = 0L
                    navController.popOrOpenControl()
                }
                AppBackAction.PROMPT_EXIT -> {
                    snackbarMessage = AppSnackbarMessage(
                        sequence = now,
                        text = UiText.Resource(R.string.back_again_exit),
                    )
                    backPressedTime = now
                }
                AppBackAction.EXIT -> (context as? ComponentActivity)?.finish()
            }
        }

        if (!compactShell) {
            RailAppShell(
                navController = navController,
                currentRoute = navigationSelectionRoute,
                currentTitle = currentTitle,
                reduceMotion = reduceMotion,
                railExpanded = maxWidth >= SizeTokens.ExpandedBreakpoint,
                snackbarHostState = snackbarHostState,
                appContent = appContent,
            )
        } else {
            ModalNavigationDrawer(
                modifier = Modifier.testTag("compact_app_shell"),
                drawerState = drawerState,
                drawerContent = {
                    AppDrawer(
                        currentRoute = navigationSelectionRoute,
                        onNavigate = { screen ->
                            navController.navigateTo(screen)
                            scope.launch { drawerState.close() }
                        },
                    )
                },
            ) {
                AppScaffold(
                    navController = navController,
                    currentTitle = currentTitle,
                    reduceMotion = reduceMotion,
                    snackbarHostState = snackbarHostState,
                    showNavigationIcon = true,
                    onNavigationClick = { scope.launch { drawerState.open() } },
                    appContent = appContent,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RailAppShell(
    navController: NavHostController,
    currentRoute: String?,
    currentTitle: String,
    reduceMotion: Boolean,
    railExpanded: Boolean,
    snackbarHostState: SnackbarHostState,
    appContent: @Composable (NavHostController, Boolean, Modifier) -> Unit,
) {
    key(railExpanded) {
        var showMediumOverflow by rememberSaveable { mutableStateOf(false) }
        val railState = rememberWideNavigationRailState(
            initialValue = if (railExpanded) {
                WideNavigationRailValue.Expanded
            } else {
                WideNavigationRailValue.Collapsed
            },
        )
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxSize()
                .testTag(if (railExpanded) "expanded_app_shell" else "medium_app_shell"),
        ) {
            WideNavigationRail(
                state = railState,
                colors = androidx.compose.material3.WideNavigationRailDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                ),
                header = {
                    NavigationBrand(
                        compact = true,
                        showText = railExpanded,
                    )
                },
            ) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    if (railExpanded) {
                        Screen.navigationGroups.forEach { group ->
                            DrawerSectionHeader(stringResource(group.titleRes))
                            group.screens.forEach { screen ->
                                RailScreenItem(
                                    screen = screen,
                                    railExpanded = true,
                                    selected = currentRoute == screen.route,
                                    onClick = { navController.navigateTo(screen) },
                                )
                            }
                        }
                    } else {
                        Screen.mediumRailScreens.forEach { screen ->
                            RailScreenItem(
                                screen = screen,
                                railExpanded = false,
                                selected = currentRoute == screen.route,
                                onClick = { navController.navigateTo(screen) },
                            )
                        }
                        WideNavigationRailItem(
                            railExpanded = false,
                            icon = {
                                Icon(
                                    Icons.Default.MoreHoriz,
                                    contentDescription = stringResource(R.string.nav_more),
                                )
                            },
                            label = { Text(stringResource(R.string.nav_more)) },
                            selected = Screen.mediumOverflowScreens.any { it.route == currentRoute },
                            onClick = { showMediumOverflow = true },
                        )
                    }
                }
            }
            AppScaffold(
                navController = navController,
                currentTitle = currentTitle,
                reduceMotion = reduceMotion,
                snackbarHostState = snackbarHostState,
                showNavigationIcon = false,
                onNavigationClick = {},
                modifier = Modifier.weight(1f),
                appContent = appContent,
            )
        }
        if (!railExpanded && showMediumOverflow) {
            MediumNavigationSheet(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    showMediumOverflow = false
                    navController.navigateTo(screen)
                },
                onDismiss = { showMediumOverflow = false },
            )
        }
    }
}

@Composable
private fun RailScreenItem(
    screen: Screen,
    railExpanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    WideNavigationRailItem(
        railExpanded = railExpanded,
        icon = {
            Icon(
                screen.icon,
                contentDescription = if (railExpanded) null else stringResource(screen.titleRes),
            )
        },
        label = { Text(stringResource(screen.titleRes)) },
        selected = selected,
        onClick = onClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediumNavigationSheet(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SizeTokens.SheetContentMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SpacingTokens.Medium),
        ) {
            Text(
                text = stringResource(R.string.nav_more_destinations),
                style = MaterialTheme.typography.headlineSmallEmphasized,
                modifier = Modifier
                    .semantics { heading() }
                    .padding(horizontal = SpacingTokens.Large),
            )
            Screen.navigationGroups.forEach { group ->
                val overflowScreens = group.screens.filter(Screen.mediumOverflowScreens::contains)
                if (overflowScreens.isNotEmpty()) {
                    DrawerSectionHeader(stringResource(group.titleRes))
                    overflowScreens.forEach { screen ->
                        DrawerItem(
                            screen = screen,
                            selected = currentRoute == screen.route,
                            onClick = { onNavigate(screen) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(SpacingTokens.ExtraLarge))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AppScaffold(
    navController: NavHostController,
    currentTitle: String,
    reduceMotion: Boolean,
    snackbarHostState: SnackbarHostState,
    showNavigationIcon: Boolean,
    onNavigationClick: () -> Unit,
    modifier: Modifier = Modifier,
    appContent: @Composable (NavHostController, Boolean, Modifier) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.testTag("app_top_bar"),
                title = {
                    AnimatedContent(
                        targetState = currentTitle,
                        transitionSpec = {
                            if (reduceMotion) {
                                fadeIn(tween(MotionTokens.DurationImmediate)) togetherWith
                                    fadeOut(tween(MotionTokens.DurationImmediate))
                            } else {
                                (fadeIn(tween(MotionTokens.DurationMedium)) + slideInVertically { it / 3 }) togetherWith
                                    (fadeOut(tween(MotionTokens.DurationShort)) + slideOutVertically { -it / 3 })
                            }
                        },
                        label = "screen title",
                    ) { title ->
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                },
                subtitle = {
                    Text(
                        text = stringResource(R.string.app_subtitle),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (showNavigationIcon) {
                        IconButton(onClick = onNavigationClick) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = stringResource(R.string.nav_open_menu),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        ),
    ) { contentPadding ->
        appContent(
            navController,
            reduceMotion,
            Modifier.padding(contentPadding).consumeWindowInsets(contentPadding),
        )
    }
}

@Composable
private fun AppDrawer(
    currentRoute: String?,
    onNavigate: (Screen) -> Unit,
) {
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            NavigationBrand()
            Spacer(Modifier.height(SpacingTokens.Small))
            Screen.navigationGroups.forEach { group ->
                DrawerSectionHeader(stringResource(group.titleRes))
                group.screens.forEach { screen ->
                    DrawerItem(
                        screen = screen,
                        selected = currentRoute == screen.route,
                        onClick = { onNavigate(screen) },
                    )
                }
            }
            Spacer(Modifier.height(SpacingTokens.ExtraLarge))
        }
    }
}

@Composable
private fun NavigationBrand(
    compact: Boolean = false,
    showText: Boolean = true,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(if (compact) SpacingTokens.Small else SpacingTokens.Medium),
    ) {
        Column(
            modifier = Modifier.padding(if (compact) SpacingTokens.Large else SpacingTokens.CardContent),
            horizontalAlignment = if (showText) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = Modifier.size(if (compact) SizeTokens.BrandMarkCompact else SizeTokens.BrandMarkExpanded),
            )
            if (showText) {
                Spacer(Modifier.height(SpacingTokens.Medium))
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLargeEmphasized)
                if (!compact) {
                    Text(
                        stringResource(R.string.app_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.app_version_short, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMediumEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .semantics { heading() }
            .padding(
                start = SpacingTokens.DrawerHeaderStart,
                top = SpacingTokens.Large,
                end = SpacingTokens.Large,
                bottom = SpacingTokens.Compact,
            ),
    )
}

@Composable
private fun DrawerItem(screen: Screen, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(screen.icon, contentDescription = null) },
        label = { Text(stringResource(screen.titleRes)) },
        selected = selected,
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.padding(horizontal = SpacingTokens.Medium, vertical = SpacingTokens.Micro),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            unselectedContainerColor = MaterialTheme.colorScheme.surface,
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

private fun NavHostController.navigateTo(screen: Screen) {
    if (screen == Screen.Control) {
        popOrOpenControl()
        return
    }
    navigate(screen.route) {
        popUpTo(Screen.Control.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
