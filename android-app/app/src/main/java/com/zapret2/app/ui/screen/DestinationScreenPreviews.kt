package com.zapret2.app.ui.screen

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.compose.rememberNavController
import com.zapret2.app.R
import com.zapret2.app.data.DirectService
import com.zapret2.app.data.DnsDomain
import com.zapret2.app.data.DnsService
import com.zapret2.app.data.HostsIniData
import com.zapret2.app.data.ModuleInstallState
import com.zapret2.app.data.PresetEntry
import com.zapret2.app.ui.UiText
import com.zapret2.app.ui.theme.ZapretTheme
import com.zapret2.app.viewmodel.CategoryUiModel
import com.zapret2.app.viewmodel.ConfigEditorUiState
import com.zapret2.app.viewmodel.ConfigEditorOperation
import com.zapret2.app.viewmodel.ConfigEditorResult
import com.zapret2.app.viewmodel.ControlStatus
import com.zapret2.app.viewmodel.ControlUiState
import com.zapret2.app.viewmodel.DnsManagerOperation
import com.zapret2.app.viewmodel.DnsManagerUiState
import com.zapret2.app.viewmodel.HostlistContentError
import com.zapret2.app.viewmodel.HostlistContentLoadState
import com.zapret2.app.viewmodel.HostlistContentUiState
import com.zapret2.app.viewmodel.HostlistUiModel
import com.zapret2.app.viewmodel.HostlistsLoadError
import com.zapret2.app.viewmodel.HostlistsUiState
import com.zapret2.app.viewmodel.HostsEditorOperation
import com.zapret2.app.viewmodel.HostsEditorResult
import com.zapret2.app.viewmodel.HostsEditorUiState
import com.zapret2.app.viewmodel.LogTab
import com.zapret2.app.viewmodel.LogsLoadState
import com.zapret2.app.viewmodel.LogsUiState
import com.zapret2.app.viewmodel.PresetsOperation
import com.zapret2.app.viewmodel.PresetsUiState
import com.zapret2.app.viewmodel.StrategiesUiState

/** Real destination entry points rendered with deterministic state; no parallel preview UI. */
@Composable
private fun PreviewDestination(content: @Composable () -> Unit) {
    ZapretTheme(dynamicColor = false, reducedMotion = true, content = content)
}

@Preview(name = "Control · compact", widthDp = 360, heightDp = 800)
@Composable
private fun ControlFullScreenPreview() = PreviewDestination {
    ControlScreen(
        previewState = ControlUiState(
            status = ControlStatus.RUNNING,
            isRunning = true,
            canStopService = true,
            uptime = "02:14:37",
            hasRootAccess = true,
            moduleInstallState = ModuleInstallState.READY,
            nfqueueSupported = true,
            hasAuthoritativeRuntimeSettings = true,
        ),
    )
}

@Preview(name = "Strategies · 600dp", widthDp = 600, heightDp = 800)
@Composable
private fun StrategiesFullScreenPreview() = PreviewDestination {
    StrategiesScreen(
        previewState = StrategiesUiState(
            categories = listOf(
                CategoryUiModel(
                    key = "youtube",
                    title = "YouTube",
                    subtitle = UiText.resource(
                        R.string.strategies_category_subtitle_target,
                        "TCP",
                        "youtube.txt",
                    ),
                    type = "tcp",
                    strategy = "syndata_multisplit_tls_google_700",
                    strategyDisplayName = UiText.Dynamic("Syndata multisplit TLS Google 700"),
                    filterMode = "hostlist",
                    canSwitchFilter = true,
                ),
            ),
            pktCount = "20",
        ),
    )
}

