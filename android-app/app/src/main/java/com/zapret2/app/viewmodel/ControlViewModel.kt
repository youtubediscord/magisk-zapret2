package com.zapret2.app.viewmodel

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapret2.app.R
import com.zapret2.app.data.ArtifactValidationReason
import com.zapret2.app.data.DownloadFailureReason
import com.zapret2.app.data.LifecycleErrorContract
import com.zapret2.app.data.ModuleInstallState
import com.zapret2.app.data.ModuleEnvironmentSnapshot
import com.zapret2.app.data.ModuleMutationState
import com.zapret2.app.data.ModuleMutationCoordinator
import com.zapret2.app.data.ModulePurgeAppDataCleaner
import com.zapret2.app.data.ModulePurgeController
import com.zapret2.app.data.ModuleServiceAccess
import com.zapret2.app.data.NetworkStatsManager
import com.zapret2.app.data.PendingModuleState
import com.zapret2.app.data.ProtectedTextRead
import com.zapret2.app.data.RuntimeConfigReadResult
import com.zapret2.app.data.RuntimeConfigMutationResult
import com.zapret2.app.data.RuntimeConfigStore
import com.zapret2.app.data.diagnosticText
import com.zapret2.app.data.diagnosticTextOrNull
import com.zapret2.app.data.RuntimeLogRepository
import com.zapret2.app.data.ServiceEventBus
import com.zapret2.app.data.ServiceEventSource
import com.zapret2.app.data.ServiceLifecycleController
import com.zapret2.app.data.UpdateFailure
import com.zapret2.app.data.UpdateManager
import com.zapret2.app.data.UpdateProgress
import com.zapret2.app.data.UpdateStage
import com.zapret2.app.data.UpdateTerminalOutcome
import com.zapret2.app.data.Zapret2ModuleRepository
import com.zapret2.app.data.toTerminalOutcome
import com.zapret2.app.ui.UiText
import com.zapret2.app.ui.labelRes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

enum class ControlStatus(@param:StringRes val labelRes: Int) {
    CHECKING(R.string.control_service_checking),
    LIFECYCLE_BUSY(R.string.control_service_lifecycle_busy),
    RUNNING(R.string.control_service_running),
    STOPPED(R.string.control_service_stopped),
    DEGRADED(R.string.control_status_degraded),
    ROOT_DENIED(R.string.control_status_root_denied),
    ROOT_MANAGER_UNAVAILABLE(R.string.control_status_root_manager_unavailable),
    ROOT_SHELL_FAILED(R.string.control_status_root_shell_failed),
    ROOT_TIMEOUT(R.string.control_status_root_timeout),
    ROOT_OPERATION_BUSY(R.string.control_status_root_operation_busy),
    NOT_INSTALLED(R.string.control_status_not_installed),
    REBOOT_REQUIRED(R.string.control_status_reboot_required),
    MODULE_NOT_READY(R.string.control_status_module_not_ready),
    UNAVAILABLE(R.string.control_status_unavailable),
}

internal fun confirmedRunning(
    serviceStatus: ServiceLifecycleController.ServiceStatus,
): Boolean = serviceStatus.healthy

internal fun projectedControlStatus(
    serviceStatus: ServiceLifecycleController.ServiceStatus,
    canStopService: Boolean,
): ControlStatus = when {
    !serviceStatus.rootGranted -> serviceStatus.rootAccessState.toControlStatus()
    serviceStatus.lifecycleState == ServiceLifecycleController.LifecycleState.OWNED ->
        ControlStatus.LIFECYCLE_BUSY
    serviceStatus.lifecycleState == ServiceLifecycleController.LifecycleState.ACTIVE ->
        ControlStatus.LIFECYCLE_BUSY
    serviceStatus.lifecycleState in setOf(
        ServiceLifecycleController.LifecycleState.AMBIGUOUS,
        ServiceLifecycleController.LifecycleState.RECOVERY_FAILED,
    ) -> ControlStatus.UNAVAILABLE
    serviceStatus.error != null -> ControlStatus.UNAVAILABLE
    confirmedRunning(serviceStatus) -> ControlStatus.RUNNING
    canStopService -> ControlStatus.DEGRADED
    else -> ControlStatus.STOPPED
}

internal fun ServiceLifecycleController.RootAccessState.toControlStatus(): ControlStatus = when (this) {
    ServiceLifecycleController.RootAccessState.GRANTED -> ControlStatus.UNAVAILABLE
    ServiceLifecycleController.RootAccessState.DENIED -> ControlStatus.ROOT_DENIED
    ServiceLifecycleController.RootAccessState.MANAGER_UNAVAILABLE ->
        ControlStatus.ROOT_MANAGER_UNAVAILABLE
    ServiceLifecycleController.RootAccessState.SHELL_FAILURE -> ControlStatus.ROOT_SHELL_FAILED
    ServiceLifecycleController.RootAccessState.TIMEOUT -> ControlStatus.ROOT_TIMEOUT
    ServiceLifecycleController.RootAccessState.BUSY -> ControlStatus.ROOT_OPERATION_BUSY
}

internal val ModuleInstallState.labelRes: Int
    @StringRes get() = when (this) {
        ModuleInstallState.UNKNOWN -> R.string.control_module_state_unknown
        ModuleInstallState.MISSING -> R.string.control_module_state_missing
        ModuleInstallState.READY -> R.string.control_module_state_ready
        ModuleInstallState.DISABLED -> R.string.control_module_state_disabled
        ModuleInstallState.REMOVAL_PENDING -> R.string.control_module_state_removal_pending
        ModuleInstallState.PARTIAL -> R.string.control_module_state_partial
        ModuleInstallState.UNSUPPORTED_ABI -> R.string.control_module_state_unsupported_abi
        ModuleInstallState.UNREADABLE -> R.string.control_module_state_unreadable
    }

@get:StringRes
internal val ControlUiState.moduleStateLabelRes: Int
    get() = when {
        moduleMutationState == ModuleMutationState.IN_PROGRESS ->
            R.string.control_service_lifecycle_busy
        moduleMutationState == ModuleMutationState.BLOCKED ->
            R.string.control_module_state_lifecycle_blocked
        pendingModuleState == PendingModuleState.READY &&
            moduleInstallState == ModuleInstallState.MISSING ->
            R.string.control_module_state_installed_reboot
        pendingModuleState == PendingModuleState.READY ->
            R.string.control_module_state_update_reboot
        pendingModuleState == PendingModuleState.PARTIAL ->
            R.string.control_module_state_pending_partial
        pendingModuleState == PendingModuleState.UNSUPPORTED_ABI ->
            R.string.control_module_state_pending_unsupported_abi
        pendingModuleState == PendingModuleState.UNREADABLE ->
            R.string.control_module_state_pending_unreadable
        else -> moduleInstallState.labelRes
    }

internal fun ModuleServiceAccess.statusWithoutQuery(): ControlStatus? = when (this) {
    ModuleServiceAccess.QUERY_ACTIVE -> null
    ModuleServiceAccess.NOT_INSTALLED -> ControlStatus.NOT_INSTALLED
    ModuleServiceAccess.REBOOT_REQUIRED -> ControlStatus.REBOOT_REQUIRED
    ModuleServiceAccess.NOT_READY -> ControlStatus.MODULE_NOT_READY
    ModuleServiceAccess.UNKNOWN -> ControlStatus.UNAVAILABLE
}

internal fun ServiceLifecycleController.LifecycleState.toModuleMutationState(): ModuleMutationState =
    when (this) {
        ServiceLifecycleController.LifecycleState.OWNED,
        ServiceLifecycleController.LifecycleState.ACTIVE,
        -> ModuleMutationState.IN_PROGRESS
        ServiceLifecycleController.LifecycleState.AMBIGUOUS,
        ServiceLifecycleController.LifecycleState.RECOVERY_FAILED,
        -> ModuleMutationState.BLOCKED
        ServiceLifecycleController.LifecycleState.IDLE,
        ServiceLifecycleController.LifecycleState.RECOVERED,
        ServiceLifecycleController.LifecycleState.UNKNOWN,
        -> ModuleMutationState.IDLE
    }

internal fun projectedLifecycleDiagnostic(
    serviceStatus: ServiceLifecycleController.ServiceStatus,
): String? = serviceStatus.lifecycleError
    ?.takeUnless { error ->
        error.isNone ||
            serviceStatus.lifecycleState in setOf(
                ServiceLifecycleController.LifecycleState.OWNED,
                ServiceLifecycleController.LifecycleState.ACTIVE,
            )
    }
    ?.diagnosticText()

enum class ControlDialogKind {
    UPDATE,
    ERROR,
    FULL_ROLLBACK_CONFIRM,
    FULL_ROLLBACK_RESULT,
    MODULE_PURGE_CONFIRM,
    MODULE_PURGE_RESULT,
}

enum class ControlErrorKind(@param:StringRes val titleRes: Int) {
    INITIALIZATION(R.string.control_initialization_failed),
    START_SERVICE(R.string.control_service_start_failed),
    STOP_SERVICE(R.string.control_service_stop_failed),
    SERVICE_OPERATION(R.string.control_service_operation_failed),
    UPDATE(R.string.control_update_failed),
    RESTART_SERVICE(R.string.control_service_restart_failed),
}

enum class ControlLastResult(@param:StringRes val messageRes: Int) {
    SERVICE_STARTED(R.string.control_service_started),
    SERVICE_STOPPED(R.string.control_service_stopped_result),
    SERVICE_FAILED(R.string.control_service_operation_failed),
    ROLLBACK_COMPLETED(R.string.control_full_rollback_success_title),
    ROLLBACK_FAILED(R.string.control_full_rollback_failure_title),
    PURGE_COMPLETED(R.string.control_purge_success_title),
    PURGE_FAILED(R.string.control_purge_failure_title),
    UPDATE_COMPLETED(R.string.control_update_completed),
    UPDATE_REBOOT_REQUIRED(R.string.control_update_installed_reboot),
    UPDATE_APK_PENDING(R.string.control_update_apk_pending),
    UPDATE_APK_PENDING_REBOOT(R.string.control_update_apk_pending_reboot),
    UPDATE_PARTIAL(R.string.control_update_partial),
    UPDATE_PARTIAL_REBOOT(R.string.control_update_partial_reboot),
    UPDATE_FAILED(R.string.control_update_failed),
}

