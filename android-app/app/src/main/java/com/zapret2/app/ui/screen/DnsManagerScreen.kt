package com.zapret2.app.ui.screen

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.ui.resolve
import com.zapret2.app.ui.components.AdaptiveActionGroup
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.DnsServiceItem
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.components.SectionHeader
import com.zapret2.app.ui.components.SettingRow
import com.zapret2.app.ui.components.quantityStringResource
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.viewmodel.DnsManagerViewModel
import com.zapret2.app.viewmodel.DnsManagerUiState

@Composable
fun DnsManagerScreen(
    viewModel: DnsManagerViewModel? = null,
    previewState: DnsManagerUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: DnsManagerUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPresetPicker by rememberSaveable { mutableStateOf(false) }
    var showResetConfirmation by rememberSaveable { mutableStateOf(false) }
    val hostsData = state.hostsData
    val selectionEnabled = state.canEditSelection

    LaunchedEffect(activeViewModel) {
        activeViewModel?.ensureLoaded()
    }

    LaunchedEffect(selectionEnabled) {
        if (!selectionEnabled) {
            showPresetPicker = false
            showResetConfirmation = false
        }
    }

    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    if (showPresetPicker && hostsData != null && selectionEnabled) {
        DnsPresetDialog(
            presets = hostsData.dnsPresets,
            selectedIndex = state.selectedPresetIndex,
            onSelect = {
                activeViewModel?.selectPreset(it)
                showPresetPicker = false
            },
            onDismiss = { showPresetPicker = false },
        )
    }

    if (showResetConfirmation && selectionEnabled) {
        AlertDialog(
            onDismissRequest = { showResetConfirmation = false },
            shape = MaterialTheme.shapes.extraLarge,
            icon = { Icon(Icons.Default.RestartAlt, contentDescription = null) },
            title = { Text(stringResource(R.string.dns_reset_title)) },
            text = { Text(stringResource(R.string.dns_reset_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        showResetConfirmation = false
                        activeViewModel?.resetDns(confirmed = true)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showResetConfirmation = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { scaffoldPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(scaffoldPadding),
            ) {
                val horizontalPadding = if (maxWidth >= SizeTokens.MediumBreakpoint) SpacingTokens.ExtraLarge else SpacingTokens.Large
                val compactActions = maxWidth < SizeTokens.CompactActionsBreakpoint
                state.loadError?.let { error ->
                    ErrorState(
                        message = error.resolve(),
                        onRetry = { activeViewModel?.loadData() },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .widthIn(max = SizeTokens.DialogContentMaxWidth)
                            .fillMaxWidth()
                            .padding(horizontalPadding),
                    )
                } ?: LazyColumn(
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
                                        Icons.Default.Dns,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(SpacingTokens.RowVertical)
                                            .size(SizeTokens.IconEmphasized),
                                    )
                                }
                                Spacer(Modifier.width(SpacingTokens.Large))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.dns_routing),
                                        style = MaterialTheme.typography.titleLargeEmphasized,
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.dns_selection_summary,
                                            quantityStringResource(
                                                R.plurals.dns_service_count,
                                                state.selectedDnsServices.size,
                                                state.selectedDnsServices.size,
                                            ),
                                            quantityStringResource(
                                                R.plurals.dns_direct_count,
                                                state.selectedDirectServices.size,
                                                state.selectedDirectServices.size,
                                            ),
                                        ),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(Modifier.height(SpacingTokens.Large))
                            SettingRow(
                                title = stringResource(R.string.dns_preset),
                                value = hostsData?.dnsPresets?.getOrElse(state.selectedPresetIndex) { "" }
                                    ?.ifBlank { stringResource(R.string.dns_not_available) }
                                    ?: stringResource(R.string.dns_not_available),
                                subtitle = stringResource(R.string.dns_preset_description),
                                onClick = { if (hostsData != null) showPresetPicker = true },
                                enabled = selectionEnabled,
                            )
                        }
                    }

                    hostsData?.let { data ->
                        item {
                            SectionHeader(stringResource(R.string.dns_services))
                            val allChecked = data.dnsServices.isNotEmpty() &&
                                data.dnsServices.all { it.name in state.selectedDnsServices }
                            DnsServiceItem(
                                name = stringResource(R.string.dns_select_all),
                                info = quantityStringResource(
                                    R.plurals.dns_service_count,
                                    data.dnsServices.size,
                                    data.dnsServices.size,
                                ),
                                checked = allChecked,
                                enabled = selectionEnabled,
                                onCheckedChange = { activeViewModel?.selectAllDns(it) },
                            )
                        }
                        if (data.dnsServices.isEmpty()) {
                            item { EmptyServiceSection(stringResource(R.string.dns_no_services)) }
                        } else {
                            items(
                                items = data.dnsServices,
                                key = { it.name },
                                contentType = { "dns_service" },
                            ) { service ->
                                DnsServiceItem(
                                    name = service.name,
                                    info = quantityStringResource(
                                        R.plurals.dns_domain_count,
                                        service.domains.size,
                                        service.domains.size,
                                    ),
                                    checked = service.name in state.selectedDnsServices,
                                    enabled = selectionEnabled,
                                    onCheckedChange = { activeViewModel?.toggleDnsService(service.name) },
                                )
                            }
                        }

                        item {
                            SectionHeader(stringResource(R.string.dns_direct_services))
                            val allChecked = data.directServices.isNotEmpty() &&
                                data.directServices.all { it.name in state.selectedDirectServices }
                            DnsServiceItem(
                                name = stringResource(R.string.dns_select_all_direct),
                                info = quantityStringResource(
                                    R.plurals.dns_direct_count,
                                    data.directServices.size,
                                    data.directServices.size,
                                ),
                                checked = allChecked,
                                enabled = selectionEnabled,
                                onCheckedChange = { activeViewModel?.selectAllDirect(it) },
                            )
                        }
                        if (data.directServices.isEmpty()) {
                            item { EmptyServiceSection(stringResource(R.string.dns_no_direct_services)) }
                        } else {
                            items(
                                items = data.directServices,
                                key = { it.name },
                                contentType = { "direct_service" },
                            ) { service ->
                                DnsServiceItem(
                                    name = service.name,
                                    info = quantityStringResource(
                                        R.plurals.dns_entry_count,
                                        service.entries.size,
                                        service.entries.size,
                                    ),
                                    checked = service.name in state.selectedDirectServices,
                                    enabled = selectionEnabled,
                                    onCheckedChange = { activeViewModel?.toggleDirectService(service.name) },
                                )
                            }
                        }
                    }

                    item {
                        Spacer(Modifier.height(SpacingTokens.Compact))
                        AdaptiveActionGroup(stacked = compactActions) { buttonModifier ->
                            FilledTonalButton(
                                onClick = { showResetConfirmation = true },
                                enabled = selectionEnabled,
                                shape = MaterialTheme.shapes.extraLarge,
                                modifier = buttonModifier,
                            ) {
                                Icon(Icons.Default.RestartAlt, contentDescription = null)
                                Spacer(Modifier.width(SpacingTokens.Small))
                                Text(stringResource(R.string.action_reset))
                            }
                            val success = MaterialTheme.extendedColors.success
                            Button(
                                onClick = { activeViewModel?.applyDns() },
                                enabled = selectionEnabled,
                                shape = MaterialTheme.shapes.extraLarge,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = success.container,
                                    contentColor = success.onContainer,
                                ),
                                modifier = buttonModifier,
                            ) {
                                Icon(Icons.Default.Dns, contentDescription = null)
                                Spacer(Modifier.width(SpacingTokens.Small))
                                Text(stringResource(R.string.action_apply))
                            }
                        }
                    }
                }
            }
        }
        LoadingOverlay(
            text = state.loadingText?.resolve().orEmpty(),
            visible = state.isLoading,
        )
    }
}

