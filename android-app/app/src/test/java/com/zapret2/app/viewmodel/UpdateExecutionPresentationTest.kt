package com.zapret2.app.viewmodel

import androidx.lifecycle.SavedStateHandle
import com.zapret2.app.R
import com.zapret2.app.data.ArtifactValidationReason
import com.zapret2.app.data.DownloadFailureReason
import com.zapret2.app.data.UpdateFailure
import com.zapret2.app.data.UpdateManager
import com.zapret2.app.data.UpdateProgress
import com.zapret2.app.data.UpdateStage
import com.zapret2.app.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateExecutionPresentationTest {

    @Test
    fun progressStages_mapToTypedLocalizedResources() {
        val expected = mapOf(
            UpdateStage.DOWNLOADING_MODULE to R.string.update_stage_downloading_module,
            UpdateStage.INSTALLING_MODULE to R.string.update_stage_installing_module,
            UpdateStage.MODULE_INSTALLED to R.string.update_stage_module_installed,
            UpdateStage.DOWNLOADING_APK to R.string.update_stage_downloading_apk,
            UpdateStage.VALIDATING_ARTIFACTS to R.string.update_stage_validating_artifacts,
            UpdateStage.OPENING_APK_INSTALLER to R.string.update_stage_opening_apk_installer,
            UpdateStage.APK_INSTALLER_PENDING to R.string.update_stage_apk_installer_pending,
            UpdateStage.COMPLETE to R.string.update_stage_complete,
        )

        expected.forEach { (stage, resourceId) ->
            val text = UpdateProgress(0.5f, stage).toUiText() as UiText.Resource
            assertEquals(stage.name, resourceId, text.id)
            assertEquals(stage.name, emptyList<Any>(), text.arguments)
        }
    }

    @Test
    fun downloadProgress_usesClampedFormattedPercentResources() {
        val module = UpdateProgress(
            fraction = -1f,
            stage = UpdateStage.DOWNLOADING_MODULE,
            percent = -10,
        ).toUiText() as UiText.Resource
        val apk = UpdateProgress(
            fraction = 2f,
            stage = UpdateStage.DOWNLOADING_APK,
            percent = 110,
        ).toUiText() as UiText.Resource

        assertEquals(R.string.update_stage_downloading_module_percent, module.id)
        assertEquals(listOf(0), module.arguments)
        assertEquals(R.string.update_stage_downloading_apk_percent, apk.id)
        assertEquals(listOf(100), apk.arguments)
    }

    @Test
    fun updateCheckFailures_mapToLocalizedTypedResources() {
        val expected = mapOf(
            UpdateManager.UpdateCheckFailure.NoInternet to R.string.control_update_check_no_internet,
            UpdateManager.UpdateCheckFailure.ConnectionTimeout to R.string.control_update_check_timeout,
            UpdateManager.UpdateCheckFailure.SecureConnectionFailed to
                R.string.control_update_check_secure_connection_failed,
            UpdateManager.UpdateCheckFailure.MetadataTooLarge to
                R.string.control_update_check_metadata_too_large,
            UpdateManager.UpdateCheckFailure.EmptyResponse to
                R.string.control_update_check_empty_response,
            UpdateManager.UpdateCheckFailure.InvalidMetadata to
                R.string.control_update_check_invalid_metadata,
            UpdateManager.UpdateCheckFailure.RequestFailed to R.string.control_update_check_failed,
        )

        expected.forEach { (failure, resourceId) ->
            val text = failure.toUiText() as UiText.Resource
            assertEquals(resourceId, text.id)
            assertEquals(emptyList<Any>(), text.arguments)
        }

        val http = UpdateManager.UpdateCheckFailure.ServerResponse(503).toUiText() as UiText.Resource
        assertEquals(R.string.control_update_check_http_failed, http.id)
        assertEquals(listOf(503), http.arguments)
    }

    @Test
    fun updateExecutionFailures_mapAppOwnedReasonsToResources() {
        val expectedDownloads = mapOf(
            DownloadFailureReason.SECURITY_POLICY_REJECTED to
                R.string.control_update_download_security_rejected,
            DownloadFailureReason.SERVER_REJECTED to
                R.string.control_update_download_server_rejected,
            DownloadFailureReason.TOO_MANY_REDIRECTS to
                R.string.control_update_download_redirects,
            DownloadFailureReason.TOO_LARGE to R.string.control_update_download_too_large,
            DownloadFailureReason.STORAGE_UNAVAILABLE to R.string.control_update_download_storage,
            DownloadFailureReason.CHECKSUM_MISMATCH to R.string.control_update_download_checksum,
            DownloadFailureReason.NO_INTERNET to R.string.control_update_download_no_internet,
            DownloadFailureReason.CONNECTION_TIMEOUT to R.string.control_update_download_timeout,
            DownloadFailureReason.SECURE_CONNECTION_FAILED to
                R.string.control_update_download_secure_connection_failed,
            DownloadFailureReason.FAILED to R.string.control_update_download_failed,
        )
        assertEquals(enumValues<DownloadFailureReason>().toSet(), expectedDownloads.keys)
        expectedDownloads.forEach { (reason, resourceId) ->
            assertEquals(
                UiText.Resource(resourceId),
                UpdateFailure.Download(reason).toUiText(),
            )
        }

        val expectedValidations = mapOf(
            ArtifactValidationReason.APK_FILE_INVALID to R.string.control_update_apk_file_invalid,
            ArtifactValidationReason.APK_UNREADABLE to R.string.control_update_apk_unreadable,
            ArtifactValidationReason.APK_PACKAGE_ID_MISMATCH to
                R.string.control_update_apk_package_mismatch,
            ArtifactValidationReason.APK_NOT_NEWER to R.string.control_update_apk_not_newer,
            ArtifactValidationReason.APK_VERSION_CODE_MISMATCH to
                R.string.control_update_apk_version_code_mismatch,
            ArtifactValidationReason.APK_VERSION_MISMATCH to
                R.string.control_update_apk_version_mismatch,
            ArtifactValidationReason.INSTALLED_APK_SIGNER_UNAVAILABLE to
                R.string.control_update_installed_signer_unavailable,
            ArtifactValidationReason.APK_SIGNER_UNAVAILABLE to
                R.string.control_update_apk_signer_unavailable,
            ArtifactValidationReason.APK_SIGNER_MISMATCH to
                R.string.control_update_apk_signer_mismatch,
            ArtifactValidationReason.APK_VALIDATION_FAILED to
                R.string.control_update_apk_validation_failed,
            ArtifactValidationReason.MODULE_TOO_MANY_ENTRIES to
                R.string.control_update_module_too_many_entries,
            ArtifactValidationReason.MODULE_UNSAFE_OR_DUPLICATE_PATH to
                R.string.control_update_module_unsafe_path,
            ArtifactValidationReason.MODULE_ENTRY_TOO_LARGE to
                R.string.control_update_module_entry_too_large,
            ArtifactValidationReason.MODULE_EXPANDED_SIZE_TOO_LARGE to
                R.string.control_update_module_expanded_too_large,
            ArtifactValidationReason.MODULE_EMPTY to R.string.control_update_module_empty,
            ArtifactValidationReason.MODULE_IDENTITY_MISSING to
                R.string.control_update_module_identity_missing,
            ArtifactValidationReason.MODULE_PACKAGE_INVALID to
                R.string.control_update_module_package_invalid,
            ArtifactValidationReason.MODULE_VALIDATION_FAILED to
                R.string.control_update_module_validation_failed,
        )
        assertEquals(enumValues<ArtifactValidationReason>().toSet(), expectedValidations.keys)
        expectedValidations.forEach { (reason, resourceId) ->
            assertEquals(
                UiText.Resource(resourceId),
                UpdateFailure.Validation(reason).toUiText(),
            )
        }

        assertEquals(
            UiText.Resource(R.string.control_update_unsupported_abi),
            UpdateFailure.UnsupportedAbi.toUiText(),
        )
        assertEquals(
            UiText.Resource(R.string.control_update_apk_installer_unavailable),
            UpdateFailure.ApkInstallerUnavailable.toUiText(),
        )
        assertEquals(
            UiText.Resource(R.string.control_update_module_install_failed),
            UpdateFailure.ModuleInstallationFailed.toUiText(),
        )
        assertEquals(
            UiText.Resource(R.string.control_update_module_rejected),
            UpdateFailure.ModuleRejected.toUiText(),
        )

        assertEquals(
            UiText.resource(
                R.string.control_update_module_recovery_required_details,
                "cleanup incomplete",
            ),
            UpdateFailure.ModuleRecoveryRequired("cleanup incomplete").toUiText(),
        )
        assertEquals(
            UiText.Resource(R.string.control_update_module_recovery_required),
            UpdateFailure.ModuleRecoveryRequired("").toUiText(),
        )
    }

    @Test
    fun durableCombinedOutcome_keepsPendingInstallerAndRebootCopy() {
        assertEquals(
            R.string.control_update_apk_pending_reboot,
            ControlLastResult.UPDATE_APK_PENDING_REBOOT.messageRes,
        )
        assertEquals(
            ControlLastResult.UPDATE_APK_PENDING_REBOOT,
            restoreControlLastResult(
                SavedStateHandle(
                    mapOf(
                        "control_last_result" to ControlLastResult.UPDATE_APK_PENDING_REBOOT.name,
                    ),
                ),
            ),
        )
    }

    @Test
    fun durablePartialOutcome_keepsRebootRequirement() {
        assertEquals(
            R.string.control_update_partial_reboot,
            ControlLastResult.UPDATE_PARTIAL_REBOOT.messageRes,
        )
        assertEquals(
            ControlLastResult.UPDATE_PARTIAL_REBOOT,
            restoreControlLastResult(
                SavedStateHandle(
                    mapOf(
                        "control_last_result" to ControlLastResult.UPDATE_PARTIAL_REBOOT.name,
                    ),
                ),
            ),
        )
    }
}