internal fun UpdateProgress.toUiText(): UiText = when (stage) {
    UpdateStage.DOWNLOADING_MODULE -> normalizedPercent?.let {
        UiText.resource(R.string.update_stage_downloading_module_percent, it)
    } ?: UiText.Resource(R.string.update_stage_downloading_module)
    UpdateStage.INSTALLING_MODULE -> UiText.Resource(R.string.update_stage_installing_module)
    UpdateStage.MODULE_INSTALLED -> UiText.Resource(R.string.update_stage_module_installed)
    UpdateStage.DOWNLOADING_APK -> normalizedPercent?.let {
        UiText.resource(R.string.update_stage_downloading_apk_percent, it)
    } ?: UiText.Resource(R.string.update_stage_downloading_apk)
    UpdateStage.VALIDATING_ARTIFACTS -> UiText.Resource(R.string.update_stage_validating_artifacts)
    UpdateStage.OPENING_APK_INSTALLER -> UiText.Resource(R.string.update_stage_opening_apk_installer)
    UpdateStage.APK_INSTALLER_PENDING -> UiText.Resource(R.string.update_stage_apk_installer_pending)
    UpdateStage.COMPLETE -> UiText.Resource(R.string.update_stage_complete)
}

internal fun UpdateManager.UpdateCheckFailure.toUiText(): UiText = when (this) {
    UpdateManager.UpdateCheckFailure.NoInternet ->
        UiText.Resource(R.string.control_update_check_no_internet)
    UpdateManager.UpdateCheckFailure.ConnectionTimeout ->
        UiText.Resource(R.string.control_update_check_timeout)
    UpdateManager.UpdateCheckFailure.SecureConnectionFailed ->
        UiText.Resource(R.string.control_update_check_secure_connection_failed)
    is UpdateManager.UpdateCheckFailure.ServerResponse ->
        UiText.resource(R.string.control_update_check_http_failed, statusCode)
    UpdateManager.UpdateCheckFailure.MetadataTooLarge ->
        UiText.Resource(R.string.control_update_check_metadata_too_large)
    UpdateManager.UpdateCheckFailure.EmptyResponse ->
        UiText.Resource(R.string.control_update_check_empty_response)
    UpdateManager.UpdateCheckFailure.InvalidMetadata ->
        UiText.Resource(R.string.control_update_check_invalid_metadata)
    UpdateManager.UpdateCheckFailure.RequestFailed ->
        UiText.Resource(R.string.control_update_check_failed)
}

internal fun UpdateFailure.toUiText(): UiText = when (this) {
    is UpdateFailure.Download -> reason.toUiText()
    is UpdateFailure.Validation -> reason.toUiText()
    UpdateFailure.UnsupportedAbi -> UiText.Resource(R.string.control_update_unsupported_abi)
    UpdateFailure.ApkInstallerUnavailable ->
        UiText.Resource(R.string.control_update_apk_installer_unavailable)
    UpdateFailure.ModuleRejected -> UiText.Resource(R.string.control_update_module_rejected)
    UpdateFailure.ModuleInstallationFailed ->
        UiText.Resource(R.string.control_update_module_install_failed)
}

private fun ArtifactValidationReason.toUiText(): UiText = UiText.Resource(
    when (this) {
        ArtifactValidationReason.APK_FILE_INVALID -> R.string.control_update_apk_file_invalid
        ArtifactValidationReason.APK_UNREADABLE -> R.string.control_update_apk_unreadable
        ArtifactValidationReason.APK_PACKAGE_ID_MISMATCH ->
            R.string.control_update_apk_package_mismatch
        ArtifactValidationReason.APK_NOT_NEWER -> R.string.control_update_apk_not_newer
        ArtifactValidationReason.APK_VERSION_CODE_MISMATCH ->
            R.string.control_update_apk_version_code_mismatch
        ArtifactValidationReason.APK_VERSION_MISMATCH ->
            R.string.control_update_apk_version_mismatch
        ArtifactValidationReason.INSTALLED_APK_SIGNER_UNAVAILABLE ->
            R.string.control_update_installed_signer_unavailable
        ArtifactValidationReason.APK_SIGNER_UNAVAILABLE ->
            R.string.control_update_apk_signer_unavailable
        ArtifactValidationReason.APK_SIGNER_MISMATCH ->
            R.string.control_update_apk_signer_mismatch
        ArtifactValidationReason.APK_VALIDATION_FAILED ->
            R.string.control_update_apk_validation_failed
        ArtifactValidationReason.MODULE_TOO_MANY_ENTRIES ->
            R.string.control_update_module_too_many_entries
        ArtifactValidationReason.MODULE_UNSAFE_OR_DUPLICATE_PATH ->
            R.string.control_update_module_unsafe_path
        ArtifactValidationReason.MODULE_ENTRY_TOO_LARGE ->
            R.string.control_update_module_entry_too_large
        ArtifactValidationReason.MODULE_EXPANDED_SIZE_TOO_LARGE ->
            R.string.control_update_module_expanded_too_large
        ArtifactValidationReason.MODULE_EMPTY -> R.string.control_update_module_empty
        ArtifactValidationReason.MODULE_IDENTITY_MISSING ->
            R.string.control_update_module_identity_missing
        ArtifactValidationReason.MODULE_PACKAGE_INVALID ->
            R.string.control_update_module_package_invalid
        ArtifactValidationReason.MODULE_VALIDATION_FAILED ->
            R.string.control_update_module_validation_failed
    },
)

private fun DownloadFailureReason.toUiText(): UiText = UiText.Resource(
    when (this) {
        DownloadFailureReason.SECURITY_POLICY_REJECTED ->
            R.string.control_update_download_security_rejected
        DownloadFailureReason.SERVER_REJECTED -> R.string.control_update_download_server_rejected
        DownloadFailureReason.TOO_MANY_REDIRECTS -> R.string.control_update_download_redirects
        DownloadFailureReason.TOO_LARGE -> R.string.control_update_download_too_large
        DownloadFailureReason.STORAGE_UNAVAILABLE -> R.string.control_update_download_storage
        DownloadFailureReason.CHECKSUM_MISMATCH -> R.string.control_update_download_checksum
        DownloadFailureReason.NO_INTERNET -> R.string.control_update_download_no_internet
        DownloadFailureReason.CONNECTION_TIMEOUT -> R.string.control_update_download_timeout
        DownloadFailureReason.SECURE_CONNECTION_FAILED ->
            R.string.control_update_download_secure_connection_failed
        DownloadFailureReason.FAILED -> R.string.control_update_download_failed
    },
)

private fun String.toSafeUpdateDiagnosticOrNull(): String? = sanitizedBoundedUiDiagnostic(this)
    .takeIf(String::isNotBlank)

data class ControlErrorDialog(
    val kind: ControlErrorKind,
    val details: UiText,
)

sealed interface FullRollbackUiState {
    data object Idle : FullRollbackUiState
    data object Confirmation : FullRollbackUiState
    data object InProgress : FullRollbackUiState
    data class Result(
        val outcome: ServiceLifecycleController.FullRollbackOutcome,
        val rebootRequired: Boolean,
        val diagnostic: String,
    ) : FullRollbackUiState
}

sealed interface ModulePurgeUiState {
    data object Idle : ModulePurgeUiState
    data object Confirmation : ModulePurgeUiState
    data object InProgress : ModulePurgeUiState
    data class Result(
        val outcome: ModulePurgeController.Outcome,
        val rebootRequired: Boolean,
        val diagnostic: String,
    ) : ModulePurgeUiState
}

internal object FullRollbackAvailabilityPolicy {
    fun isAvailable(
        status: ControlStatus,
        hasRootAccess: Boolean,
        moduleInstallState: ModuleInstallState,
        isToggling: Boolean,
        isCheckingForUpdates: Boolean,
        isUpdating: Boolean,
        isRollingBack: Boolean,
    ): Boolean =
        status in setOf(ControlStatus.RUNNING, ControlStatus.DEGRADED, ControlStatus.STOPPED) &&
            hasRootAccess &&
            moduleInstallState.allowsFullRollback &&
            !isToggling &&
            !isCheckingForUpdates &&
            !isUpdating &&
            !isRollingBack
}

data class ControlUiState(
    val isRunning: Boolean = false,
    val status: ControlStatus = ControlStatus.CHECKING,
    val uptime: String = "",
    val autostart: Boolean = true,
    val moduleVersion: String = "",
    val networkType: UiText = UiText.Resource(R.string.control_network_checking),
    val iptablesActive: Boolean = false,
    val nfqueueRulesCount: Int = 0,
    val processStats: ProcessStats = ProcessStats(),
    val isToggling: Boolean = false,
    val canStopService: Boolean = false,
    val showQuicBanner: Boolean = false,
    val iptablesDetail: NetworkStatsManager.IptablesDetail = NetworkStatsManager.IptablesDetail(),
    val hasRootAccess: Boolean = false,
    val rootAccessState: ServiceLifecycleController.RootAccessState? = null,
    val moduleInstallState: ModuleInstallState = ModuleInstallState.UNKNOWN,
    val pendingModuleState: PendingModuleState = PendingModuleState.NONE,
    val moduleMutationState: ModuleMutationState = ModuleMutationState.IDLE,
    val nfqueueSupported: Boolean = false,
    val isCheckingForUpdates: Boolean = false,
    val isUpdating: Boolean = false,
    val isSavingSettings: Boolean = false,
    val hasAuthoritativeRuntimeSettings: Boolean = false,
    val moduleDiagnostic: String? = null,
    val updateProgress: Float = 0f,
    val updateStatus: UiText? = null,
    val pendingDialog: ControlDialogKind? = null,
    val updateRelease: UpdateManager.Release? = null,
    val errorDialog: ControlErrorDialog? = null,
    val fullRollback: FullRollbackUiState = FullRollbackUiState.Idle,
    val modulePurge: ModulePurgeUiState = ModulePurgeUiState.Idle,
    val lastResult: ControlLastResult? = null,
    val message: UiText? = null,
) {
    val isModuleOperational: Boolean
        get() = moduleInstallState.isOperational &&
            moduleMutationState == ModuleMutationState.IDLE
    val isFullRollbackInProgress: Boolean get() = fullRollback is FullRollbackUiState.InProgress
    val isModulePurgeInProgress: Boolean get() = modulePurge is ModulePurgeUiState.InProgress
    val canEditSettings: Boolean get() = status in setOf(
        ControlStatus.RUNNING,
        ControlStatus.DEGRADED,
        ControlStatus.STOPPED,
    ) && isModuleOperational && hasRootAccess &&
        hasAuthoritativeRuntimeSettings &&
        !isToggling && !isCheckingForUpdates && !isUpdating &&
        !isSavingSettings && !isFullRollbackInProgress && !isModulePurgeInProgress
    val canFullRollback: Boolean
        get() = moduleMutationState == ModuleMutationState.IDLE &&
            FullRollbackAvailabilityPolicy.isAvailable(
                status = status,
                hasRootAccess = hasRootAccess,
                moduleInstallState = moduleInstallState,
                isToggling = isToggling || isSavingSettings,
                isCheckingForUpdates = isCheckingForUpdates,
                isUpdating = isUpdating,
                isRollingBack = isFullRollbackInProgress || isModulePurgeInProgress,
            )
    val canPurgeModule: Boolean get() = status in setOf(
        ControlStatus.RUNNING,
        ControlStatus.DEGRADED,
        ControlStatus.STOPPED,
    ) && hasRootAccess && moduleMutationState == ModuleMutationState.IDLE &&
        moduleInstallState.allowsFullRollback &&
        !isToggling && !isCheckingForUpdates && !isUpdating && !isSavingSettings &&
        !isFullRollbackInProgress && !isModulePurgeInProgress
}