@Composable
private fun DnsPresetDialog(
    presets: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { Icon(Icons.Default.Dns, contentDescription = null) },
        title = { Text(stringResource(R.string.dns_preset)) },
        text = {
            if (presets.isEmpty()) {
                Text(
                    text = stringResource(R.string.dns_no_presets),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.selectableGroup()) {
                    itemsIndexed(
                        items = presets,
                        key = { _, name -> name },
                        contentType = { _, _ -> "dns_preset" },
                    ) { index, name ->
                        val selected = index == selectedIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = SizeTokens.MinimumTouchTarget)
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onSelect(index) },
                                )
                                .padding(vertical = SpacingTokens.ItemVertical),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                modifier = Modifier.clearAndSetSemantics { },
                            )
                            Spacer(Modifier.width(SpacingTokens.Medium))
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        color = error.errorContainer,
        contentColor = error.onErrorContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(SpacingTokens.ExtraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.Illustration),
            )
            Spacer(Modifier.height(SpacingTokens.Medium))
            Text(
                stringResource(R.string.dns_load_error),
                style = MaterialTheme.typography.titleLargeEmphasized,
            )
            Spacer(Modifier.height(SpacingTokens.Compact))
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(SpacingTokens.Large))
            Button(onClick = onRetry, shape = MaterialTheme.shapes.extraLarge) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(SpacingTokens.Small))
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}

@Composable
private fun EmptyServiceSection(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(SpacingTokens.Large),
        )
    }
}
