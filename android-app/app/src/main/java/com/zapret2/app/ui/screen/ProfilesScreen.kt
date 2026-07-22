package com.zapret2.app.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.data.PresetProfile
import com.zapret2.app.data.ProfileListEntry
import com.zapret2.app.data.StrategyCatalogEntry
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.LoadingOverlay
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.viewmodel.ProfilesUiState
import com.zapret2.app.viewmodel.ProfilesViewModel

@Composable
fun ProfilesScreen(
    viewModel: ProfilesViewModel? = null,
    previewState: ProfilesUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: ProfilesUiState()
    val snackbar = remember { SnackbarHostState() }

    LifecycleStartEffect(activeViewModel) {
        activeViewModel?.onScreenEntered()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }
    AppSnackbarEffect(state.message, snackbar) { activeViewModel?.clearMessage() }

    val selectedProfile = state.strategyProfileIndex?.let { state.document?.profiles?.getOrNull(it) }
    if (selectedProfile != null) {
        StrategyDialog(
            profileName = selectedProfile.name,
            items = state.strategies,
            onDismiss = { activeViewModel?.closeStrategyPicker() },
            onSelect = { activeViewModel?.selectStrategy(it) },
        )
    }
    val renamedProfile = state.renameProfileIndex?.let { state.document?.profiles?.getOrNull(it) }
    if (renamedProfile != null) {
        RenameDialog(
            value = state.renameDraft,
            onValueChange = { activeViewModel?.updateRenameDraft(it) },
            onDismiss = { activeViewModel?.closeRename() },
            onSave = { activeViewModel?.saveRename() },
        )
    }
    val selectorTarget = state.selectorTarget
    val selectorProfile = selectorTarget?.let { state.document?.profiles?.getOrNull(it.profileIndex) }
    val selectorLine = selectorTarget?.let { selectorProfile?.selectors?.getOrNull(it.selectorIndex) }
    if (selectorProfile != null && selectorLine != null) {
        ListSelectorDialog(
            title = selectorLine.substringBefore('=').removePrefix("--"),
            items = state.listEntries,
            onDismiss = { activeViewModel?.closeSelectorPicker() },
            onSelect = { activeViewModel?.selectList(it) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(SpacingTokens.Large),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium),
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(com.zapret2.app.R.string.profiles_title), style = MaterialTheme.typography.headlineMedium)
                            Text(
                                state.document?.fileName ?: stringResource(com.zapret2.app.R.string.profiles_active_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { activeViewModel?.load() }, enabled = !state.isLoading) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(com.zapret2.app.R.string.profiles_refresh))
                        }
                    }
                }
                if (state.error && !state.isLoading) {
                    item {
                        Text(
                            stringResource(com.zapret2.app.R.string.profiles_read_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                items(
                    items = state.document?.profiles.orEmpty(),
                    key = PresetProfile::stableId,
                    contentType = { "profile" },
                ) { profile ->
                    ProfileCard(
                        profile = profile,
                        total = state.document?.profiles?.size ?: 0,
                        enabled = !state.isLoading,
                        onEnabled = { activeViewModel?.setEnabled(profile.index, it) },
                        onRename = { activeViewModel?.openRename(profile.index) },
                        onSelector = { selectorIndex ->
                            activeViewModel?.openSelectorPicker(profile.index, selectorIndex)
                        },
                        onStrategy = { activeViewModel?.openStrategyPicker(profile.index) },
                        onMoveUp = { activeViewModel?.move(profile.index, -1) },
                        onMoveDown = { activeViewModel?.move(profile.index, 1) },
                    )
                }
            }
        }
        LoadingOverlay(text = stringResource(com.zapret2.app.R.string.profiles_saving), visible = state.isLoading)
    }
}

@Composable
private fun ProfileCard(
    profile: PresetProfile,
    total: Int,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onSelector: (Int) -> Unit,
    onStrategy: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.fillMaxWidth().padding(SpacingTokens.Large)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        profile.filters.joinToString(" · ") { it.removePrefix("--") },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = profile.enabled, onCheckedChange = onEnabled, enabled = enabled)
            }
            Spacer(Modifier.height(SpacingTokens.Small))
            Text(
                profile.strategies.joinToString(" → ") { it.removePrefix("--lua-desync=").substringBefore(':') },
                style = MaterialTheme.typography.bodyMedium,
            )
            profile.selectors.forEachIndexed { index, selector ->
                TextButton(
                    onClick = { onSelector(index) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        selector.removePrefix("--"),
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(SpacingTokens.Medium))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(
                    onClick = onStrategy,
                    enabled = enabled && profile.catalogScope != null,
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null)
                    Spacer(Modifier.width(SpacingTokens.Small))
                    Text(stringResource(com.zapret2.app.R.string.profiles_choose_strategy))
                }
                IconButton(onClick = onRename, enabled = enabled) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(com.zapret2.app.R.string.profiles_rename))
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onMoveUp, enabled = enabled && profile.index > 0) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = stringResource(com.zapret2.app.R.string.profiles_move_up))
                }
                IconButton(onClick = onMoveDown, enabled = enabled && profile.index + 1 < total) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = stringResource(com.zapret2.app.R.string.profiles_move_down))
                }
            }
        }
    }
}

@Composable
private fun RenameDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(com.zapret2.app.R.string.profiles_rename)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text(stringResource(com.zapret2.app.R.string.profiles_name)) },
            )
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = value.isNotBlank()) {
                Text(stringResource(com.zapret2.app.R.string.profiles_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.zapret2.app.R.string.profiles_close))
            }
        },
    )
}

@Composable
private fun ListSelectorDialog(
    title: String,
    items: List<ProfileListEntry>,
    onDismiss: () -> Unit,
    onSelect: (ProfileListEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)) {
                items(
                    items = items,
                    key = ProfileListEntry::relativePath,
                    contentType = { "list-entry" },
                ) { entry ->
                    TextButton(onClick = { onSelect(entry) }, modifier = Modifier.fillMaxWidth()) {
                        Text(entry.fileName, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.zapret2.app.R.string.profiles_close))
            }
        },
    )
}

@Composable
private fun StrategyDialog(
    profileName: String,
    items: List<StrategyCatalogEntry>,
    onDismiss: () -> Unit,
    onSelect: (StrategyCatalogEntry) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(profileName) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)) {
                items(
                    items = items,
                    key = StrategyCatalogEntry::id,
                    contentType = { "strategy" },
                ) { strategy ->
                    TextButton(onClick = { onSelect(strategy) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(strategy.name)
                            if (strategy.description.isNotBlank()) {
                                Text(
                                    strategy.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(com.zapret2.app.R.string.profiles_close))
            }
        },
    )
}