data class ProcessStats(
    val pid: String = "",
    val memory: String = "",
    val cpu: String = "",
    val threads: String = "",
    val uptime: String = ""
)

private const val KEY_DIALOG_KIND = "control_dialog_kind"
private const val KEY_ERROR_KIND = "control_error_kind"
private const val KEY_ERROR_DETAIL_RESOURCE = "control_error_detail_resource"
private const val KEY_ERROR_DETAIL_DYNAMIC = "control_error_detail_dynamic"
private const val KEY_ROLLBACK_IN_PROGRESS = "control_full_rollback_in_progress"
private const val KEY_ROLLBACK_OUTCOME = "control_full_rollback_outcome"
private const val KEY_ROLLBACK_REBOOT_REQUIRED = "control_full_rollback_reboot_required"
private const val KEY_ROLLBACK_DIAGNOSTIC = "control_full_rollback_diagnostic"
private const val KEY_PURGE_IN_PROGRESS = "control_module_purge_in_progress"
private const val KEY_PURGE_OUTCOME = "control_module_purge_outcome"
private const val KEY_PURGE_REBOOT_REQUIRED = "control_module_purge_reboot_required"
private const val KEY_PURGE_DIAGNOSTIC = "control_module_purge_diagnostic"
private const val KEY_LAST_RESULT = "control_last_result"
private const val MAX_ERROR_DETAIL_LENGTH = 12_000
private const val UPDATE_STATUS_REFRESH_DELAY_MS = 1_000L
private val CONTROL_ERROR_DETAIL_RESOURCES = setOf(
    R.string.control_environment_probe_failed,
    R.string.control_restart_unhealthy,
    R.string.control_runtime_rollback_failed,
    R.string.control_service_expected_state_error,
    R.string.control_update_apk_file_invalid,
    R.string.control_update_apk_installer_unavailable,
    R.string.control_update_apk_not_newer,
    R.string.control_update_apk_package_mismatch,
    R.string.control_update_apk_signer_mismatch,
    R.string.control_update_apk_signer_unavailable,
    R.string.control_update_apk_unreadable,
    R.string.control_update_apk_validation_failed,
    R.string.control_update_apk_version_code_mismatch,
    R.string.control_update_apk_version_mismatch,
    R.string.control_update_download_checksum,
    R.string.control_update_download_failed,
    R.string.control_update_download_no_internet,
    R.string.control_update_download_redirects,
    R.string.control_update_download_secure_connection_failed,
    R.string.control_update_download_security_rejected,
    R.string.control_update_download_server_rejected,
    R.string.control_update_download_storage,
    R.string.control_update_download_timeout,
    R.string.control_update_download_too_large,
    R.string.control_update_installed_signer_unavailable,
    R.string.control_update_module_empty,
    R.string.control_update_module_entry_too_large,
    R.string.control_update_module_expanded_too_large,
    R.string.control_update_module_identity_missing,
    R.string.control_update_module_install_failed,
    R.string.control_update_module_package_invalid,
    R.string.control_update_module_rejected,
    R.string.control_update_module_too_many_entries,
    R.string.control_update_module_unsafe_path,
    R.string.control_update_module_validation_failed,
    R.string.control_update_unsupported_abi,
    R.string.control_unknown_error,
)
private val CONTROL_ERROR_DETAIL_WRAPPER_FALLBACKS = emptyMap<Int, Int>()

private class EnvironmentProbeException : IllegalStateException()

private fun restoreControlErrorDetails(savedStateHandle: SavedStateHandle): UiText? {
    val hasResource = savedStateHandle.contains(KEY_ERROR_DETAIL_RESOURCE)
    val hasDynamic = savedStateHandle.contains(KEY_ERROR_DETAIL_DYNAMIC)
    if (!hasResource && !hasDynamic) return null

    val resourceId = if (hasResource) {
        savedStateHandle.restoreTypedOrRemove<Int>(KEY_ERROR_DETAIL_RESOURCE)
    } else {
        null
    }
    val safeDynamic = if (hasDynamic) {
        sanitizedBoundedUiDiagnostic(
            savedStateHandle.restoreTypedOrRemove<String>(KEY_ERROR_DETAIL_DYNAMIC).orEmpty(),
        ).takeIf(String::isNotBlank)
    } else {
        null
    }
    return when {
        resourceId != null &&
            resourceId in CONTROL_ERROR_DETAIL_WRAPPER_FALLBACKS &&
            safeDynamic != null -> UiText.resource(resourceId, safeDynamic)
        resourceId != null &&
            resourceId in CONTROL_ERROR_DETAIL_WRAPPER_FALLBACKS -> UiText.Resource(
                CONTROL_ERROR_DETAIL_WRAPPER_FALLBACKS.getValue(resourceId),
            )
        resourceId != null && resourceId in CONTROL_ERROR_DETAIL_RESOURCES ->
            UiText.Resource(resourceId)
        hasResource -> UiText.Resource(R.string.control_unknown_error)
        safeDynamic != null -> UiText.Dynamic(safeDynamic)
        else -> UiText.Resource(R.string.control_unknown_error)
    }
}

private fun UiText.toSafeControlErrorDetails(): UiText = when (this) {
    is UiText.Dynamic -> sanitizedBoundedUiDiagnostic(value)
        .takeIf(String::isNotBlank)
        ?.let(UiText::Dynamic)
        ?: UiText.Resource(R.string.control_unknown_error)
    is UiText.Resource -> when {
        id in CONTROL_ERROR_DETAIL_RESOURCES && arguments.isEmpty() -> this
        id in CONTROL_ERROR_DETAIL_WRAPPER_FALLBACKS && arguments.size == 1 -> {
            val rawDiagnostic = when (val argument = arguments.single()) {
                is String -> argument
                is UiText.Dynamic -> argument.value
                else -> ""
            }
            sanitizedBoundedUiDiagnostic(rawDiagnostic)
                .takeIf(String::isNotBlank)
                ?.let { UiText.resource(id, it) }
                ?: UiText.Resource(CONTROL_ERROR_DETAIL_WRAPPER_FALLBACKS.getValue(id))
        }
        else -> UiText.Resource(R.string.control_unknown_error)
    }
}

private fun SavedStateHandle.persistControlErrorDetails(details: UiText) {
    when (details) {
        is UiText.Dynamic -> {
            this[KEY_ERROR_DETAIL_DYNAMIC] = details.value
            remove<Int>(KEY_ERROR_DETAIL_RESOURCE)
        }
        is UiText.Resource -> {
            this[KEY_ERROR_DETAIL_RESOURCE] = details.id
            val argument = details.arguments.singleOrNull() as? String
            if (argument == null) {
                remove<String>(KEY_ERROR_DETAIL_DYNAMIC)
            } else {
                this[KEY_ERROR_DETAIL_DYNAMIC] = argument
            }
        }
    }
}

