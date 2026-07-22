package com.zapret2.app.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zapret2.app.ui.theme.SizeTokens
import com.zapret2.app.ui.theme.SpacingTokens
import com.zapret2.app.R
import com.zapret2.app.data.MAX_PACKET_COUNT
import com.zapret2.app.data.ServiceLifecycleController
import com.zapret2.app.ui.UiText
import com.zapret2.app.ui.resolve
import com.zapret2.app.ui.components.AppSnackbarEffect
import com.zapret2.app.ui.components.AdaptiveEqualWidthGroup
import com.zapret2.app.ui.components.AppSnackbarMessage
import com.zapret2.app.ui.components.ContentCard
import com.zapret2.app.ui.components.SectionHeader
import com.zapret2.app.ui.components.SettingRow
import com.zapret2.app.ui.components.SettingToggleRow
import com.zapret2.app.ui.components.StatusIndicator
import com.zapret2.app.ui.components.UpdateDialog
import com.zapret2.app.ui.components.LocalReducedMotionEnabled
import com.zapret2.app.ui.theme.MonospaceStyle
import com.zapret2.app.ui.theme.SemanticColorFamily
import com.zapret2.app.ui.theme.MotionTokens
import com.zapret2.app.ui.theme.extendedColors
import com.zapret2.app.viewmodel.ControlDialogKind
import com.zapret2.app.viewmodel.ControlStatus
import com.zapret2.app.viewmodel.ControlUiState
import com.zapret2.app.viewmodel.ControlViewModel
import com.zapret2.app.viewmodel.FullRollbackUiState
import com.zapret2.app.viewmodel.PacketTarget
import com.zapret2.app.viewmodel.labelRes

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ControlScreen(
    viewModel: ControlViewModel? = null,
    previewState: ControlUiState? = null,
) {
    val activeViewModel = viewModel ?: if (previewState == null) hiltViewModel() else null
    val runtimeState = activeViewModel?.uiState?.collectAsStateWithLifecycle()
    val state = previewState ?: runtimeState?.value ?: ControlUiState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val errorDetailsLabel = stringResource(R.string.control_error_details)
    var localSnackbar by remember { mutableStateOf<AppSnackbarMessage?>(null) }

    LifecycleStartEffect(activeViewModel) {
        activeViewModel?.onScreenStarted()
        onStopOrDispose { activeViewModel?.onScreenStopped() }
    }

    AppSnackbarEffect(
        message = localSnackbar,
        hostState = snackbarHostState,
        onConsumed = { consumed -> if (localSnackbar === consumed) localSnackbar = null },
    )
    AppSnackbarEffect(state.message, snackbarHostState) { activeViewModel?.clearMessage() }

    state.updateRelease
        ?.takeIf { state.pendingDialog == ControlDialogKind.UPDATE }
        ?.let { release ->
        UpdateDialog(
            version = release.version,
            changelog = release.changelog,
            hasApk = release.apkUrl != null,
            hasModule = release.moduleUrl != null,
            isUpdating = state.isUpdating,
            updateProgress = state.updateProgress,
            updateStatus = state.updateStatus?.resolve().orEmpty(),
            onDismiss = { activeViewModel?.dismissDialog() },
            onUpdate = { activeViewModel?.updateAll(release) },
        )
    }

    state.errorDialog
        ?.takeIf { state.pendingDialog == ControlDialogKind.ERROR }
        ?.let { error ->
        val details = error.details.resolve()
        ErrorDetailsDialog(
            title = stringResource(error.kind.titleRes),
            details = details,
            onCopy = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(
                    ClipData.newPlainText(errorDetailsLabel, details),
                )
                localSnackbar = AppSnackbarMessage(
                    sequence = System.nanoTime(),
                    text = UiText.Resource(R.string.control_copied),
                )
            },
            onDismiss = { activeViewModel?.dismissDialog() },
        )
    }

    state.packetTarget
        ?.takeIf { state.pendingDialog == ControlDialogKind.PACKET }
        ?.let { target ->
        PacketCountDialog(
            title = stringResource(target.titleRes),
            textValue = state.packetDraft,
            onTextValueChange = { activeViewModel?.updatePacketDraft(it) },
            onConfirm = { activeViewModel?.confirmPacketDialog() },
            onDismiss = { activeViewModel?.dismissDialog() },
        )
    }

    if (
        state.pendingDialog == ControlDialogKind.FULL_ROLLBACK_CONFIRM &&
        state.fullRollback is FullRollbackUiState.Confirmation
    ) {
        FullRollbackConfirmationDialog(
            onConfirm = { activeViewModel?.confirmFullRollback() },
            onDismiss = { activeViewModel?.dismissDialog() },
        )
    }

    if (state.fullRollback is FullRollbackUiState.InProgress) {
        FullRollbackProgressDialog()
    }

    (state.fullRollback as? FullRollbackUiState.Result)
        ?.takeIf { state.pendingDialog == ControlDialogKind.FULL_ROLLBACK_RESULT }
        ?.let { result ->
            FullRollbackResultDialog(
                result = result,
                onDismiss = { activeViewModel?.dismissDialog() },
            )
        }

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
            val compactGroups = maxWidth < SizeTokens.CompactActionsBreakpoint
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
                    ServiceStatusCard(
                        state = state,
                        onToggle = { activeViewModel?.toggleService() },
                    )
                }

                state.lastResult?.let { lastResult ->
                    item {
                        SectionHeader(stringResource(R.string.control_last_result))
                        ContentCard(
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        ) {
                            Text(
                                text = stringResource(lastResult.messageRes),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }

                if (state.showQuicBanner) {
                    item {
                        QuicNotice(onDismiss = { activeViewModel?.dismissQuicBanner() })
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.control_environment))
                    ContentCard {
                        val checkingEnvironment = state.status == ControlStatus.CHECKING
                        AdaptiveEqualWidthGroup(stacked = compactGroups) { itemModifier ->
                            ReadinessBadge(
                                label = stringResource(R.string.control_root),
                                ready = state.hasRootAccess.takeUnless { checkingEnvironment },
                                modifier = itemModifier,
                            )
                            ReadinessBadge(
                                label = stringResource(R.string.control_module),
                                ready = state.isModuleOperational.takeUnless { checkingEnvironment },
                                modifier = itemModifier,
                            )
                            ReadinessBadge(
                                label = stringResource(R.string.term_nfqueue),
                                ready = state.nfqueueSupported.takeUnless { checkingEnvironment },
                                modifier = itemModifier,
                            )
                        }
                        Spacer(Modifier.height(SpacingTokens.Small))
                        SettingRow(
                            title = stringResource(R.string.control_module_state),
                            value = stringResource(state.moduleInstallState.labelRes),
                        )
                        Spacer(Modifier.height(SpacingTokens.Small))
                        SettingRow(
                            title = stringResource(R.string.control_module_version),
                            value = state.moduleVersion.ifEmpty {
                                stringResource(R.string.control_not_available)
                            },
                        )
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.control_settings))
                    ContentCard {
                        SettingToggleRow(
                            title = stringResource(R.string.control_autostart),
                            checked = state.autostart,
                            onCheckedChange = { activeViewModel?.setAutostart(it) },
                            icon = Icons.Default.PowerSettingsNew,
                            enabled = state.canEditSettings,
                        )
                        Spacer(Modifier.height(SpacingTokens.Small))
                        SettingToggleRow(
                            title = stringResource(R.string.control_wifi_only),
                            checked = false,
                            onCheckedChange = {},
                            icon = Icons.Default.Wifi,
                            subtitle = stringResource(R.string.control_wifi_only_body),
                            enabled = false,
                        )
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.control_packet_interception))
                    ContentCard {
                        SettingRow(
                            title = stringResource(R.string.control_outgoing_packets),
                            value = state.pktOut.toString(),
                            subtitle = stringResource(R.string.term_pkt_out),
                            icon = Icons.AutoMirrored.Filled.CallMade,
                            onClick = { activeViewModel?.showPacketDialog(PacketTarget.OUT) },
                            enabled = state.canEditSettings,
                        )
                        Spacer(Modifier.height(SpacingTokens.Small))
                        SettingRow(
                            title = stringResource(R.string.control_incoming_packets),
                            value = state.pktIn.toString(),
                            subtitle = stringResource(R.string.term_pkt_in),
                            icon = Icons.AutoMirrored.Filled.CallReceived,
                            onClick = { activeViewModel?.showPacketDialog(PacketTarget.IN) },
                            enabled = state.canEditSettings,
                        )
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.control_network))
                    ContentCard {
                        SettingRow(
                            title = stringResource(R.string.control_network_type),
                            value = state.networkType.resolve(),
                        )
                        Spacer(Modifier.height(SpacingTokens.Small))
                        SettingRow(
                            title = stringResource(R.string.term_iptables),
                            value = if (state.iptablesActive) {
                                stringResource(R.string.state_active)
                            } else {
                                stringResource(R.string.state_inactive)
                            },
                        )
                        Spacer(Modifier.height(SpacingTokens.Small))
                        SettingRow(
                            title = stringResource(R.string.control_nfq_rules),
                            value = state.nfqueueRulesCount.toString(),
                        )
                    }
                }

                if (state.processStats.pid.isNotEmpty()) {
                    item {
                        SectionHeader(stringResource(R.string.control_process))
                        ContentCard {
                            SettingRow(title = stringResource(R.string.term_pid), value = state.processStats.pid)
                            state.processStats.memory.takeIf { it.isNotEmpty() }?.let {
                                Spacer(Modifier.height(SpacingTokens.Small))
                                SettingRow(title = stringResource(R.string.control_memory), value = it)
                            }
                            state.processStats.cpu.takeIf { it.isNotEmpty() }?.let {
                                Spacer(Modifier.height(SpacingTokens.Small))
                                SettingRow(title = stringResource(R.string.term_cpu), value = it)
                            }
                            state.processStats.threads.takeIf { it.isNotEmpty() }?.let {
                                Spacer(Modifier.height(SpacingTokens.Small))
                                SettingRow(title = stringResource(R.string.control_threads), value = it)
                            }
                        }
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.control_full_rollback_section))
                    FilledTonalButton(
                        onClick = { activeViewModel?.showFullRollbackConfirmation() },
                        enabled = state.canFullRollback,
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null)
                        Spacer(Modifier.width(SpacingTokens.Small))
                        Text(stringResource(R.string.control_full_rollback_action))
                    }
                }

                item {
                    SectionHeader(stringResource(R.string.control_updates))
                    FilledTonalButton(
                        onClick = { activeViewModel?.checkForUpdates() },
                        enabled = !state.isUpdating &&
                            !state.isCheckingForUpdates &&
                            !state.isToggling &&
                            !state.isSavingSettings &&
                            state.status != ControlStatus.CHECKING &&
                            !state.isFullRollbackInProgress,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isCheckingForUpdates) {
                            LoadingIndicator(modifier = Modifier.size(SizeTokens.IconSmall))
                        } else {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null)
                        }
                        Spacer(Modifier.width(SpacingTokens.Small))
                        Text(
                            stringResource(
                                if (state.isCheckingForUpdates) {
                                    R.string.control_checking_updates
                                } else {
                                    R.string.control_check_updates
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ServiceStatusCard(
    state: ControlUiState,
    onToggle: () -> Unit,
) {
    val reduceMotion = LocalReducedMotionEnabled.current
    val checking = state.status == ControlStatus.CHECKING
    val success = MaterialTheme.extendedColors.success
    val error = MaterialTheme.colorScheme.run {
        SemanticColorFamily(error, onError, errorContainer, onErrorContainer)
    }
    val warning = MaterialTheme.extendedColors.warning
    val serviceColors = if (state.canStopService) error else success
    val statusDescription = stringResource(state.status.labelRes)
    val statusColor by animateColorAsState(
        targetValue = when (state.status) {
            ControlStatus.RUNNING -> success.color
            ControlStatus.DEGRADED -> warning.color
            ControlStatus.ROOT_DENIED,
            ControlStatus.ROOT_MANAGER_UNAVAILABLE,
            ControlStatus.ROOT_SHELL_FAILED,
            ControlStatus.ROOT_TIMEOUT,
            ControlStatus.UNAVAILABLE,
            -> error.color
            ControlStatus.CHECKING,
            ControlStatus.STOPPED,
            -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(
            if (reduceMotion) MotionTokens.DurationImmediate else MotionTokens.DurationLong,
        ),
        label = "service status color",
    )
    val buttonContainer by animateColorAsState(
        targetValue = serviceColors.container,
        animationSpec = tween(
            if (reduceMotion) MotionTokens.DurationImmediate else MotionTokens.DurationLong,
        ),
        label = "service action container",
    )
    val buttonContent by animateColorAsState(
        targetValue = serviceColors.onContainer,
        animationSpec = tween(
            if (reduceMotion) MotionTokens.DurationImmediate else MotionTokens.DurationLong,
        ),
        label = "service action content",
    )

    ContentCard(
        modifier = Modifier.semantics(mergeDescendants = true) {
            stateDescription = statusDescription
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checking) {
                LoadingIndicator(modifier = Modifier.size(SizeTokens.IconProminent))
            } else {
                StatusIndicator(
                    isActive = state.isRunning,
                    size = SpacingTokens.Medium,
                    exposeState = false,
                )
            }
            Spacer(Modifier.width(SpacingTokens.Large))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(state.status.labelRes),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    color = statusColor,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                if (state.uptime.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.control_uptime, state.uptime),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(SpacingTokens.CardContent))
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isToggling &&
                !state.isCheckingForUpdates &&
                !state.isUpdating &&
                !state.isSavingSettings &&
                !state.isFullRollbackInProgress &&
                !checking &&
                (state.canStopService || (
                    state.hasRootAccess && state.isModuleOperational && state.nfqueueSupported
                )),
            shape = MaterialTheme.shapes.extraLarge,
            colors = ButtonDefaults.buttonColors(
                containerColor = buttonContainer,
                contentColor = buttonContent,
            ),
        ) {
            if (state.isToggling) {
                LoadingIndicator(modifier = Modifier.size(SizeTokens.IconEmphasized))
                Spacer(Modifier.width(SpacingTokens.Small))
                Text(stringResource(R.string.control_applying))
            } else {
                Text(
                    stringResource(
                        if (state.canStopService) {
                            R.string.control_stop_service
                        } else {
                            R.string.control_start_service
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun ReadinessBadge(
    label: String,
    ready: Boolean?,
    modifier: Modifier = Modifier,
) {
    val colors = when (ready) {
        true -> MaterialTheme.extendedColors.success
        false -> MaterialTheme.colorScheme.run {
            SemanticColorFamily(error, onError, errorContainer, onErrorContainer)
        }
        null -> MaterialTheme.extendedColors.info
    }
    val availabilityDescription = when (ready) {
        true -> stringResource(R.string.state_available, label)
        false -> stringResource(R.string.state_unavailable, label)
        null -> stringResource(R.string.state_checking, label)
    }
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            stateDescription = availabilityDescription
        },
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.Medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = when (ready) {
                    true -> Icons.Default.CheckCircle
                    false -> Icons.Default.Error
                    null -> Icons.Default.Info
                },
                contentDescription = null,
                modifier = Modifier.size(SizeTokens.IconCompact),
            )
            Spacer(Modifier.height(SpacingTokens.Compact))
            Text(text = label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun QuicNotice(onDismiss: () -> Unit) {
    val warning = MaterialTheme.extendedColors.warning
    Surface(
        color = warning.container,
        contentColor = warning.onContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Row(
            modifier = Modifier.padding(start = SpacingTokens.Large, top = SpacingTokens.Medium, bottom = SpacingTokens.Medium, end = SpacingTokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Info, contentDescription = null)
            Spacer(Modifier.width(SpacingTokens.Medium))
            Text(
                text = stringResource(R.string.control_quic_notice),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_dismiss_quic_notice),
                )
            }
        }
    }
}

@Composable
private fun FullRollbackConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            Icon(
                Icons.Default.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.control_full_rollback_confirm_title)) },
        text = {
            Text(
                text = stringResource(R.string.control_full_rollback_confirm_body),
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.control_full_rollback_confirm_action),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun FullRollbackProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(stringResource(R.string.control_full_rollback_progress_title)) },
        text = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Large),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LoadingIndicator(modifier = Modifier.size(SizeTokens.LoadingMedium))
                Text(
                    text = stringResource(R.string.control_full_rollback_progress_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun FullRollbackResultDialog(
    result: FullRollbackUiState.Result,
    onDismiss: () -> Unit,
) {
    val success = result.outcome == ServiceLifecycleController.FullRollbackOutcome.COMPLETE
    AlertDialog(
        onDismissRequest = {},
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            Icon(
                imageVector = if (success) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (success) {
                    MaterialTheme.extendedColors.success.color
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        },
        title = {
            Text(
                stringResource(
                    if (success) {
                        R.string.control_full_rollback_success_title
                    } else {
                        R.string.control_full_rollback_failure_title
                    },
                ),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)) {
                Text(
                    text = stringResource(
                        if (success) {
                            R.string.control_full_rollback_success_body
                        } else {
                            R.string.control_full_rollback_failure_body
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (result.rebootRequired) {
                    Text(
                        text = stringResource(R.string.control_full_rollback_reboot_required),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!success) {
                    Text(
                        text = result.diagnostic.ifBlank {
                            stringResource(R.string.control_full_rollback_failure_unknown)
                        },
                        style = MonospaceStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = SizeTokens.DialogContentTallMaxHeight)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun ErrorDetailsDialog(
    title: String,
    details: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        icon = {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(title, color = MaterialTheme.colorScheme.error) },
        text = {
            Text(
                text = details,
                style = MonospaceStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = SizeTokens.DialogContentExtraTallMaxHeight)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.action_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

@Composable
private fun PacketCountDialog(
    title: String,
    textValue: String,
    onTextValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val parsedValue = textValue.toIntOrNull()
    val isValid = parsedValue != null && parsedValue in 1..MAX_PACKET_COUNT

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.extraLarge,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = textValue,
                onValueChange = onTextValueChange,
                singleLine = true,
                isError = textValue.isNotEmpty() && !isValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                label = { Text(stringResource(R.string.control_packet_count)) },
                supportingText = { Text(stringResource(R.string.control_packet_range)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = isValid,
            ) { Text(stringResource(R.string.action_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
