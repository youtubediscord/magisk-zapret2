package com.zapret2.app.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import com.zapret2.app.R
import com.zapret2.app.data.StrategyRepository
import com.zapret2.app.ui.components.*
import com.zapret2.app.ui.theme.*
import com.zapret2.app.viewmodel.*

@Composable
fun StrategiesScreen(viewModel: StrategiesViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Which category is being picked (null = show categories list)
    var pickingCategory by remember { mutableStateOf<CategoryUiModel?>(null) }

    LaunchedEffect(Unit) { viewModel.snackbar.collect { snackbarHostState.showSnackbar(it) } }

    Box {
        Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
            AnimatedContent(
                targetState = pickingCategory,
                modifier = Modifier.padding(padding),
                label = "strategies_content"
            ) { category ->
                if (category == null) {
                    // Categories list
                    CategoriesListView(
                        state = state,
                        viewModel = viewModel,
                        onCategoryClick = { pickingCategory = it }
                    )
                } else {
                    // Full-page strategy picker
                    StrategyPickerView(
                        category = category,
                        onBack = { pickingCategory = null },
                        onSelected = { strategyId, filterMode ->
                            viewModel.selectStrategy(category.key, strategyId, filterMode)
                            pickingCategory = null
                        }
                    )
                }
            }
        }
        LoadingOverlay(text = state.loadingText, visible = state.isLoading)
    }
}

@Composable
private fun CategoriesListView(
    state: StrategiesUiState,
    viewModel: StrategiesViewModel,
    onCategoryClick: (CategoryUiModel) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item { SectionHeader("CATEGORIES") }
        items(state.categories, key = { it.key }) { cat ->
            CategoryRow(
                title = cat.title,
                subtitle = cat.subtitle,
                value = cat.strategyDisplayName,
                iconRes = resolveCategoryIcon(cat.key, cat.type),
                iconTint = resolveCategoryColor(cat.key, cat.type),
                onClick = { onCategoryClick(cat) }
            )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
        item { SectionHeader("ADVANCED") }
        item {
            var showPktPicker by remember { mutableStateOf(false) }
            SettingRow(title = "PKT_COUNT", value = state.pktCount, onClick = { showPktPicker = true })
            if (showPktPicker) {
                val pktOptions = listOf("1", "3", "5", "10", "15", "20")
                StrategyPickerSheet(
                    title = "PKT_COUNT",
                    subtitle = "Packets to modify",
                    strategies = pktOptions.map { StrategyItem(it, it) },
                    selectedId = state.pktCount,
                    onDismiss = { showPktPicker = false },
                    onSelected = { id, _ -> viewModel.setPktCount(id); showPktPicker = false }
                )
            }
        }
        item {
            var showDebugPicker by remember { mutableStateOf(false) }
            SettingRow(title = "Debug Mode", value = when (state.debugMode) {
                "none" -> "None"; "android" -> "Android"; "file" -> "File"; "syslog" -> "Syslog"; else -> "None"
            }, onClick = { showDebugPicker = true })
            if (showDebugPicker) {
                StrategyPickerSheet(
                    title = "Debug Mode",
                    subtitle = "Log destination",
                    strategies = listOf(
                        StrategyItem("none", "None", "Logging disabled"),
                        StrategyItem("android", "Android (logcat)", "Output to logcat"),
                        StrategyItem("file", "File", "Write to file"),
                        StrategyItem("syslog", "Syslog", "System logger")
                    ),
                    selectedId = state.debugMode,
                    onDismiss = { showDebugPicker = false },
                    onSelected = { id, _ -> viewModel.setDebugMode(id); showDebugPicker = false }
                )
            }
        }
    }
}