internal fun restoreControlUiState(savedStateHandle: SavedStateHandle): ControlUiState {
    val dialogKind = savedStateHandle.restoreEnumNameOrRemove<ControlDialogKind>(KEY_DIALOG_KIND)
    val errorKind = savedStateHandle.restoreEnumNameOrRemove<ControlErrorKind>(KEY_ERROR_KIND)
    val errorDetails = restoreControlErrorDetails(savedStateHandle)
    val rollbackResult = if (dialogKind == ControlDialogKind.FULL_ROLLBACK_RESULT) {
        val outcome = savedStateHandle
            .restoreEnumNameOrRemove<ServiceLifecycleController.FullRollbackOutcome>(
                KEY_ROLLBACK_OUTCOME,
            )
        outcome?.let {
            FullRollbackUiState.Result(
                outcome = it,
                rebootRequired = savedStateHandle
                    .restoreTypedOrRemove<Boolean>(KEY_ROLLBACK_REBOOT_REQUIRED) == true,
                diagnostic = sanitizedBoundedUiDiagnostic(
                    savedStateHandle
                        .restoreTypedOrRemove<String>(KEY_ROLLBACK_DIAGNOSTIC)
                        .orEmpty(),
                ),
            )
        }
    } else {
        null
    }
    val fullRollback = when {
        rollbackResult != null -> rollbackResult
        savedStateHandle.restoreTypedOrRemove<Boolean>(KEY_ROLLBACK_IN_PROGRESS) == true ->
            FullRollbackUiState.InProgress
        dialogKind == ControlDialogKind.FULL_ROLLBACK_CONFIRM -> FullRollbackUiState.Confirmation
        else -> FullRollbackUiState.Idle
    }
    val purgeResult = if (dialogKind == ControlDialogKind.MODULE_PURGE_RESULT) {
        val outcome = savedStateHandle.restoreEnumNameOrRemove<ModulePurgeController.Outcome>(
            KEY_PURGE_OUTCOME,
        )
        outcome?.let {
            ModulePurgeUiState.Result(
                outcome = it,
                rebootRequired = savedStateHandle
                    .restoreTypedOrRemove<Boolean>(KEY_PURGE_REBOOT_REQUIRED) == true,
                diagnostic = sanitizedBoundedUiDiagnostic(
                    savedStateHandle.restoreTypedOrRemove<String>(KEY_PURGE_DIAGNOSTIC).orEmpty(),
                ),
            )
        }
    } else {
        null
    }
    val restoredPurge = when {
        purgeResult != null -> purgeResult
        savedStateHandle.restoreTypedOrRemove<Boolean>(KEY_PURGE_IN_PROGRESS) == true ->
            ModulePurgeUiState.InProgress
        dialogKind == ControlDialogKind.MODULE_PURGE_CONFIRM -> ModulePurgeUiState.Confirmation
        else -> ModulePurgeUiState.Idle
    }
    // Corrupt or stale SavedState must never restore two destructive operations at once.
    val modulePurge = restoredPurge.takeIf { fullRollback is FullRollbackUiState.Idle }
        ?: ModulePurgeUiState.Idle
    val restoredDialog = when {
        fullRollback is FullRollbackUiState.InProgress -> null
        fullRollback is FullRollbackUiState.Result -> ControlDialogKind.FULL_ROLLBACK_RESULT
        fullRollback is FullRollbackUiState.Confirmation -> ControlDialogKind.FULL_ROLLBACK_CONFIRM
        modulePurge is ModulePurgeUiState.InProgress -> null
        modulePurge is ModulePurgeUiState.Result -> ControlDialogKind.MODULE_PURGE_RESULT
        modulePurge is ModulePurgeUiState.Confirmation -> ControlDialogKind.MODULE_PURGE_CONFIRM
        else -> when (dialogKind) {
            ControlDialogKind.UPDATE -> ControlDialogKind.UPDATE
            ControlDialogKind.ERROR -> ControlDialogKind.ERROR.takeIf {
                errorKind != null && errorDetails != null
            }
            ControlDialogKind.FULL_ROLLBACK_CONFIRM,
            ControlDialogKind.FULL_ROLLBACK_RESULT,
            ControlDialogKind.MODULE_PURGE_CONFIRM,
            ControlDialogKind.MODULE_PURGE_RESULT,
            null,
            -> null
        }
    }
    canonicalizeRestoredControlState(
        savedStateHandle = savedStateHandle,
        dialog = restoredDialog,
        errorKind = errorKind,
        errorDetails = errorDetails,
        fullRollback = fullRollback,
        modulePurge = modulePurge,
    )
    return ControlUiState(
        pendingDialog = restoredDialog,
        errorDialog = if (
            restoredDialog == ControlDialogKind.ERROR && errorKind != null && errorDetails != null
        ) {
            ControlErrorDialog(errorKind, errorDetails)
        } else {
            null
        },
        fullRollback = fullRollback,
        modulePurge = modulePurge,
        lastResult = restoreControlLastResult(savedStateHandle),
    )
}

private fun canonicalizeRestoredControlState(
    savedStateHandle: SavedStateHandle,
    dialog: ControlDialogKind?,
    errorKind: ControlErrorKind?,
    errorDetails: UiText?,
    fullRollback: FullRollbackUiState,
    modulePurge: ModulePurgeUiState,
) {
    if (dialog == null) savedStateHandle.remove<String>(KEY_DIALOG_KIND)
    else savedStateHandle[KEY_DIALOG_KIND] = dialog.name

    if (dialog != ControlDialogKind.ERROR) {
        savedStateHandle.remove<String>(KEY_ERROR_KIND)
        savedStateHandle.remove<Int>(KEY_ERROR_DETAIL_RESOURCE)
        savedStateHandle.remove<String>(KEY_ERROR_DETAIL_DYNAMIC)
    } else {
        savedStateHandle[KEY_ERROR_KIND] = checkNotNull(errorKind).name
        savedStateHandle.persistControlErrorDetails(checkNotNull(errorDetails))
    }
    if (fullRollback !is FullRollbackUiState.Result) {
        savedStateHandle.remove<String>(KEY_ROLLBACK_OUTCOME)
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_REBOOT_REQUIRED)
        savedStateHandle.remove<String>(KEY_ROLLBACK_DIAGNOSTIC)
    } else {
        savedStateHandle[KEY_ROLLBACK_OUTCOME] = fullRollback.outcome.name
        savedStateHandle[KEY_ROLLBACK_REBOOT_REQUIRED] = fullRollback.rebootRequired
        savedStateHandle[KEY_ROLLBACK_DIAGNOSTIC] = fullRollback.diagnostic
    }
    if (fullRollback !is FullRollbackUiState.InProgress) {
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_IN_PROGRESS)
    }
    if (modulePurge !is ModulePurgeUiState.Result) {
        savedStateHandle.remove<String>(KEY_PURGE_OUTCOME)
        savedStateHandle.remove<Boolean>(KEY_PURGE_REBOOT_REQUIRED)
        savedStateHandle.remove<String>(KEY_PURGE_DIAGNOSTIC)
    } else {
        savedStateHandle[KEY_PURGE_OUTCOME] = modulePurge.outcome.name
        savedStateHandle[KEY_PURGE_REBOOT_REQUIRED] = modulePurge.rebootRequired
        savedStateHandle[KEY_PURGE_DIAGNOSTIC] = modulePurge.diagnostic
    }
    if (modulePurge !is ModulePurgeUiState.InProgress) {
        savedStateHandle.remove<Boolean>(KEY_PURGE_IN_PROGRESS)
    }
}

internal enum class FullRollbackLaunchReason { CONFIRMED, RESTORED }

internal class FullRollbackOperationCoordinator(
    private val savedStateHandle: SavedStateHandle,
    private val operationInProgress: AtomicBoolean = AtomicBoolean(false),
    private val onTerminalPersisted: () -> Unit = {},
) {
    fun tryBegin(
        reason: FullRollbackLaunchReason,
        state: ControlUiState,
        launch: () -> Unit,
    ): Boolean {
        val eligible = when (reason) {
            FullRollbackLaunchReason.CONFIRMED ->
                state.fullRollback is FullRollbackUiState.Confirmation && state.canFullRollback
            FullRollbackLaunchReason.RESTORED ->
                state.fullRollback is FullRollbackUiState.InProgress &&
                    savedStateHandle.restoreTypedOrRemove<Boolean>(KEY_ROLLBACK_IN_PROGRESS) == true
        }
        if (!eligible || !operationInProgress.compareAndSet(false, true)) return false

        savedStateHandle[KEY_ROLLBACK_IN_PROGRESS] = true
        savedStateHandle.remove<String>(KEY_DIALOG_KIND)
        savedStateHandle.remove<String>(KEY_ERROR_KIND)
        savedStateHandle.remove<Int>(KEY_ERROR_DETAIL_RESOURCE)
        savedStateHandle.remove<String>(KEY_ERROR_DETAIL_DYNAMIC)
        clearPersistedResult()

        return try {
            launch()
            true
        } catch (error: Throwable) {
            operationInProgress.set(false)
            throw error
        }
    }

    fun persistTerminal(
        result: ServiceLifecycleController.FullRollbackResult,
        diagnostic: String,
        lastResult: ControlLastResult,
    ) {
        savedStateHandle[KEY_ROLLBACK_OUTCOME] = result.outcome.name
        savedStateHandle[KEY_ROLLBACK_REBOOT_REQUIRED] = result.rebootRequired
        savedStateHandle[KEY_ROLLBACK_DIAGNOSTIC] = diagnostic
        savedStateHandle[KEY_LAST_RESULT] = lastResult.name
        savedStateHandle.remove<String>(KEY_ERROR_KIND)
        savedStateHandle.remove<Int>(KEY_ERROR_DETAIL_RESOURCE)
        savedStateHandle.remove<String>(KEY_ERROR_DETAIL_DYNAMIC)
        // The discriminator is written after the complete terminal payload so restoration
        // cannot mistake a partially persisted terminal transition for a completed result.
        savedStateHandle[KEY_DIALOG_KIND] = ControlDialogKind.FULL_ROLLBACK_RESULT.name
        onTerminalPersisted()
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_IN_PROGRESS)
    }

    fun finishAttempt() {
        operationInProgress.set(false)
    }

    private fun clearPersistedResult() {
        savedStateHandle.remove<String>(KEY_ROLLBACK_OUTCOME)
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_REBOOT_REQUIRED)
        savedStateHandle.remove<String>(KEY_ROLLBACK_DIAGNOSTIC)
    }
}

internal enum class ModulePurgeLaunchReason { CONFIRMED, RESTORED }

internal class ModulePurgeOperationCoordinator(
    private val savedStateHandle: SavedStateHandle,
    private val operationInProgress: AtomicBoolean = AtomicBoolean(false),
) {
    fun tryBegin(
        reason: ModulePurgeLaunchReason,
        state: ControlUiState,
        launch: () -> Unit,
    ): Boolean {
        val eligible = when (reason) {
            ModulePurgeLaunchReason.CONFIRMED ->
                state.modulePurge is ModulePurgeUiState.Confirmation && state.canPurgeModule
            ModulePurgeLaunchReason.RESTORED ->
                state.modulePurge is ModulePurgeUiState.InProgress &&
                    savedStateHandle.restoreTypedOrRemove<Boolean>(KEY_PURGE_IN_PROGRESS) == true
        }
        if (!eligible || !operationInProgress.compareAndSet(false, true)) return false

        savedStateHandle[KEY_PURGE_IN_PROGRESS] = true
        savedStateHandle.remove<String>(KEY_DIALOG_KIND)
        savedStateHandle.remove<String>(KEY_ERROR_KIND)
        savedStateHandle.remove<Int>(KEY_ERROR_DETAIL_RESOURCE)
        savedStateHandle.remove<String>(KEY_ERROR_DETAIL_DYNAMIC)
        clearPersistedResult()

        return try {
            launch()
            true
        } catch (error: Throwable) {
            operationInProgress.set(false)
            throw error
        }
    }

    fun persistTerminal(
        result: ModulePurgeController.Result,
        diagnostic: String,
        lastResult: ControlLastResult,
    ) {
        savedStateHandle[KEY_PURGE_OUTCOME] = result.outcome.name
        savedStateHandle[KEY_PURGE_REBOOT_REQUIRED] = result.rebootRequired
        savedStateHandle[KEY_PURGE_DIAGNOSTIC] = diagnostic
        savedStateHandle[KEY_LAST_RESULT] = lastResult.name
        savedStateHandle.remove<String>(KEY_ERROR_KIND)
        savedStateHandle.remove<Int>(KEY_ERROR_DETAIL_RESOURCE)
        savedStateHandle.remove<String>(KEY_ERROR_DETAIL_DYNAMIC)
        savedStateHandle[KEY_DIALOG_KIND] = ControlDialogKind.MODULE_PURGE_RESULT.name
        savedStateHandle.remove<Boolean>(KEY_PURGE_IN_PROGRESS)
    }

    fun finishAttempt() {
        operationInProgress.set(false)
    }

    fun retireSuccessfulTerminalState() {
        savedStateHandle.remove<String>(KEY_DIALOG_KIND)
        savedStateHandle.remove<Boolean>(KEY_PURGE_IN_PROGRESS)
        savedStateHandle.remove<String>(KEY_PURGE_OUTCOME)
        savedStateHandle.remove<Boolean>(KEY_PURGE_REBOOT_REQUIRED)
        savedStateHandle.remove<String>(KEY_PURGE_DIAGNOSTIC)
        savedStateHandle.remove<String>(KEY_LAST_RESULT)
    }

    private fun clearPersistedResult() {
        savedStateHandle.remove<String>(KEY_PURGE_OUTCOME)
        savedStateHandle.remove<Boolean>(KEY_PURGE_REBOOT_REQUIRED)
        savedStateHandle.remove<String>(KEY_PURGE_DIAGNOSTIC)
    }
}