@Preview(
    name = "Presets · 840dp dark",
    widthDp = 840,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun PresetsFullScreenPreview() = PreviewDestination {
    PresetsScreen(
        previewState = PresetsUiState(
            activeMode = "preset",
            activePresetFile = "syndata_multisplit_tls_google_700.txt",
            hasAuthoritativeCatalog = true,
            presets = listOf(
                PresetEntry(
                    fileName = "syndata_multisplit_tls_google_700.txt",
                    displayName = "Syndata multisplit TLS Google 700",
                ),
                PresetEntry("multisplit_tls.txt", "Multisplit TLS"),
            ),
        ),
    )
}

@Preview(name = "Hostlists · RTL", widthDp = 360, heightDp = 800, locale = "ar")
@Composable
private fun HostlistsFullScreenPreview() = PreviewDestination {
    HostlistsScreen(
        navController = rememberNavController(),
        previewState = HostlistsUiState(
            hasAuthoritativeCatalog = true,
            hostlists = listOf(
                HostlistUiModel(
                    filename = "youtube.txt",
                    entryCount = 24,
                    sizeBytes = 12_345L,
                ),
            ),
            totalEntries = 24,
            totalFiles = 1,
        ),
    )
}

@Preview(
    name = "DNS · Russian 200%",
    widthDp = 360,
    heightDp = 900,
    locale = "ru",
    fontScale = 2f,
)
@Composable
private fun DnsFullScreenPreview() = PreviewDestination {
    DnsManagerScreen(
        previewState = DnsManagerUiState(
            hostsData = HostsIniData(
                dnsPresets = listOf("Cloudflare", "Google"),
                dnsServices = listOf(
                    DnsService(
                        name = "YouTube",
                        domains = listOf(DnsDomain("youtube.com", listOf("1.1.1.1", "8.8.8.8"))),
                    ),
                ),
                directServices = listOf(DirectService("Local", listOf("localhost"))),
            ),
            selectedDnsServices = setOf("YouTube"),
        ),
    )
}

@Preview(
    name = "Hosts editor · compact dark",
    widthDp = 360,
    heightDp = 800,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HostsEditorFullScreenPreview() = PreviewDestination {
    HostsEditorScreen(
        onNavigateBack = {},
        previewState = HostsEditorUiState(
            content = "127.0.0.1 localhost\n1.1.1.1 example.org",
            hasAuthoritativeBaseline = true,
            hasUnsavedChanges = true,
        ),
    )
}

@Preview(name = "Config editor · 600dp", widthDp = 600, heightDp = 800)
@Composable
private fun ConfigEditorFullScreenPreview() = PreviewDestination {
    ConfigEditorScreen(
        onNavigateBack = {},
        previewState = ConfigEditorUiState(
            commandText = "nfqws2 --qnum=200 --lua-init=@zapret-antidpi.lua",
            hasUnsavedChanges = true,
            hasAuthoritativeBinding = true,
        ),
    )
}

@Preview(
    name = "Logs · 840dp dark",
    widthDp = 840,
    heightDp = 720,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun LogsFullScreenPreview() = PreviewDestination {
    LogsScreen(
        previewState = LogsUiState(
            commandLoadState = LogsLoadState.READY,
            cmdline = "nfqws2 --qnum=200 --lua-init=@zapret-antidpi.lua",
            rawCmdline = "nfqws2 --qnum=200 --lua-init=@zapret-antidpi.lua",
        ),
    )
}

@Preview(name = "About · RTL", widthDp = 360, heightDp = 800, locale = "ar")
@Composable
private fun AboutFullScreenPreview() = PreviewDestination { AboutScreen() }

@Preview(
    name = "Hostlist content · Russian 200%",
    widthDp = 360,
    heightDp = 900,
    locale = "ru",
    fontScale = 2f,
)
@Composable
private fun HostlistContentFullScreenPreview() = PreviewDestination {
    HostlistContentScreen(
        navController = rememberNavController(),
        previewState = HostlistContentUiState(
            fileName = "youtube.txt",
            totalEntries = 4,
            entries = listOf("googlevideo.com", "youtube.com", "youtu.be", "ytimg.com"),
            loadState = HostlistContentLoadState.READY,
        ),
    )
}

@Preview(name = "Control - degraded", widthDp = 360, heightDp = 800)
@Composable
private fun ControlDegradedPreview() = PreviewDestination {
    ControlScreen(
        previewState = ControlUiState(
            status = ControlStatus.DEGRADED,
            hasRootAccess = true,
            moduleInstallState = ModuleInstallState.PARTIAL,
            isCheckingForUpdates = true,
        ),
    )
}

@Preview(name = "Control - root denied", widthDp = 360, heightDp = 800)
@Composable
private fun ControlRootDeniedPreview() = PreviewDestination {
    ControlScreen(
        previewState = ControlUiState(
            status = ControlStatus.ROOT_DENIED,
            moduleInstallState = ModuleInstallState.READY,
        ),
    )
}

@Preview(name = "Strategies - loading", widthDp = 360, heightDp = 800)
@Composable
private fun StrategiesLoadingPreview() = PreviewDestination {
    StrategiesScreen(
        previewState = StrategiesUiState(
            isLoading = true,
            loadingText = UiText.Resource(R.string.strategies_loading),
        ),
    )
}

@Preview(name = "Strategies - error", widthDp = 360, heightDp = 800)
@Composable
private fun StrategiesErrorPreview() = PreviewDestination {
    StrategiesScreen(
        previewState = StrategiesUiState(
            loadError = UiText.Resource(R.string.strategies_load_failed),
        ),
    )
}

@Preview(name = "Strategies - empty", widthDp = 360, heightDp = 800)
@Composable
private fun StrategiesEmptyPreview() = PreviewDestination {
    StrategiesScreen(previewState = StrategiesUiState())
}

@Preview(name = "Strategies - disabled category", widthDp = 360, heightDp = 800)
@Composable
private fun StrategiesDisabledPreview() = PreviewDestination {
    StrategiesScreen(
        previewState = StrategiesUiState(
            categories = listOf(
                CategoryUiModel(
                    key = "discord",
                    title = "Discord",
                    subtitle = UiText.resource(
                        R.string.strategies_category_subtitle_no_filter,
                        "UDP",
                    ),
                    type = "udp",
                    strategy = "disabled",
                    strategyDisplayName = UiText.Resource(R.string.state_disabled),
                    filterMode = "none",
                    canSwitchFilter = false,
                ),
            ),
        ),
    )
}

@Preview(name = "Presets - loading", widthDp = 360, heightDp = 800)
@Composable
private fun PresetsLoadingPreview() = PreviewDestination {
    PresetsScreen(
        previewState = PresetsUiState(
            operation = PresetsOperation.LOAD,
            loadingText = UiText.Resource(R.string.presets_loading),
        ),
    )
}

@Preview(name = "Presets - error", widthDp = 360, heightDp = 800)
@Composable
private fun PresetsErrorPreview() = PreviewDestination {
    PresetsScreen(
        previewState = PresetsUiState(
            loadError = UiText.Resource(R.string.presets_load_failed),
        ),
    )
}

@Preview(name = "Presets - empty", widthDp = 360, heightDp = 800)
@Composable
private fun PresetsEmptyPreview() = PreviewDestination {
    PresetsScreen(previewState = PresetsUiState(hasAuthoritativeCatalog = true))
}

@Preview(name = "Hostlists - loading", widthDp = 360, heightDp = 800)
@Composable
private fun HostlistsLoadingPreview() = PreviewDestination {
    HostlistsScreen(
        navController = rememberNavController(),
        previewState = HostlistsUiState(isLoading = true),
    )
}

@Preview(name = "Hostlists - empty", widthDp = 360, heightDp = 800)
@Composable
private fun HostlistsEmptyPreview() = PreviewDestination {
    HostlistsScreen(
        navController = rememberNavController(),
        previewState = HostlistsUiState(hasAuthoritativeCatalog = true),
    )
}

@Preview(name = "Hostlists - error", widthDp = 360, heightDp = 800)
@Composable
private fun HostlistsErrorPreview() = PreviewDestination {
    HostlistsScreen(
        navController = rememberNavController(),
        previewState = HostlistsUiState(loadError = HostlistsLoadError.ROOT_COMMAND_FAILED),
    )
}

@Preview(name = "DNS - loading", widthDp = 360, heightDp = 800)
@Composable
private fun DnsLoadingPreview() = PreviewDestination {
    DnsManagerScreen(
        previewState = DnsManagerUiState(
            operation = DnsManagerOperation.LOAD,
            loadingText = UiText.Resource(R.string.dns_loading_hosts),
        ),
    )
}

@Preview(name = "DNS - error", widthDp = 360, heightDp = 800)
@Composable
private fun DnsErrorPreview() = PreviewDestination {
    DnsManagerScreen(
        previewState = DnsManagerUiState(
            loadError = UiText.Resource(R.string.dns_load_error_body),
        ),
    )
}

@Preview(name = "DNS - empty", widthDp = 360, heightDp = 800)
@Composable
private fun DnsEmptyPreview() = PreviewDestination {
    DnsManagerScreen(
        previewState = DnsManagerUiState(
            hostsData = HostsIniData(
                dnsPresets = emptyList(),
                dnsServices = emptyList(),
                directServices = emptyList(),
            ),
        ),
    )
}

@Preview(name = "Hosts editor - disabled", widthDp = 360, heightDp = 800)
@Composable
private fun HostsEditorDisabledPreview() = PreviewDestination {
    HostsEditorScreen(
        onNavigateBack = {},
        previewState = HostsEditorUiState(
            operation = HostsEditorOperation.LOAD,
        ),
    )
}

@Preview(name = "Hosts editor - error", widthDp = 360, heightDp = 800)
@Composable
private fun HostsEditorErrorPreview() = PreviewDestination {
    HostsEditorScreen(
        onNavigateBack = {},
        previewState = HostsEditorUiState(
            hasAuthoritativeBaseline = false,
            baselineLoadAttempted = true,
            result = HostsEditorResult.READ_FAILED,
            message = UiText.Resource(R.string.hosts_read_failed),
        ),
    )
}

@Preview(name = "Hosts editor - empty", widthDp = 360, heightDp = 800)
@Composable
private fun HostsEditorEmptyPreview() = PreviewDestination {
    HostsEditorScreen(
        onNavigateBack = {},
        previewState = HostsEditorUiState(
            hasAuthoritativeBaseline = true,
            result = HostsEditorResult.EMPTY,
            message = UiText.Resource(R.string.hosts_file_empty),
        ),
    )
}

@Preview(name = "Hosts editor - invalid", widthDp = 360, heightDp = 800)
@Composable
private fun HostsEditorInvalidPreview() = PreviewDestination {
    HostsEditorScreen(
        onNavigateBack = {},
        previewState = HostsEditorUiState(
            content = "not-a-valid-hosts-line",
            hasAuthoritativeBaseline = true,
            hasUnsavedChanges = true,
            result = HostsEditorResult.INVALID,
            message = UiText.Resource(R.string.hosts_file_invalid),
        ),
    )
}

@Preview(name = "Config editor - disabled", widthDp = 360, heightDp = 800)
@Composable
private fun ConfigEditorDisabledPreview() = PreviewDestination {
    ConfigEditorScreen(
        onNavigateBack = {},
        previewState = ConfigEditorUiState(
            operation = ConfigEditorOperation.LOAD,
            actionsEnabled = false,
        ),
    )
}

@Preview(name = "Config editor - error", widthDp = 360, heightDp = 800)
@Composable
private fun ConfigEditorErrorPreview() = PreviewDestination {
    ConfigEditorScreen(
        onNavigateBack = {},
        previewState = ConfigEditorUiState(
            result = ConfigEditorResult.LOAD_FAILED,
            bindingLoadAttempted = true,
            message = UiText.Resource(R.string.config_load_failed),
        ),
    )
}

@Preview(name = "Config editor - empty", widthDp = 360, heightDp = 800)
@Composable
private fun ConfigEditorEmptyPreview() = PreviewDestination {
    ConfigEditorScreen(
        onNavigateBack = {},
        previewState = ConfigEditorUiState(
            result = ConfigEditorResult.EMPTY,
            message = UiText.Resource(R.string.config_command_empty),
            hasAuthoritativeBinding = true,
        ),
    )
}

@Preview(name = "Logs - loading", widthDp = 360, heightDp = 800)
@Composable
private fun LogsLoadingPreview() = PreviewDestination {
    LogsScreen(
        previewState = LogsUiState(commandLoadState = LogsLoadState.LOADING),
    )
}

@Preview(name = "Logs - error", widthDp = 360, heightDp = 800)
@Composable
private fun LogsErrorPreview() = PreviewDestination {
    LogsScreen(
        previewState = LogsUiState(
            currentTab = LogTab.LOGS,
            commandLoadState = LogsLoadState.READY,
            outputLoadState = LogsLoadState.ERROR,
        ),
    )
}

@Preview(name = "Logs - empty", widthDp = 360, heightDp = 800)
@Composable
private fun LogsEmptyPreview() = PreviewDestination {
    LogsScreen(
        previewState = LogsUiState(
            commandLoadState = LogsLoadState.READY,
            cmdline = "",
        ),
    )
}

@Preview(name = "Hostlist content - loading", widthDp = 360, heightDp = 800)
@Composable
private fun HostlistContentLoadingPreview() = PreviewDestination {
    HostlistContentScreen(
        navController = rememberNavController(),
        previewState = HostlistContentUiState(
            fileName = "youtube.txt",
            loadState = HostlistContentLoadState.LOADING,
        ),
    )
}

@Preview(name = "Hostlist content - error", widthDp = 360, heightDp = 800)
@Composable
private fun HostlistContentErrorPreview() = PreviewDestination {
    HostlistContentScreen(
        navController = rememberNavController(),
        previewState = HostlistContentUiState(
            fileName = "youtube.txt",
            loadState = HostlistContentLoadState.ERROR,
            loadError = HostlistContentError.LOAD_FAILED,
        ),
    )
}

@Preview(name = "Hostlist content - empty", widthDp = 360, heightDp = 800)
@Composable
private fun HostlistContentEmptyPreview() = PreviewDestination {
    HostlistContentScreen(
        navController = rememberNavController(),
        previewState = HostlistContentUiState(
            fileName = "empty.txt",
            loadState = HostlistContentLoadState.READY,
        ),
    )
}
