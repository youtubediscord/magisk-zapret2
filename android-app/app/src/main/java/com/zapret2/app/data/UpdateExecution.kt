package com.zapret2.app.data

/** A typed progress stage; presentation code owns the localized copy. */
internal enum class UpdateStage {
    DOWNLOADING_MODULE,
    INSTALLING_MODULE,
    MODULE_INSTALLED,
    DOWNLOADING_APK,
    VALIDATING_ARTIFACTS,
    OPENING_APK_INSTALLER,
    APK_INSTALLER_PENDING,
    COMPLETE,
}

internal data class UpdateProgress(
    val fraction: Float,
    val stage: UpdateStage,
    val percent: Int? = null,
) {
    val normalizedFraction: Float = fraction.coerceIn(0f, 1f)
    val normalizedPercent: Int? = percent?.coerceIn(0, 100)
}

internal enum class DownloadFailureReason {
    SECURITY_POLICY_REJECTED,
    SERVER_REJECTED,
    TOO_MANY_REDIRECTS,
    TOO_LARGE,
    STORAGE_UNAVAILABLE,
    CHECKSUM_MISMATCH,
    NO_INTERNET,
    CONNECTION_TIMEOUT,
    SECURE_CONNECTION_FAILED,
    FAILED,
}

internal enum class ArtifactValidationReason {
    APK_FILE_INVALID,
    APK_UNREADABLE,
    APK_PACKAGE_ID_MISMATCH,
    APK_NOT_NEWER,
    APK_VERSION_CODE_MISMATCH,
    APK_VERSION_MISMATCH,
    INSTALLED_APK_SIGNER_UNAVAILABLE,
    APK_SIGNER_UNAVAILABLE,
    APK_SIGNER_MISMATCH,
    APK_VALIDATION_FAILED,
    MODULE_TOO_MANY_ENTRIES,
    MODULE_UNSAFE_OR_DUPLICATE_PATH,
    MODULE_ENTRY_TOO_LARGE,
    MODULE_EXPANDED_SIZE_TOO_LARGE,
    MODULE_EMPTY,
    MODULE_IDENTITY_MISSING,
    MODULE_PACKAGE_INVALID,
    MODULE_VALIDATION_FAILED,
}

internal sealed interface UpdateFailure {
    data class Download(val reason: DownloadFailureReason) : UpdateFailure

    data class Validation(val reason: ArtifactValidationReason) : UpdateFailure

    data object UnsupportedAbi : UpdateFailure
    data object ApkInstallerUnavailable : UpdateFailure

    data class ModuleRecoveryRequired(val diagnostic: String) : UpdateFailure

    data object ModuleRejected : UpdateFailure
    data object ModuleInstallationFailed : UpdateFailure
}

internal enum class UpdateDeferredReason {
    MODULE_PREFLIGHT_FAILED,
    APK_PREFLIGHT_FAILED,
    MODULE_INSTALLATION_FAILED,
}

internal sealed interface ModuleArtifactOutcome {
    data object NotRequested : ModuleArtifactOutcome

    data class Installed(
        val requiresReboot: Boolean,
        val restartService: Boolean = false,
    ) : ModuleArtifactOutcome

    /** The requested module was deliberately not installed because another preflight failed. */
    data class Deferred(
        val reason: UpdateDeferredReason,
    ) : ModuleArtifactOutcome

    data class Failed(
        val failure: UpdateFailure,
    ) : ModuleArtifactOutcome
}

internal sealed interface ApkArtifactOutcome {
    data object NotRequested : ApkArtifactOutcome

    /** The system package installer owns the remaining user-confirmed step. */
    data object InstallerPending : ApkArtifactOutcome

    /** The APK was deliberately not attempted after an earlier artifact failed. */
    data class Deferred(
        val reason: UpdateDeferredReason,
    ) : ApkArtifactOutcome

    data class Failed(
        val failure: UpdateFailure,
    ) : ApkArtifactOutcome
}

internal data class UpdateExecutionReport(
    val module: ModuleArtifactOutcome,
    val apk: ApkArtifactOutcome,
) {
    val requiresReboot: Boolean
        get() = (module as? ModuleArtifactOutcome.Installed)?.requiresReboot == true

    val restartService: Boolean
        get() = (module as? ModuleArtifactOutcome.Installed)?.restartService == true

    val madeProgress: Boolean
        get() = module is ModuleArtifactOutcome.Installed || apk is ApkArtifactOutcome.InstallerPending

    val hasFailure: Boolean
        get() = module is ModuleArtifactOutcome.Failed || apk is ApkArtifactOutcome.Failed

    val isPartial: Boolean
        get() = madeProgress && (
            hasFailure ||
                module is ModuleArtifactOutcome.Deferred ||
                apk is ApkArtifactOutcome.Deferred
        )

    val primaryFailure: UpdateFailure?
        get() = (module as? ModuleArtifactOutcome.Failed)?.failure
            ?: (apk as? ApkArtifactOutcome.Failed)?.failure
}

/** One deterministic terminal state consumed by presentation and unit tests. */
internal sealed interface UpdateTerminalOutcome {
    data class Partial(
        val requiresReboot: Boolean,
        val failure: UpdateFailure?,
        val restartService: Boolean = false,
    ) : UpdateTerminalOutcome

    data class Failed(val failure: UpdateFailure) : UpdateTerminalOutcome

    data class ApkInstallerPending(
        val requiresReboot: Boolean,
        val restartService: Boolean = false,
    ) : UpdateTerminalOutcome

    data class Installed(
        val requiresReboot: Boolean,
        val restartService: Boolean = false,
    ) : UpdateTerminalOutcome

    data object Invalid : UpdateTerminalOutcome
}

internal fun UpdateExecutionReport.toTerminalOutcome(): UpdateTerminalOutcome = when {
    isPartial -> UpdateTerminalOutcome.Partial(requiresReboot, primaryFailure, restartService)
    hasFailure -> UpdateTerminalOutcome.Failed(requireNotNull(primaryFailure))
    apk is ApkArtifactOutcome.InstallerPending ->
        UpdateTerminalOutcome.ApkInstallerPending(requiresReboot, restartService)
    module is ModuleArtifactOutcome.Installed ->
        UpdateTerminalOutcome.Installed(requiresReboot, restartService)
    else -> UpdateTerminalOutcome.Invalid
}

/** Filters the actionable release itself so already-current artifacts cannot be installed again. */
internal fun UpdateManager.Release.onlyOutdatedArtifacts(
    apkOutdated: Boolean,
    moduleOutdated: Boolean,
    allowSameVersionModuleRepair: Boolean = false,
): UpdateManager.Release = copy(
    apkArtifact = apkArtifact.takeIf { apkOutdated },
    moduleArtifact = moduleArtifact.takeIf { moduleOutdated },
    allowSameVersionModuleRepair = moduleOutdated && allowSameVersionModuleRepair,
)
