package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateExecutionTest {

    @Test
    fun progress_clampsFractionsAndPercentsAtThePresentationBoundary() {
        val below = UpdateProgress(-0.25f, UpdateStage.DOWNLOADING_MODULE, -5)
        val above = UpdateProgress(1.25f, UpdateStage.DOWNLOADING_APK, 125)

        assertEquals(0f, below.normalizedFraction)
        assertEquals(0, below.normalizedPercent)
        assertEquals(1f, above.normalizedFraction)
        assertEquals(100, above.normalizedPercent)
    }

    @Test
    fun terminalOutcome_preservesPartialFailureAfterModuleProgress() {
        val report = UpdateExecutionReport(
            module = ModuleArtifactOutcome.Installed(requiresReboot = true),
            apk = ApkArtifactOutcome.Failed(UpdateFailure.ApkInstallerUnavailable),
        )

        val terminal = report.toTerminalOutcome() as UpdateTerminalOutcome.Partial

        assertTrue(report.isPartial)
        assertTrue(report.requiresReboot)
        assertTrue(terminal.requiresReboot)
        assertEquals(UpdateFailure.ApkInstallerUnavailable, terminal.failure)
    }

    @Test
    fun terminalOutcome_keepsModuleFailureTerminalWhenApkWasDeferred() {
        val report = UpdateExecutionReport(
            module = ModuleArtifactOutcome.Failed(
                UpdateFailure.Download(DownloadFailureReason.CHECKSUM_MISMATCH),
            ),
            apk = ApkArtifactOutcome.Deferred(UpdateDeferredReason.MODULE_INSTALLATION_FAILED),
        )

        val terminal = report.toTerminalOutcome() as UpdateTerminalOutcome.Failed

        assertFalse(report.isPartial)
        assertTrue(report.hasFailure)
        assertEquals(
            UpdateFailure.Download(DownloadFailureReason.CHECKSUM_MISMATCH),
            terminal.failure,
        )
        assertEquals(
            UpdateDeferredReason.MODULE_INSTALLATION_FAILED,
            (report.apk as ApkArtifactOutcome.Deferred).reason,
        )
    }

    @Test
    fun terminalOutcome_reportsModuleDeferralWhenApkPreflightFails() {
        val report = UpdateExecutionReport(
            module = ModuleArtifactOutcome.Deferred(UpdateDeferredReason.APK_PREFLIGHT_FAILED),
            apk = ApkArtifactOutcome.Failed(
                UpdateFailure.Validation(ArtifactValidationReason.APK_SIGNER_MISMATCH),
            ),
        )

        val terminal = report.toTerminalOutcome() as UpdateTerminalOutcome.Failed

        assertFalse(report.madeProgress)
        assertFalse(report.isPartial)
        assertEquals(
            UpdateFailure.Validation(ArtifactValidationReason.APK_SIGNER_MISMATCH),
            terminal.failure,
        )
        assertEquals(
            UpdateDeferredReason.APK_PREFLIGHT_FAILED,
            (report.module as ModuleArtifactOutcome.Deferred).reason,
        )
    }

    @Test
    fun terminalOutcome_preservesCombinedApkPendingAndRebootRequirement() {
        val report = UpdateExecutionReport(
            module = ModuleArtifactOutcome.Installed(requiresReboot = true),
            apk = ApkArtifactOutcome.InstallerPending,
        )

        assertEquals(
            UpdateTerminalOutcome.ApkInstallerPending(requiresReboot = true),
            report.toTerminalOutcome(),
        )
    }

    @Test
    fun terminalOutcome_distinguishesInstalledAndInvalidReports() {
        assertEquals(
            UpdateTerminalOutcome.Installed(requiresReboot = false),
            UpdateExecutionReport(
                module = ModuleArtifactOutcome.Installed(requiresReboot = false),
                apk = ApkArtifactOutcome.NotRequested,
            ).toTerminalOutcome(),
        )
        assertEquals(
            UpdateTerminalOutcome.Invalid,
            UpdateExecutionReport(
                module = ModuleArtifactOutcome.NotRequested,
                apk = ApkArtifactOutcome.NotRequested,
            ).toTerminalOutcome(),
        )
    }

    @Test
    fun outdatedArtifactFilter_keepsDigestBoundToItsExactUrl() {
        val apk = UpdateManager.ReleaseArtifact("https://example.test/app.apk", "11".repeat(32))
        val module = UpdateManager.ReleaseArtifact("https://example.test/module.zip", "22".repeat(32))
        val release = UpdateManager.Release(
            version = "2.0.0",
            apkArtifact = apk,
            moduleArtifact = module,
            changelog = "changes",
        )

        val apkOnly = release.onlyOutdatedArtifacts(apkOutdated = true, moduleOutdated = false)

        assertEquals(apk, apkOnly.apkArtifact)
        assertEquals(apk.url, apkOnly.apkUrl)
        assertNull(apkOnly.moduleArtifact)
        assertNull(apkOnly.moduleUrl)
        assertFalse(apkOnly.allowSameVersionModuleRepair)

        val repair = release.onlyOutdatedArtifacts(
            apkOutdated = false,
            moduleOutdated = true,
            allowSameVersionModuleRepair = true,
        )
        assertEquals(module, repair.moduleArtifact)
        assertTrue(repair.allowSameVersionModuleRepair)
    }

    @Test
    fun moduleInstall_reconcilesIndeterminateSubmissionWithoutRetryingIt() {
        assertTrue(
            shouldVerifyStandardInstallPublication(
                ServiceLifecycleController.CommandResult(success = true),
            ),
        )
        assertTrue(
            shouldVerifyStandardInstallPublication(
                ServiceLifecycleController.CommandResult(
                    success = false,
                    indeterminate = true,
                ),
            ),
        )
        assertFalse(
            shouldVerifyStandardInstallPublication(
                ServiceLifecycleController.CommandResult(success = false),
            ),
        )
    }
}