@Composable
private fun StrategyPickerView(
    category: CategoryUiModel,
    onBack: () -> Unit,
    onSelected: (strategyId: String, filterMode: String?) -> Unit
) {
    val scope = rememberCoroutineScope()

    // Load strategy details with custom order
    val details = remember(category.type) { mutableStateListOf<StrategyRepository.StrategyDetail>() }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(category.type) {
        isLoading = true
        val list = StrategyRepository.getStrategyDetails(category.type)
        val savedOrder = StrategyRepository.getSavedOrder(category.type)
        val ordered = StrategyRepository.applyOrder(list, savedOrder)
        details.clear()
        details.addAll(ordered)
        isLoading = false
    }

    // Search & filter state
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf(category.filterMode) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var isReorderMode by remember { mutableStateOf(false) }

    // Filter strategies by search (only when not reordering)
    val displayList = remember(searchQuery, details.toList(), isReorderMode) {
        if (isReorderMode || searchQuery.isBlank()) details.toList()
        else {
            val q = searchQuery.lowercase()
            details.filter {
                it.displayName.lowercase().contains(q) ||
                    it.description.lowercase().contains(q) ||
                    it.args.lowercase().contains(q) ||
                    it.id.lowercase().contains(q)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header with back button + reorder toggle
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (isReorderMode) {
                    isReorderMode = false
                    // Save order when exiting reorder mode
                    scope.launch {
                        StrategyRepository.saveOrder(category.type, details.map { it.id })
                    }
                } else {
                    onBack()
                }
            }) {
                Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isReorderMode) "Reorder strategies" else category.title,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Text(
                    if (isReorderMode) "Use arrows to move, back to save" else category.subtitle,
                    fontSize = 12.sp,
                    color = if (isReorderMode) AccentLightBlue else TextSecondary
                )
            }
            // Reorder mode toggle
            IconButton(onClick = {
                if (isReorderMode) {
                    // Save and exit reorder
                    scope.launch {
                        StrategyRepository.saveOrder(category.type, details.map { it.id })
                    }
                    isReorderMode = false
                } else {
                    isReorderMode = true
                    searchQuery = "" // Clear search when entering reorder
                }
            }) {
                Icon(
                    if (isReorderMode) Icons.Default.Check else Icons.Default.SwapVert,
                    if (isReorderMode) "Save order" else "Reorder",
                    tint = if (isReorderMode) StatusActive else TextSecondary
                )
            }
        }

        // Search field (hidden in reorder mode)
        if (!isReorderMode) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search strategies...", color = TextHint) },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, tint = TextQuaternary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, "Clear", tint = TextQuaternary)
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentLightBlue,
                    unfocusedBorderColor = Border,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )
        }

        // Filter mode toggle (if category supports switching, hidden in reorder mode)
        if (category.canSwitchFilter && !isReorderMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = filterMode == "ipset",
                    onClick = { filterMode = "ipset" },
                    label = { Text("IPSet") }
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilterChip(
                    selected = filterMode == "hostlist",
                    onClick = { filterMode = "hostlist" },
                    label = { Text("Hostlist") }
                )
            }
        }

        // Count
        Text(
            "${displayList.size} strategies",
            fontSize = 12.sp,
            color = TextTertiary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        if (isLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentLightBlue)
            }
        }

        // Strategy list
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(displayList, key = { it.id }) { strategy ->
                val isSelected = strategy.id == category.strategyName
                val isExpanded = expandedId == strategy.id
                val currentIndex = details.indexOf(strategy)
                val isDisabled = strategy.id == "disabled"

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) ItemBackgroundSelected else ItemBackground)
                        .then(
                            if (!isReorderMode) Modifier.clickable {
                                onSelected(
                                    strategy.id,
                                    if (category.canSwitchFilter) filterMode else null
                                )
                            } else Modifier
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isReorderMode && !isDisabled) {
                            // Reorder arrows
                            Column {
                                IconButton(
                                    onClick = {
                                        if (currentIndex > 1) { // Don't move above "disabled" at index 0
                                            val item = details.removeAt(currentIndex)
                                            details.add(currentIndex - 1, item)
                                        }
                                    },
                                    enabled = currentIndex > 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowUp, "Move up",
                                        tint = if (currentIndex > 1) AccentLightBlue else TextEmpty,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        if (currentIndex < details.size - 1) {
                                            val item = details.removeAt(currentIndex)
                                            details.add(currentIndex + 1, item)
                                        }
                                    },
                                    enabled = currentIndex < details.size - 1,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.KeyboardArrowDown, "Move down",
                                        tint = if (currentIndex < details.size - 1) AccentLightBlue else TextEmpty,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        } else {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    if (!isReorderMode) {
                                        onSelected(
                                            strategy.id,
                                            if (category.canSwitchFilter) filterMode else null
                                        )
                                    }
                                },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = AccentLightBlue,
                                    unselectedColor = TextQuaternary
                                )
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(strategy.displayName, fontSize = 14.sp, color = TextPrimary)
                            if (strategy.description.isNotEmpty()) {
                                Text(strategy.description, fontSize = 11.sp, color = TextTertiary)
                            }
                        }
                        // Preview button (only if args exist and NOT in reorder mode)
                        if (strategy.args.isNotEmpty() && !isReorderMode) {
                            IconButton(
                                onClick = { expandedId = if (isExpanded) null else strategy.id },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.Code,
                                    "Preview args",
                                    tint = if (isExpanded) AccentLightBlue else TextQuaternary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Expandable args preview
                    if (!isReorderMode) {
                        AnimatedVisibility(visible = isExpanded) {
                            Text(
                                text = strategy.args.replace(" --", "\n--"),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextCommand,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 48.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(SurfaceVeryDark)
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- Icon/color resolvers ---

private fun resolveCategoryIcon(key: String, type: String): Int {
    val k = key.lowercase()
    return when {
        k.contains("youtube") || k.contains("googlevideo") || k.contains("twitch") -> R.drawable.ic_video
        k.contains("discord") || k.contains("telegram") || k.contains("whatsapp") -> R.drawable.ic_message
        k.contains("voice") || type == "voice" -> R.drawable.ic_message
        k.contains("facebook") || k.contains("instagram") || k.contains("twitter") -> R.drawable.ic_social
        else -> R.drawable.ic_apps
    }
}

private fun resolveCategoryColor(key: String, type: String): Color {
    val k = key.lowercase()
    return when {
        k.contains("youtube") -> YoutubeRed
        k.contains("googlevideo") -> GooglevideoRed
        k.contains("twitch") -> TwitchPurple
        k.contains("discord") -> DiscordBlue
        k.contains("telegram") -> TelegramBlue
        k.contains("whatsapp") -> WhatsappGreen
        k.contains("voice") || type == "voice" -> VoicePurple
        k.contains("facebook") -> FacebookBlue
        k.contains("instagram") -> InstagramPink
        k.contains("twitter") -> TwitterBlue
        k.contains("github") -> GithubWhite
        k.contains("soundcloud") -> SoundcloudOrange
        k.contains("steam") -> SteamBlue
        type == "udp" -> UdpBlue
        else -> StatusSuccess
    }
}
