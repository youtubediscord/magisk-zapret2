package com.zapret2.app.ui.screen

import android.icu.text.CompactDecimalFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.zapret2.app.R
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.HostlistItem
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.components.SectionHeader
import com.zapret2.app.ui.components.quantityStringResource
import com.zapret2.app.ui.components.LocalReducedMotionEnabled
import com.zapret2.app.ui.navigation.Screen
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.ui.theme.ZapretTheme
import com.zapret2.app.viewmodel.HostlistsViewModel
import com.zapret2.app.viewmodel.HostlistsUiState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HostlistsScreen(
    navController: NavController,
    viewModel: HostlistsViewModel? = null,
    previewState: HostlistsUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: HostlistsUiState()
    val reduceMotion = LocalReducedMotionEnabled.current
    val locale = LocalConfiguration.current.locales[0]
    val compactNumberFormat = remember(locale) {
        CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
    }
    val formattedTotalEntries = remember(state.totalEntries, compactNumberFormat) {
        compactNumberFormat.format(state.totalEntries)
    }

    LifecycleStartEffect(activeViewModel) {
        activeViewModel?.onScreenEntered()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }

    val importLauncher = if (activeViewModel != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(activeViewModel::importHostlist)
        }
    } else {
        null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) {
                SpacingTokens.ExtraLarge
            } else {
                SpacingTokens.Large
            }
            LazyColumn(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = SizeTokens.ContentMax)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.ItemVertical),
                contentPadding = PaddingValues(
                    start = horizontalPadding,
                    top = SpacingTokens.Large,
                    end = horizontalPadding,
                    bottom = SpacingTokens.Section,
                ),
            ) {
                item {
                    ContentCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                shape = MaterialTheme.shapes.extraLarge,
                            ) {
                                Icon(
                                    Icons.Default.Storage,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .padding(SpacingTokens.RowVertical)
                                        .size(SizeTokens.IconEmphasized),
                                )
                            }
                            Spacer(Modifier.width(SpacingTokens.Large))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(
                                        R.string.hostlists_total_entries,
                                        formattedTotalEntries,
                                    ),
                                    style = MaterialTheme.typography.headlineSmallEmphasized,
                                )
                                Text(
                                    text = quantityStringResource(
                                        R.plurals.hostlists_total_files,
                                        state.totalFiles,
                                        state.totalFiles,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { activeViewModel?.refresh() },
                                enabled = state.canReloadCatalog,
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.cd_refresh_hostlists),
                                )
                            }
                        }
                    }
                }

                item {
                    FilledTonalButton(
                        onClick = {
                            importLauncher?.launch(
                                arrayOf("text/plain", "text/*", "application/octet-stream"),
                            )
                        },
                        enabled = state.canStartCatalogOperation,
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null)
                        Spacer(Modifier.width(SpacingTokens.Small))
                        Text(stringResource(R.string.hostlists_import_action))
                    }
                }

                state.importResult?.let { result ->
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = MaterialTheme.shapes.large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Row(
                                modifier = Modifier.padding(SpacingTokens.Medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = stringResource(result.messageRes),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { activeViewModel?.clearImportResult() }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.action_close),
                                    )
                                }
                            }
                        }
                    }
                }

                item { SectionHeader(stringResource(R.string.hostlists_files_title)) }

                if (state.loadError != null && !state.isLoading) {
                    item { HostlistsLoadErrorState(onRetry = { activeViewModel?.loadData() }) }
                } else if (state.hostlists.isEmpty() && !state.isLoading) {
                    item { EmptyHostlistState(onRefresh = { activeViewModel?.refresh() }) }
                }

                items(
                    items = state.hostlists,
                    key = { it.filename },
                    contentType = { "hostlist" },
                ) { hostlist ->
                    HostlistItem(
                        filename = hostlist.filename,
                        entryCount = hostlist.entryCount,
                        sizeBytes = hostlist.sizeBytes,
                        icon = hostlistIcon(hostlist.filename),
                        iconTint = hostlistIconColor(hostlist.filename),
                        enabled = state.canOpenHostlist,
                        onClick = {
                            navController.navigate(
                                Screen.HostlistContent.createRoute(
                                    hostlist.filename,
                                ),
                            ) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
        }

        if (state.isRefreshing) {
            LinearWavyProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                amplitude = if (reduceMotion) 0f else 1f,
            )
        }

        LoadingOverlay(
            text = stringResource(
                if (state.isImporting) {
                    R.string.hostlists_importing
                } else {
                    R.string.loading_hostlists
                },
            ),
            visible = state.isImporting || (state.isLoading && state.hostlists.isEmpty()),
        )
    }
}

@Composable
private fun HostlistsLoadErrorState(onRetry: () -> Unit) {
    ContentCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.CardContent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(SizeTokens.IconLarge),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
            text = stringResource(R.string.hostlists_load_error_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.hostlists_load_error_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SpacingTokens.Large))
            FilledTonalButton(onClick = onRetry, shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(SpacingTokens.Small))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun EmptyHostlistState(onRefresh: () -> Unit) {
    ContentCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.CardContent),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(SizeTokens.IconLarge),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
                stringResource(R.string.hostlists_empty_title),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Text(
                text = stringResource(R.string.hostlists_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(SpacingTokens.Large))
            FilledTonalButton(onClick = onRefresh, shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(SpacingTokens.Small))
                Text(stringResource(R.string.action_refresh))
            }
        }
    }
}

private fun hostlistIcon(filename: String): ImageVector {
    val normalized = filename.lowercase().removeSuffix(".txt")
    return when {
        normalized.contains("youtube") ||
            normalized.contains("twitch") ||
            normalized.contains("tiktok") -> Icons.Default.VideoLibrary
        normalized.contains("discord") ||
            normalized.contains("telegram") ||
            normalized.contains("whatsapp") -> Icons.AutoMirrored.Filled.Chat
        normalized.contains("facebook") ||
            normalized.contains("instagram") ||
            normalized.contains("twitter") -> Icons.Default.Public
        else -> Icons.Default.Folder
    }
}

@Composable
private fun hostlistIconColor(filename: String): Color {
    val normalized = filename.lowercase().removeSuffix(".txt")
    return when {
        normalized.contains("youtube") || normalized.contains("twitch") ->
            MaterialTheme.colorScheme.error
        normalized.contains("discord") || normalized.contains("telegram") ->
            MaterialTheme.colorScheme.primary
        normalized.contains("facebook") || normalized.contains("instagram") ->
            MaterialTheme.colorScheme.tertiary
        normalized.contains("whatsapp") -> MaterialTheme.extendedColors.success.color
        else -> MaterialTheme.extendedColors.info.color
    }
}

@Preview(
    name = "Hostlists error · Russian large text",
    widthDp = 411,
    fontScale = 1.5f,
    locale = "ru",
    showBackground = true,
)
@Composable
private fun HostlistsErrorPreview() {
    ZapretTheme(dynamicColor = false) {
        Surface(modifier = Modifier.padding(SpacingTokens.Large)) {
            HostlistsLoadErrorState(onRetry = {})
        }
    }
}

@Preview(
    name = "Hostlists empty · RTL",
    widthDp = 411,
    locale = "ar",
    showBackground = true,
)
@Composable
private fun HostlistsEmptyRtlPreview() {
    ZapretTheme(dynamicColor = false) {
        Surface(modifier = Modifier.padding(SpacingTokens.Large)) {
            EmptyHostlistState(onRefresh = {})
        }
    }
}