@HiltViewModel
class ControlViewModel @Inject constructor(
    private val networkStatsManager: NetworkStatsManager,
    private val updateManager: UpdateManager,
    private val prefs: SharedPreferences,
    private val serviceEventBus: ServiceEventBus,
    private val savedStateHandle: SavedStateHandle,
    private val moduleRepository: Zapret2ModuleRepository,
    private val logRepository: RuntimeLogRepository,
    private val modulePurgeAppDataCleaner: ModulePurgeAppDataCleaner,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoreControlUiState(savedStateHandle))
    val uiState: StateFlow<ControlUiState> = _uiState.asStateFlow()

    private var screenStarted = false
    private var statusInvalidated = false
    private val initializationRequested = AtomicBoolean(false)
    private val initializationFinished = AtomicBoolean(false)
    private val toggleInProgress = AtomicBoolean(false)
    private val settingMutationInProgress = AtomicBoolean(false)
    private val updateCheckInProgress = AtomicBoolean(false)
    private val exclusiveActionInProgress = AtomicBoolean(false)
    private val rollbackOperation = FullRollbackOperationCoordinator(
        savedStateHandle = savedStateHandle,
        operationInProgress = exclusiveActionInProgress,
    )
    private val purgeOperation = ModulePurgeOperationCoordinator(
        savedStateHandle = savedStateHandle,
        operationInProgress = exclusiveActionInProgress,
    )
    private val statusRefreshSequence = AtomicLong(0)
    private val lifecycleSettlementObserver = LifecycleSettlementObserver(
        scope = viewModelScope,
        observe = { refreshServiceStatusOnce() },
    )

    init {
        viewModelScope.launch {
            serviceEventBus.serviceRestarted.collect { source ->
                if (source != ServiceEventSource.CONTROL) statusInvalidated = true
            }
        }
        if (_uiState.value.fullRollback is FullRollbackUiState.InProgress) {
            startFullRollback(FullRollbackLaunchReason.RESTORED)
        } else if (_uiState.value.modulePurge is ModulePurgeUiState.InProgress) {
            startModulePurge(ModulePurgeLaunchReason.RESTORED)
        }
    }

    fun ensureInitialized() {
        if (initializationRequested.compareAndSet(false, true)) loadInitialState()
    }

    fun onScreenStarted() {
        if (screenStarted) return
        screenStarted = true
        if (_uiState.value.moduleMutationState == ModuleMutationState.IN_PROGRESS) {
            lifecycleSettlementObserver.ensureObserving()
        }
        if (statusInvalidated) {
            statusInvalidated = false
            viewModelScope.launch { refreshServiceStatusOnce() }
        }
    }

    fun onScreenStopped() {
        screenStarted = false
        lifecycleSettlementObserver.stop()
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private fun publishMessage(message: UiText) {
        _uiState.update { it.copy(message = message) }
    }

    fun dismissDialog() {
        val state = _uiState.value
        if (state.isFullRollbackInProgress || state.isModulePurgeInProgress ||
            state.isUpdating ||
            ServiceLifecycleController.isAppUpdateInProgress()
        ) return
        clearPersistedDialog()
        _uiState.update {
            it.copy(
                pendingDialog = null,
                updateRelease = null,
                errorDialog = null,
                fullRollback = FullRollbackUiState.Idle,
                modulePurge = ModulePurgeUiState.Idle,
            )
        }
    }

    fun showFullRollbackConfirmation() {
        if (rejectConflictingOperation()) return
        val state = _uiState.value
        if (!state.canFullRollback || state.pendingDialog != null) return
        savedStateHandle[KEY_DIALOG_KIND] = ControlDialogKind.FULL_ROLLBACK_CONFIRM.name
        clearPersistedError()
        clearPersistedRollback()
        clearPersistedPurge()
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_IN_PROGRESS)
        savedStateHandle.remove<Boolean>(KEY_PURGE_IN_PROGRESS)
        _uiState.update {
            it.copy(
                pendingDialog = ControlDialogKind.FULL_ROLLBACK_CONFIRM,
                updateRelease = null,
                errorDialog = null,
                fullRollback = FullRollbackUiState.Confirmation,
                modulePurge = ModulePurgeUiState.Idle,
            )
        }
    }

    fun confirmFullRollback() {
        startFullRollback(FullRollbackLaunchReason.CONFIRMED)
    }

    private fun startFullRollback(reason: FullRollbackLaunchReason) {
        rollbackOperation.tryBegin(reason, _uiState.value) {
            _uiState.update {
                it.copy(
                    pendingDialog = null,
                    updateRelease = null,
                    errorDialog = null,
                    fullRollback = FullRollbackUiState.InProgress,
                    modulePurge = ModulePurgeUiState.Idle,
                )
            }

            viewModelScope.launch {
                try {
                    val result = ServiceLifecycleController.fullRollback()
                    try {
                        refreshStatus()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The controller already performed the authoritative post-command check.
                    }
                    if (result.success) {
                        _uiState.update { it.copy(autostart = false) }
                    }
                    showFullRollbackResult(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showFullRollbackResult(
                        ServiceLifecycleController.FullRollbackResult(
                            outcome = ServiceLifecycleController.FullRollbackOutcome.ERROR,
                        ),
                    )
                } finally {
                    // Cancellation intentionally keeps the durable marker so a recreated
                    // ViewModel can reconcile the idempotent rollback operation.
                    rollbackOperation.finishAttempt()
                }
            }
        }
    }

    private fun showFullRollbackResult(result: ServiceLifecycleController.FullRollbackResult) {
        val diagnostic = sanitizedBoundedUiDiagnostic(result.diagnosticText())
        val lastResult = if (result.success) {
            ControlLastResult.ROLLBACK_COMPLETED
        } else {
            ControlLastResult.ROLLBACK_FAILED
        }
        rollbackOperation.persistTerminal(
            result = result,
            diagnostic = diagnostic,
            lastResult = lastResult,
        )
        _uiState.update {
            it.copy(
                pendingDialog = ControlDialogKind.FULL_ROLLBACK_RESULT,
                updateRelease = null,
                errorDialog = null,
                fullRollback = FullRollbackUiState.Result(
                    outcome = result.outcome,
                    rebootRequired = result.rebootRequired,
                    diagnostic = diagnostic,
                ),
                modulePurge = ModulePurgeUiState.Idle,
                lastResult = lastResult,
            )
        }
    }

    fun showModulePurgeConfirmation() {
        if (rejectConflictingOperation()) return
        val state = _uiState.value
        if (!state.canPurgeModule || state.pendingDialog != null) return
        savedStateHandle[KEY_DIALOG_KIND] = ControlDialogKind.MODULE_PURGE_CONFIRM.name
        clearPersistedError()
        clearPersistedRollback()
        clearPersistedPurge()
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_IN_PROGRESS)
        savedStateHandle.remove<Boolean>(KEY_PURGE_IN_PROGRESS)
        _uiState.update {
            it.copy(
                pendingDialog = ControlDialogKind.MODULE_PURGE_CONFIRM,
                updateRelease = null,
                errorDialog = null,
                fullRollback = FullRollbackUiState.Idle,
                modulePurge = ModulePurgeUiState.Confirmation,
            )
        }
    }

    fun confirmModulePurge() {
        startModulePurge(ModulePurgeLaunchReason.CONFIRMED)
    }

    private fun startModulePurge(reason: ModulePurgeLaunchReason) {
        purgeOperation.tryBegin(reason, _uiState.value) {
            _uiState.update {
                it.copy(
                    pendingDialog = null,
                    updateRelease = null,
                    errorDialog = null,
                    fullRollback = FullRollbackUiState.Idle,
                    modulePurge = ModulePurgeUiState.InProgress,
                )
            }

            viewModelScope.launch {
                try {
                    val result = ModulePurgeController.purge(modulePurgeAppDataCleaner)
                    if (result.report?.satisfiesCompleteContract == true) {
                        _uiState.update {
                            it.copy(
                                autostart = false,
                                isRunning = false,
                                canStopService = false,
                                status = ControlStatus.STOPPED,
                                moduleInstallState = ModuleInstallState.MISSING,
                                pendingModuleState = PendingModuleState.NONE,
                                moduleMutationState = ModuleMutationState.IDLE,
                                moduleVersion = "",
                                hasAuthoritativeRuntimeSettings = false,
                                iptablesActive = false,
                                nfqueueRulesCount = 0,
                            )
                        }
                    }
                    showModulePurgeResult(result)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    showModulePurgeResult(ModulePurgeController.Result(ModulePurgeController.Outcome.ERROR))
                } finally {
                    purgeOperation.finishAttempt()
                }
            }
        }
    }

    private fun showModulePurgeResult(result: ModulePurgeController.Result) {
        val diagnostic = sanitizedBoundedUiDiagnostic(result.diagnosticText())
        val lastResult = if (result.success) {
            ControlLastResult.PURGE_COMPLETED
        } else {
            ControlLastResult.PURGE_FAILED
        }
        if (result.success) {
            // Keep the success dialog only in memory; a clean reset must not recreate
            // its own persisted app state after the app-owned storage was cleared.
            purgeOperation.retireSuccessfulTerminalState()
        } else {
            purgeOperation.persistTerminal(result, diagnostic, lastResult)
        }
        _uiState.update {
            it.copy(
                pendingDialog = ControlDialogKind.MODULE_PURGE_RESULT,
                updateRelease = null,
                errorDialog = null,
                fullRollback = FullRollbackUiState.Idle,
                modulePurge = ModulePurgeUiState.Result(
                    outcome = result.outcome,
                    rebootRequired = result.rebootRequired,
                    diagnostic = diagnostic,
                ),
                lastResult = lastResult,
            )
        }
    }

    private fun showUpdateDialog(release: UpdateManager.Release) {
        savedStateHandle[KEY_DIALOG_KIND] = ControlDialogKind.UPDATE.name
        clearPersistedError()
        clearPersistedRollback()
        clearPersistedPurge()
        _uiState.update {
            it.copy(
                pendingDialog = ControlDialogKind.UPDATE,
                updateRelease = release,
                errorDialog = null,
                fullRollback = FullRollbackUiState.Idle,
                modulePurge = ModulePurgeUiState.Idle,
            )
        }
    }

    private fun showErrorDialog(kind: ControlErrorKind, details: UiText) {
        if (_uiState.value.isFullRollbackInProgress || _uiState.value.isModulePurgeInProgress) return
        val safeDetails = details.toSafeControlErrorDetails()
        when (kind) {
            ControlErrorKind.UPDATE -> recordLastResult(ControlLastResult.UPDATE_FAILED)
            ControlErrorKind.START_SERVICE,
            ControlErrorKind.STOP_SERVICE,
            ControlErrorKind.SERVICE_OPERATION,
            ControlErrorKind.RESTART_SERVICE,
            -> recordLastResult(ControlLastResult.SERVICE_FAILED)
            ControlErrorKind.INITIALIZATION -> Unit
        }
        savedStateHandle[KEY_DIALOG_KIND] = ControlDialogKind.ERROR.name
        savedStateHandle[KEY_ERROR_KIND] = kind.name
        clearPersistedRollback()
        clearPersistedPurge()
        savedStateHandle.persistControlErrorDetails(safeDetails)
        _uiState.update {
            it.copy(
                pendingDialog = ControlDialogKind.ERROR,
                updateRelease = null,
                errorDialog = ControlErrorDialog(kind, safeDetails),
                fullRollback = FullRollbackUiState.Idle,
                modulePurge = ModulePurgeUiState.Idle,
            )
        }
    }

    private fun clearPersistedDialog() {
        savedStateHandle.remove<String>(KEY_DIALOG_KIND)
        clearPersistedError()
        clearPersistedRollback()
        clearPersistedPurge()
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_IN_PROGRESS)
        savedStateHandle.remove<Boolean>(KEY_PURGE_IN_PROGRESS)
    }

    private fun clearPersistedError() {
        savedStateHandle.remove<String>(KEY_ERROR_KIND)
        savedStateHandle.remove<Int>(KEY_ERROR_DETAIL_RESOURCE)
        savedStateHandle.remove<String>(KEY_ERROR_DETAIL_DYNAMIC)
    }

    private fun clearPersistedRollback() {
        savedStateHandle.remove<String>(KEY_ROLLBACK_OUTCOME)
        savedStateHandle.remove<Boolean>(KEY_ROLLBACK_REBOOT_REQUIRED)
        savedStateHandle.remove<String>(KEY_ROLLBACK_DIAGNOSTIC)
    }

    private fun clearPersistedPurge() {
        savedStateHandle.remove<String>(KEY_PURGE_OUTCOME)
        savedStateHandle.remove<Boolean>(KEY_PURGE_REBOOT_REQUIRED)
        savedStateHandle.remove<String>(KEY_PURGE_DIAGNOSTIC)
    }

    private fun recordLastResult(result: ControlLastResult) {
        savedStateHandle[KEY_LAST_RESULT] = result.name
        _uiState.update { it.copy(lastResult = result) }
    }

    private fun revalidateRestoredUpdateDialog() {
        launchUpdateCheck(
            showUpToDateMessage = false,
            expectedDialog = ControlDialogKind.UPDATE,
        )
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            var detectedRootState: ServiceLifecycleController.RootAccessState? = null
            try {
                val rootAccess = ServiceLifecycleController.checkRootAccess()
                detectedRootState = rootAccess.state
                val environment = if (rootAccess.granted) {
                    moduleRepository.reconcileEnvironment()
                        ?: throw EnvironmentProbeException()
                } else {
                    null
                }
                val stableModuleConfig =
                    environment?.activeState in setOf(
                            ModuleInstallState.READY,
                            ModuleInstallState.DISABLED,
                        )
                val runtimeConfig = if (stableModuleConfig) {
                    RuntimeConfigStore.readCore()
                } else {
                    null
                }
                if (runtimeConfig != null && runtimeConfig !is RuntimeConfigReadResult.Valid) {
                    _uiState.update {
                        it.copy(moduleDiagnostic = runtimeConfig.diagnosticText())
                    }
                    throw EnvironmentProbeException()
                }
                val coreValues = runtimeConfig?.values.orEmpty()
                if (stableModuleConfig && coreValues.isEmpty()) throw EnvironmentProbeException()
                val unsupportedWifiOnlyWasEnabled = coreValues["wifi_only"] == "1"
                var runtimeMutationDiagnostic: String? = null
                val wifiOnlyNormalized = if (!unsupportedWifiOnlyWasEnabled) {
                    true
                } else {
                    try {
                        ModuleMutationCoordinator.withNonCancellableMutation {
                            RuntimeConfigStore.upsertCoreValue("wifi_only", "0").let { result ->
                                runtimeMutationDiagnostic = result.diagnosticTextOrNull()
                                result.isSuccess
                            }
                        }
                    } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                        false
                    }
                }
                val showQuicBanner = withContext(Dispatchers.IO) {
                    !prefs.getBoolean("quic_banner_dismissed", false)
                }

                _uiState.update { state ->
                    state.copy(
                        hasRootAccess = rootAccess.granted,
                        rootAccessState = rootAccess.state,
                        moduleInstallState = environment?.activeState ?: ModuleInstallState.UNKNOWN,
                        pendingModuleState = environment?.pendingState ?: PendingModuleState.NONE,
                        moduleMutationState = ModuleMutationState.IDLE,
                        nfqueueSupported = environment?.nfqueueSupported == true,
                        moduleVersion = environment?.displayedVersion.orEmpty(),
                        autostart = coreValues["autostart"] != "0",
                        hasAuthoritativeRuntimeSettings = stableModuleConfig,
                        moduleDiagnostic = runtimeMutationDiagnostic,
                        showQuicBanner = showQuicBanner,
                    )
                }

                if (!wifiOnlyNormalized) {
                    publishMessage(UiText.Resource(R.string.control_wifi_only_disable_failed))
                }

                if (rootAccess.granted) checkStatus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val rootState = detectedRootState
                    ?: ServiceLifecycleController.RootAccessState.SHELL_FAILURE
                _uiState.update {
                    it.copy(
                        hasRootAccess = rootState == ServiceLifecycleController.RootAccessState.GRANTED,
                        rootAccessState = rootState,
                        hasAuthoritativeRuntimeSettings = false,
                        status = if (rootState == ServiceLifecycleController.RootAccessState.GRANTED) {
                            ControlStatus.UNAVAILABLE
                        } else {
                            rootState.toControlStatus()
                        },
                    )
                }
                showErrorDialog(
                    kind = ControlErrorKind.INITIALIZATION,
                    details = if (error is EnvironmentProbeException) {
                        _uiState.value.moduleDiagnostic
                            ?.let { UiText.Dynamic(it) }
                            ?: UiText.Resource(R.string.control_environment_probe_failed)
                    } else UiText.Resource(R.string.control_unknown_error),
                )
            } finally {
                if (_uiState.value.pendingDialog == ControlDialogKind.UPDATE) {
                    revalidateRestoredUpdateDialog()
                }
                initializationFinished.set(true)
            }
        }
    }

    private suspend fun refreshServiceStatusOnce(): ModuleMutationState? {
        if (exclusiveActionInProgress.get()) return null
        return try {
            checkStatus().moduleMutationState
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _uiState.update {
                it.copy(
                    isRunning = false,
                    status = ControlStatus.UNAVAILABLE,
                    iptablesActive = false,
                )
            }
            null
        }
    }

    private data class ServiceSnapshot(
        val isRunning: Boolean,
        val canStopService: Boolean,
        val moduleMutationState: ModuleMutationState,
    )

    private suspend fun checkStatus(): ServiceSnapshot {
        return refreshStatus()
    }

    /**
     * Status recovery acquires ModuleMutationCoordinator before the lifecycle controller. Never
     * wrap this method in a view-model mutex: settings saves intentionally keep the module
     * coordinator while restarting, and a reverse wait here would deadlock observation against saves.
     * Installation metadata is retained from the initialization/publication boundary; an ordinary
     * status read consumes only the module's typed lifecycle snapshot.
     */
    private suspend fun refreshStatus(): ServiceSnapshot {
        val refreshId = statusRefreshSequence.incrementAndGet()
        val cachedEnvironment = _uiState.value
        val environment = ModuleEnvironmentSnapshot(
            activeState = cachedEnvironment.moduleInstallState,
            pendingState = cachedEnvironment.pendingModuleState,
            nfqueueSupported = cachedEnvironment.nfqueueSupported,
            activeVersion = cachedEnvironment.moduleVersion,
        )

        val statusWithoutQuery = environment.serviceAccess.statusWithoutQuery()
        if (statusWithoutQuery != null) {
            if (refreshId == statusRefreshSequence.get()) {
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        canStopService = false,
                        status = statusWithoutQuery,
                        networkType = UiText.Resource(networkStatsManager.getNetworkType().labelRes),
                        uptime = "",
                        iptablesActive = false,
                        nfqueueRulesCount = 0,
                        iptablesDetail = NetworkStatsManager.IptablesDetail(),
                        processStats = ProcessStats(),
                        hasRootAccess = cachedEnvironment.hasRootAccess,
                        rootAccessState = cachedEnvironment.rootAccessState,
                        moduleInstallState = environment.activeState,
                        pendingModuleState = environment.pendingState,
                        moduleMutationState = ModuleMutationState.IDLE,
                        moduleVersion = environment.displayedVersion,
                        nfqueueSupported = environment.nfqueueSupported,
                        hasAuthoritativeRuntimeSettings = false,
                    )
                }
            }
            return ServiceSnapshot(
                isRunning = false,
                canStopService = false,
                moduleMutationState = ModuleMutationState.IDLE,
            )
        }

        val serviceStatus = ServiceLifecycleController.getStatus()
        val lifecycleMutationState = serviceStatus.lifecycleState.toModuleMutationState()
        if (lifecycleMutationState != ModuleMutationState.IDLE) {
            val current = _uiState.value
            val publishResult = refreshId == statusRefreshSequence.get()
            val currentNetworkType = UiText.Resource(
                networkStatsManager.getNetworkType().labelRes,
            )
            if (publishResult) {
                _uiState.update {
                    it.copy(
                        status = projectedControlStatus(
                            serviceStatus = serviceStatus,
                            canStopService = current.canStopService,
                        ),
                        hasRootAccess = serviceStatus.rootGranted,
                        rootAccessState = serviceStatus.rootAccessState,
                        moduleInstallState = environment.activeState,
                        pendingModuleState = environment.pendingState,
                        moduleMutationState = lifecycleMutationState,
                        moduleVersion = environment.displayedVersion,
                        nfqueueSupported = environment.nfqueueSupported,
                        hasAuthoritativeRuntimeSettings = false,
                        moduleDiagnostic = projectedLifecycleDiagnostic(serviceStatus),
                        networkType = currentNetworkType,
                    )
                }
            }
            if (
                publishResult &&
                screenStarted &&
                lifecycleMutationState == ModuleMutationState.IN_PROGRESS
            ) {
                lifecycleSettlementObserver.ensureObserving()
            }
            return ServiceSnapshot(
                isRunning = current.isRunning,
                canStopService = current.canStopService,
                moduleMutationState = lifecycleMutationState,
            )
        }
        val netStats = networkStatsManager.getNetworkStats(serviceStatus)
        val detail = netStats.iptablesDetail
        val isRunning = confirmedRunning(serviceStatus)
        val effectiveRulesCount = serviceStatus.nfqueueRulesCount
        val canStopService = serviceStatus.hasOwnedState
        val processPid = serviceStatus.pid.takeIf {
            serviceStatus.processRunning && serviceStatus.pidVerified && it.matches(Regex("[1-9][0-9]*"))
        }.orEmpty()

        val processStats = if (serviceStatus.processRunning && processPid.isNotEmpty()) {
            moduleRepository.readProcessMetrics(processPid).let { metrics ->
                ProcessStats(
                    pid = processPid,
                    memory = metrics.memoryKb.takeIf(String::isNotBlank)?.let { "$it KB" }.orEmpty(),
                    threads = metrics.threads,
                    uptime = metrics.uptime,
                )
            }
        } else ProcessStats()

        val status = projectedControlStatus(
            serviceStatus = serviceStatus,
            canStopService = canStopService,
        )
        if (refreshId == statusRefreshSequence.get()) {
            _uiState.update { current ->
                current.copy(
                    isRunning = isRunning,
                    canStopService = canStopService,
                    status = status,
                    uptime = processStats.uptime,
                    networkType = UiText.Resource(netStats.networkType.labelRes),
                    iptablesActive = serviceStatus.iptablesActive,
                    nfqueueRulesCount = effectiveRulesCount,
                    iptablesDetail = detail,
                    processStats = processStats,
                    hasRootAccess = serviceStatus.rootGranted,
                    rootAccessState = serviceStatus.rootAccessState,
                    moduleInstallState = environment.activeState,
                    pendingModuleState = environment.pendingState,
                    moduleMutationState = lifecycleMutationState,
                    moduleVersion = environment.displayedVersion,
                    nfqueueSupported = environment.nfqueueSupported,
                    hasAuthoritativeRuntimeSettings = current.hasAuthoritativeRuntimeSettings &&
                        environment.activeState == ModuleInstallState.READY &&
                        lifecycleMutationState == ModuleMutationState.IDLE,
                    moduleDiagnostic = projectedLifecycleDiagnostic(serviceStatus)
                        ?: current.moduleDiagnostic.takeUnless {
                            serviceStatus.metadataComplete &&
                                current.hasAuthoritativeRuntimeSettings
                        },
                )
            }
        }

        return ServiceSnapshot(
            isRunning = isRunning,
            canStopService = canStopService,
            moduleMutationState = lifecycleMutationState,
        )
    }

    fun refreshStatusManually() {
        if (!screenStarted) return
        val state = _uiState.value
        if (state.moduleInstallState == ModuleInstallState.UNKNOWN || !state.hasRootAccess) {
            if (!initializationFinished.get()) return
            initializationRequested.set(false)
            initializationFinished.set(false)
            ensureInitialized()
            return
        }
        viewModelScope.launch {
            refreshServiceStatusOnce()
        }
    }

    fun toggleService() {
        if (!exclusiveActionInProgress.compareAndSet(false, true)) return
        toggleInProgress.set(true)
        if (_uiState.value.pendingDialog != null) {
            toggleInProgress.set(false)
            exclusiveActionInProgress.set(false)
            return
        }
        if (
            _uiState.value.isUpdating ||
            _uiState.value.isCheckingForUpdates ||
            _uiState.value.isSavingSettings || settingMutationInProgress.get() ||
            _uiState.value.isFullRollbackInProgress ||
            _uiState.value.isModulePurgeInProgress ||
            ServiceLifecycleController.isAppUpdateInProgress() ||
            ServiceLifecycleController.isFullRollbackInProgress() ||
            ModulePurgeController.isInProgress()
        ) {
            toggleInProgress.set(false)
            exclusiveActionInProgress.set(false)
            publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
            return
        }
        _uiState.update { it.copy(isToggling = true) }

        viewModelScope.launch {
            try {
                val currentStatus = refreshStatus()
                val shouldStop = currentStatus.canStopService
                if (!shouldStop && rejectUnavailableModuleOperation()) return@launch
                val lifecycleResult = if (shouldStop) {
                    ServiceLifecycleController.stop()
                } else {
                    ServiceLifecycleController.start()
                }
                val verifiedState = refreshStatus()

                val verified = if (shouldStop) !verifiedState.canStopService else verifiedState.isRunning
                if (lifecycleResult.success && verified) {
                    recordLastResult(
                        if (shouldStop) {
                            ControlLastResult.SERVICE_STOPPED
                        } else {
                            ControlLastResult.SERVICE_STARTED
                        },
                    )
                    publishMessage(
                        UiText.Resource(
                            if (shouldStop) {
                                R.string.control_service_stopped_result
                            } else {
                                R.string.control_service_started
                            },
                        ),
                    )
                    if (!shouldStop) {
                        serviceEventBus.notifyServiceRestarted(ServiceEventSource.CONTROL)
                    }
                } else {
                    val diagnostic = lifecycleResult.diagnosticText()
                        .ifBlank { readServiceFailureLogs() }
                    val details = if (diagnostic.isBlank()) {
                        UiText.Resource(R.string.control_service_expected_state_error)
                    } else {
                        UiText.Dynamic(diagnostic)
                    }
                    showErrorDialog(
                        kind = if (shouldStop) {
                            ControlErrorKind.STOP_SERVICE
                        } else {
                            ControlErrorKind.START_SERVICE
                        },
                        details = details,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showErrorDialog(
                    kind = ControlErrorKind.SERVICE_OPERATION,
                    details = UiText.Resource(R.string.control_service_expected_state_error),
                )
            } finally {
                _uiState.update { it.copy(isToggling = false) }
                toggleInProgress.set(false)
                exclusiveActionInProgress.set(false)
            }
        }
    }

    private suspend fun readServiceFailureLogs(): String = withContext(Dispatchers.IO) {
        when (val result = logRepository.readFailureTail()) {
            is ProtectedTextRead.Content -> result.value.takeLast(MAX_ERROR_DETAIL_LENGTH)
            ProtectedTextRead.Absent,
            ProtectedTextRead.Failed,
            -> ""
        }
    }

    fun setAutostart(enabled: Boolean) {
        if (rejectConflictingOperation()) return
        if (rejectUnavailableSettingMutation()) return
        if (_uiState.value.autostart == enabled) return
        launchSettingMutation(R.string.control_autostart_save_failed) {
            handleRuntimeMutation(
                RuntimeConfigStore.upsertCoreValue(
                    "autostart",
                    if (enabled) "1" else "0",
                ),
            ).also { success ->
                if (success) _uiState.update { it.copy(autostart = enabled) }
            }
        }
    }

    private fun handleRuntimeMutation(result: RuntimeConfigMutationResult): Boolean {
        result.diagnosticTextOrNull()?.let { diagnostic ->
            _uiState.update { it.copy(moduleDiagnostic = diagnostic) }
        }
        return result.isSuccess
    }

    private fun launchSettingMutation(
        @StringRes failureMessage: Int,
        block: suspend () -> Boolean,
    ) {
        if (!exclusiveActionInProgress.compareAndSet(false, true)) {
            publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
            return
        }
        settingMutationInProgress.set(true)
        _uiState.update { it.copy(isSavingSettings = true) }
        viewModelScope.launch {
            try {
                val succeeded = ModuleMutationCoordinator.withNonCancellableMutation(block)
                if (!succeeded) {
                    publishMessage(UiText.Resource(failureMessage))
                }
            } catch (_: ModuleMutationCoordinator.MutationBlockedException) {
                publishMessage(UiText.Resource(R.string.control_module_update_in_progress))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                publishMessage(UiText.Resource(failureMessage))
            } finally {
                settingMutationInProgress.set(false)
                _uiState.update { it.copy(isSavingSettings = false) }
                exclusiveActionInProgress.set(false)
            }
        }
    }

    fun dismissQuicBanner() {
        prefs.edit { putBoolean("quic_banner_dismissed", true) }
        _uiState.update { it.copy(showQuicBanner = false) }
    }

    fun checkForUpdates() {
        if (_uiState.value.pendingDialog != null) return
        if (
            _uiState.value.status in setOf(
                ControlStatus.CHECKING,
                ControlStatus.LIFECYCLE_BUSY,
            ) ||
            _uiState.value.isToggling ||
            _uiState.value.isUpdating ||
            _uiState.value.isSavingSettings || settingMutationInProgress.get() ||
            _uiState.value.isFullRollbackInProgress ||
            _uiState.value.isModulePurgeInProgress ||
            ServiceLifecycleController.isAppUpdateInProgress() ||
            ServiceLifecycleController.isFullRollbackInProgress() ||
            ModulePurgeController.isInProgress()
        ) {
            publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
            return
        }
        launchUpdateCheck(showUpToDateMessage = true, expectedDialog = null)
    }

    private fun launchUpdateCheck(
        showUpToDateMessage: Boolean,
        expectedDialog: ControlDialogKind?,
    ) {
        if (!exclusiveActionInProgress.compareAndSet(false, true)) {
            if (showUpToDateMessage) {
                publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
            }
            return
        }
        if (!updateCheckInProgress.compareAndSet(false, true)) {
            exclusiveActionInProgress.set(false)
            return
        }
        _uiState.update { it.copy(isCheckingForUpdates = true) }
        viewModelScope.launch {
            try {
                checkForUpdatesInternal(showUpToDateMessage, expectedDialog)
            } finally {
                finishUpdateCheckState()
            }
        }
    }

    private fun finishUpdateCheckState() {
        _uiState.update { it.copy(isCheckingForUpdates = false) }
        updateCheckInProgress.set(false)
        exclusiveActionInProgress.set(false)
    }

    private fun rejectUnavailableModuleOperation(): Boolean {
        if (_uiState.value.isModuleOperational && _uiState.value.hasRootAccess) return false
        publishMessage(UiText.Resource(R.string.control_module_not_ready))
        return true
    }

    private fun rejectUnavailableSettingMutation(): Boolean {
        if (_uiState.value.canEditSettings) return false
        publishMessage(UiText.Resource(R.string.control_module_not_ready))
        return true
    }

    private fun rejectConflictingOperation(): Boolean {
        val state = _uiState.value
        if (state.pendingDialog != null) return true
        if (!state.isCheckingForUpdates && !state.isToggling && !state.isUpdating &&
            !state.isSavingSettings && !state.isFullRollbackInProgress &&
            !state.isModulePurgeInProgress &&
            !toggleInProgress.get() && !settingMutationInProgress.get() &&
            !updateCheckInProgress.get() && !exclusiveActionInProgress.get() &&
            !ServiceLifecycleController.isAppUpdateInProgress() &&
            !ServiceLifecycleController.isFullRollbackInProgress() &&
            !ModulePurgeController.isInProgress()
        ) return false
        publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
        return true
    }

    private suspend fun checkForUpdatesInternal(
        showUpToDateMessage: Boolean,
        expectedDialog: ControlDialogKind?,
    ) {
        val state = _uiState.value
        val comparableModuleVersion = state.moduleVersion.takeIf {
            it.isNotBlank() && (
                state.moduleInstallState in setOf(
                    ModuleInstallState.READY,
                    ModuleInstallState.DISABLED,
                ) || state.pendingModuleState == PendingModuleState.READY
                )
        }
        val result = updateManager.checkForUpdates(
            currentModuleVersion = comparableModuleVersion,
            allowModuleUpdate = state.hasRootAccess &&
                state.moduleMutationState == ModuleMutationState.IDLE &&
                state.pendingModuleState == PendingModuleState.NONE &&
                state.moduleInstallState.allowsModuleUpdate,
        )
        if (
            _uiState.value.isFullRollbackInProgress ||
            _uiState.value.isModulePurgeInProgress ||
            ServiceLifecycleController.isFullRollbackInProgress() ||
            ModulePurgeController.isInProgress()
        ) {
            return
        }
        if (_uiState.value.pendingDialog != expectedDialog) return
        when (result) {
            is UpdateManager.UpdateResult.Available -> showUpdateDialog(result.release)
            is UpdateManager.UpdateResult.UpToDate -> {
                if (_uiState.value.pendingDialog == ControlDialogKind.UPDATE) dismissDialog()
                if (showUpToDateMessage) {
                    publishMessage(UiText.Resource(R.string.control_app_up_to_date))
                }
            }
            is UpdateManager.UpdateResult.Error -> {
                if (_uiState.value.pendingDialog == ControlDialogKind.UPDATE) dismissDialog()
                publishMessage(result.reason.toUiText())
            }
        }
    }

    fun updateAll(release: UpdateManager.Release) {
        if (_uiState.value.pendingDialog != ControlDialogKind.UPDATE ||
            _uiState.value.updateRelease != release
        ) return
        if (!exclusiveActionInProgress.compareAndSet(false, true)) {
            publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
            return
        }
        if (
            _uiState.value.isToggling ||
            _uiState.value.isSavingSettings ||
            settingMutationInProgress.get() ||
            updateCheckInProgress.get() ||
            _uiState.value.isFullRollbackInProgress ||
            _uiState.value.isModulePurgeInProgress ||
            ServiceLifecycleController.isFullRollbackInProgress() ||
            ModulePurgeController.isInProgress()
        ) {
            exclusiveActionInProgress.set(false)
            publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
            return
        }
        if (!ServiceLifecycleController.tryBeginAppUpdate()) {
            exclusiveActionInProgress.set(false)
            publishMessage(UiText.Resource(R.string.control_wait_operation_finish))
            return
        }
        _uiState.update {
            it.copy(
                isUpdating = true,
                updateProgress = 0f,
                updateStatus = UiText.Resource(R.string.update_in_progress),
            )
        }

        viewModelScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) {
                    // Module installation acquires the shared lifecycle lock inside UpdateManager.
                    updateManager.updateAll(release) { progress ->
                        _uiState.update {
                            it.copy(
                                updateProgress = progress.normalizedFraction,
                                updateStatus = progress.toUiText(),
                            )
                        }
                    }
                })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                _uiState.update {
                    it.copy(isUpdating = false, updateProgress = 0f, updateStatus = null)
                }
                ServiceLifecycleController.finishAppUpdate()
                exclusiveActionInProgress.set(false)
            }

            result.onSuccess { report ->
                when (val terminal = report.toTerminalOutcome()) {
                    is UpdateTerminalOutcome.Partial -> {
                        val failureText = terminal.failure?.toUiText()
                        dismissDialog()
                        recordLastResult(
                            if (terminal.requiresReboot) {
                                ControlLastResult.UPDATE_PARTIAL_REBOOT
                            } else {
                                ControlLastResult.UPDATE_PARTIAL
                            },
                        )
                        publishMessage(
                            failureText?.let {
                                UiText.resource(
                                    if (terminal.requiresReboot) {
                                        R.string.control_update_partial_reboot_details
                                    } else {
                                        R.string.control_update_partial_details
                                    },
                                    it,
                                )
                            } ?: UiText.Resource(
                                if (terminal.requiresReboot) {
                                    R.string.control_update_partial_reboot
                                } else {
                                    R.string.control_update_partial
                                },
                            ),
                        )
                        if (!terminal.requiresReboot) {
                            refreshStatusAfterUpdate()
                        }
                    }
                    is UpdateTerminalOutcome.Failed -> showErrorDialog(
                        kind = ControlErrorKind.UPDATE,
                        details = terminal.failure.toUiText(),
                    )
                    is UpdateTerminalOutcome.ApkInstallerPending -> {
                        dismissDialog()
                        recordLastResult(
                            if (terminal.requiresReboot) {
                                ControlLastResult.UPDATE_APK_PENDING_REBOOT
                            } else {
                                ControlLastResult.UPDATE_APK_PENDING
                            },
                        )
                        publishMessage(
                            UiText.Resource(
                                if (terminal.requiresReboot) {
                                    R.string.control_update_apk_pending_reboot
                                } else {
                                    R.string.control_update_apk_pending
                                },
                            ),
                        )
                    }
                    is UpdateTerminalOutcome.Installed -> {
                        dismissDialog()
                        val requiresReboot = terminal.requiresReboot
                        recordLastResult(
                            if (requiresReboot) {
                                ControlLastResult.UPDATE_REBOOT_REQUIRED
                            } else {
                                ControlLastResult.UPDATE_COMPLETED
                            },
                        )
                        publishMessage(
                            UiText.Resource(
                                if (requiresReboot) {
                                    R.string.control_update_installed_reboot
                                } else {
                                    R.string.control_update_completed
                                },
                            ),
                        )
                        if (!requiresReboot) {
                            refreshStatusAfterUpdate()
                        }
                    }
                    UpdateTerminalOutcome.Invalid -> showErrorDialog(
                        kind = ControlErrorKind.UPDATE,
                        details = UiText.Resource(R.string.control_unknown_error),
                    )
                }
            }.onFailure {
                showErrorDialog(
                    kind = ControlErrorKind.UPDATE,
                    details = UiText.Resource(R.string.control_unknown_error),
                )
            }
        }
    }

    private suspend fun refreshStatusAfterUpdate() {
        delay(UPDATE_STATUS_REFRESH_DELAY_MS)
        if (screenStarted) refreshServiceStatusOnce()
    }

    private suspend fun restartService() {
        try {
            val currentStatus = refreshStatus()
            if (!currentStatus.isRunning) return
            val result = ServiceLifecycleController.restart()
            val verifiedState = refreshStatus()
            if (result.success && verifiedState.isRunning) {
                serviceEventBus.notifyServiceRestarted(ServiceEventSource.CONTROL)
                return
            }
            val diagnostic = result.diagnosticText()
            showErrorDialog(
                kind = ControlErrorKind.RESTART_SERVICE,
                details = if (diagnostic.isBlank()) {
                    UiText.Resource(R.string.control_restart_unhealthy)
                } else {
                    UiText.Dynamic(diagnostic)
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            showErrorDialog(
                kind = ControlErrorKind.RESTART_SERVICE,
                details = UiText.Resource(R.string.control_restart_unhealthy),
            )
        }
    }

}

internal fun sanitizedBoundedUiDiagnostic(text: String): String =
    redactedBoundedLogShareText(text).takeLast(MAX_ERROR_DETAIL_LENGTH)

internal fun restoreControlLastResult(savedStateHandle: SavedStateHandle): ControlLastResult? {
    return savedStateHandle.restoreEnumNameOrRemove(KEY_LAST_RESULT)
}
