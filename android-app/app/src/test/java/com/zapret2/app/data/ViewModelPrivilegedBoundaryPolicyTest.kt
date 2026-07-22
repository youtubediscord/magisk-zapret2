package com.zapret2.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewModelPrivilegedBoundaryPolicyTest {

    @Test
    fun viewModels_doNotConstructOrExecuteRawPrivilegedCommands() {
        val directory = repositoryDirectory("android-app/app/src/main/java/com/zapret2/app/viewmodel")
        val sources = directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())

        val forbidden = listOf(
            "com.topjohnwu.superuser.Shell",
            "Shell.cmd(",
            "ServiceLifecycleController.executeRoot(",
            "RootFileIo.",
            "contentResolver",
            "OpenableColumns",
            "java.io.",
        )
        sources.forEach { source ->
            val text = source.readText()
            forbidden.forEach { token ->
                assertFalse("${source.name} bypasses the typed privileged boundary with $token", text.contains(token))
            }
        }
    }

    @Test
    fun hostlistLogsAndControl_useTheirTypedRepositories() {
        val hostlistContent = source("viewmodel/HostlistContentViewModel.kt")
        val hostlists = source("viewmodel/HostlistsViewModel.kt")
        val logs = source("viewmodel/LogsViewModel.kt")
        val control = source("viewmodel/ControlViewModel.kt")
        val commandLines = source("data/CommandLineRepository.kt")

        assertTrue(hostlistContent.contains("HostlistRepository"))
        assertTrue(hostlists.contains("HostlistRepository"))
        assertTrue(hostlists.contains("HostlistImportReader"))
        assertTrue(hostlistContent.contains("writeIfUnchangedWithRollback"))
        assertTrue(hostlists.contains("writeWithRollback"))
        assertTrue(source("data/HostlistRepository.kt").split("allowEmpty = true").size - 1 == 2)
        assertTrue(logs.contains("RuntimeLogRepository"))
        assertTrue(control.contains("ControlDiagnosticsRepository"))
        assertTrue(control.contains("RuntimeLogRepository"))
        assertTrue(source("viewmodel/HostsEditorViewModel.kt").contains("HostsOverlayRepository"))
        assertTrue(source("viewmodel/DnsManagerViewModel.kt").contains("HostsOverlayRepository"))
        assertTrue(source("viewmodel/ConfigEditorViewModel.kt").contains("CommandLineRepository"))
        assertFalse(commandLines.contains("RuntimeLogRepository"))
        assertTrue(commandLines.contains("CommandLineSnapshot.Missing -> CommandLineRead.Empty"))
        assertTrue(commandLines.contains("allowEmpty = true"))
        assertTrue(source("data/RootFileIo.kt").contains("val minimumBytes = if (allowEmpty) 0 else 1"))
    }

    @Test
    fun multiStepModuleWriters_shareTheCoordinatorOwnedCancellationFence() {
        val coordinator = source("data/ModuleMutationCoordinator.kt")
        assertTrue(coordinator.contains("suspend fun <T> withNonCancellableMutation"))
        assertTrue(
            coordinator.contains(
                "withMutation { withContext(NonCancellable) { block() } }",
            ),
        )

        val expectedBoundaries = mapOf(
            "viewmodel/ConfigEditorViewModel.kt" to 1,
            "viewmodel/DnsManagerViewModel.kt" to 2,
            "viewmodel/HostlistContentViewModel.kt" to 1,
            "viewmodel/HostlistsViewModel.kt" to 1,
            "viewmodel/HostsEditorViewModel.kt" to 2,
            "viewmodel/StrategiesViewModel.kt" to 2,
            "viewmodel/ControlViewModel.kt" to 2,
            "viewmodel/LogsViewModel.kt" to 1,
            "data/PresetRepository.kt" to 1,
        )
        expectedBoundaries.forEach { (path, expectedCount) ->
            val production = source(path)
            assertTrue(
                "$path must keep every multi-step writer behind the shared cancellation fence",
                production.split("withNonCancellableMutation").size - 1 == expectedCount,
            )
        }

        val control = source("viewmodel/ControlViewModel.kt")
        val initialLoad = control.substringAfter("private fun loadInitialState()")
            .substringBefore("private fun applyEnvironmentSnapshot(")
        val settingMutation = control.substringAfter("private fun launchSettingMutation(")
            .substringBefore("fun dismissQuicBanner()")
        assertTrue(
            "deprecated wifi_only normalization must finish its admitted runtime mutation",
            initialLoad.contains(
                "ModuleMutationCoordinator.withNonCancellableMutation {\n" +
                    "                            RuntimeConfigStore.upsertCoreValue(\"wifi_only\", \"0\")",
            ),
        )
        assertTrue(
            "every Control setting mutation must share the coordinator-owned cancellation fence",
            settingMutation.contains(
                "ModuleMutationCoordinator.withNonCancellableMutation(block)",
            ),
        )
        assertFalse(
            "individual packet settings must not own a second transaction fence",
            control.substringAfter("fun adjustPktOut(value: Int)")
                .substringBefore("private fun launchSettingMutation(")
                .contains("withNonCancellableMutation"),
        )

        val viewModels = repositoryDirectory(
            "android-app/app/src/main/java/com/zapret2/app/viewmodel",
        ).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        viewModels.forEach { file ->
            val production = file.readText()
            assertFalse(
                "${file.name} restored a caller-owned transaction cancellation fence",
                production.contains("Dispatchers.IO + NonCancellable"),
            )
        }

        val directAtomicBoundaries = mapOf(
            "data/RuntimeConfigStore.kt" to 2,
            "data/StrategyRepository.kt" to 1,
        )
        val productionFiles = repositoryDirectory(
            "android-app/app/src/main/java/com/zapret2/app",
        ).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        productionFiles.forEach { file ->
            val production = file.readText()
            val directCount = production.split("ModuleMutationCoordinator.withMutation {").size - 1
            val relative = file.invariantSeparatorsPath.substringAfter("/com/zapret2/app/")
            assertTrue(
                "$relative added a direct mutation boundary; use the transaction fence for multi-step writes",
                directCount == directAtomicBoundaries.getOrDefault(relative, 0),
            )
        }
    }

    @Test
    fun controlFailureLogRead_neverBlocksTheMainDispatcher() {
        val control = source("viewmodel/ControlViewModel.kt")

        assertTrue(
            control.contains(
                "private suspend fun readServiceFailureLogs(): String = withContext(Dispatchers.IO)",
            ),
        )
        assertTrue(control.contains("logRepository.readFailureTail()"))
    }

    @Test
    fun dnsApply_revalidatesTheExactPackageCatalogUnderTheMutationLease() {
        val dns = source("viewmodel/DnsManagerViewModel.kt")
        val lease = dns.indexOf("ModuleMutationCoordinator.withNonCancellableMutation")
        val liveCatalog = dns.indexOf("val liveData = HostsIniParser.parse().data", lease)
        val equalityGuard = dns.indexOf(
            "if (liveData != data) return@withContext ApplyOutcome.CatalogChanged",
            liveCatalog,
        )
        val firstSnapshot = dns.indexOf("val previousOverlay = hostsRepository.snapshotOverlay()", lease)

        assertTrue(lease >= 0)
        assertTrue(liveCatalog > lease)
        assertTrue(equalityGuard > liveCatalog)
        assertTrue(firstSnapshot > equalityGuard)
        assertTrue(dns.contains("if (outcome == ApplyOutcome.CatalogChanged) loadData()"))
    }

    @Test
    fun strategiesPreservesCanonicalPacketCountsAndUsesFieldScopedMutations() {
        val strategies = source("viewmodel/StrategiesViewModel.kt")

        assertFalse(strategies.contains("runtimeCore[\"pkt_count\"]"))
        assertTrue(strategies.contains("runtimeCore[\"pkt_out\"]"))
        assertTrue(strategies.contains("RuntimeConfigStore.positiveCountOrNull(runtimeCore[\"pkt_out\"])"))
        assertFalse(strategies.contains("?: \"5\""))
        assertTrue(strategies.contains("StrategyConfigMutation.PacketCount"))
        assertTrue(strategies.contains("StrategyConfigMutation.LogMode"))
        assertTrue(strategies.contains("StrategyOrderSaveOutcome.BLOCKED"))
        assertTrue(strategies.contains("mapOf(mutation.categoryKey to mutation.strategyId)"))
        assertTrue(strategies.contains("StrategyRepository.validateCategoriesWithRuntimeBuilder()"))
        assertFalse(strategies.contains("desiredState.categories.associate"))
    }

    @Test
    fun controlUpdate_claimsGlobalAndUiBusyStateBeforeLaunchingWork() {
        val control = source("viewmodel/ControlViewModel.kt")
        val updateStart = control.indexOf("fun updateAll(release: UpdateManager.Release)")
        val localClaim = control.indexOf(
            "exclusiveActionInProgress.compareAndSet(false, true)",
            updateStart,
        )
        val globalClaim = control.indexOf("ServiceLifecycleController.tryBeginAppUpdate()", updateStart)
        val uiClaim = control.indexOf("isUpdating = true", globalClaim)
        val coroutineLaunch = control.indexOf("viewModelScope.launch", updateStart)

        assertTrue(updateStart >= 0)
        assertTrue(localClaim > updateStart)
        assertTrue(globalClaim > localClaim)
        assertTrue(uiClaim > globalClaim)
        assertTrue(coroutineLaunch > uiClaim)
        assertTrue(control.split("finishUpdateCheckState()").size - 1 == 2)
        assertTrue(control.contains("!settingMutationInProgress.get()"))
        assertTrue(control.contains("pendingDialog != ControlDialogKind.UPDATE ||"))
        assertTrue(control.contains("_uiState.value.updateRelease != release"))
    }

    @Test
    fun controlPublicActions_rejectStaleDialogsAndRefreshRuntimeAuthorityOnReturn() {
        val control = source("viewmodel/ControlViewModel.kt")
        val screenStart = control
            .substringAfter("fun onScreenStarted()")
            .substringBefore("fun onScreenStopped()")

        assertTrue(screenStart.contains("hasAuthoritativeRuntimeSettings = false"))
        assertTrue(screenStart.indexOf("hasAuthoritativeRuntimeSettings = false") <
            screenStart.indexOf("startPolling(refreshImmediately = true)"))
        assertTrue(control.contains("if (state.pendingDialog != ControlDialogKind.PACKET) return"))
        assertTrue(control.contains("if (state.pendingDialog != null) return true"))
        assertTrue(control.split("if (_uiState.value.pendingDialog != null)").size - 1 >= 2)
        assertTrue(control.contains("state.isUpdating ||\n            ServiceLifecycleController.isAppUpdateInProgress()"))
        assertTrue(control.contains("if (_uiState.value.autostart == enabled) return"))
        assertTrue(control.contains("if (_uiState.value.pktOut == value) return"))
        assertTrue(control.contains("if (_uiState.value.pktIn == value) return"))
    }

    @Test
    fun controlExclusiveActions_shareOneSynchronousGate() {
        val control = source("viewmodel/ControlViewModel.kt")

        assertTrue(control.contains("private val exclusiveActionInProgress = AtomicBoolean(false)"))
        assertTrue(control.contains("operationInProgress = exclusiveActionInProgress"))
        assertTrue(
            control.split("exclusiveActionInProgress.compareAndSet(false, true)").size - 1 == 4,
        )
        assertTrue(control.contains("!exclusiveActionInProgress.get()"))
        assertFalse(control.contains("private val rollbackInProgress"))

        listOf(
            "fun toggleService()",
            "private fun launchSettingMutation(",
            "private fun launchUpdateCheck(",
            "fun updateAll(release: UpdateManager.Release)",
        ).forEach { boundary ->
            val body = control.substringAfter(boundary).substringBefore("\n    }")
            assertTrue("$boundary must claim the shared gate", body.contains("exclusiveActionInProgress"))
        }
    }

    @Test
    fun admittedLifecycleScripts_finishPostStateVerificationBeforeCancellationEscapes() {
        val lifecycle = source("data/ServiceLifecycleController.kt")
        val fullRollback = lifecycle.substringAfter("suspend fun fullRollback()")
            .substringBefore("private suspend fun fullRollbackInsideExclusiveTask()")
        val perform = lifecycle.substringAfter("private suspend fun perform(action: Action)")
            .substringBefore("private fun Action.displayName()")

        assertTrue(fullRollback.contains("withContext(NonCancellable)"))
        assertTrue(fullRollback.contains("fullRollbackInsideExclusiveTask()"))
        assertTrue(fullRollback.contains("currentCoroutineContext().ensureActive()"))

        val fence = perform.indexOf("withContext(NonCancellable)")
        val command = perform.indexOf("val commandResult = executeRoot(", fence)
        val postState = perform.indexOf("status = getStatusLocked()", command)
        val cancellationExit = perform.indexOf("currentCoroutineContext().ensureActive()", postState)
        assertTrue(fence >= 0)
        assertTrue(command > fence && postState > command && cancellationExit > postState)
    }

    @Test
    fun controlUpdateCheck_resultIsBoundToItsLaunchingDialogState() {
        val control = source("viewmodel/ControlViewModel.kt")

        assertTrue(control.contains("expectedDialog: ControlDialogKind?"))
        assertTrue(control.contains("expectedDialog = ControlDialogKind.UPDATE"))
        assertTrue(control.contains("expectedDialog = null"))
        assertTrue(control.contains("checkForUpdatesInternal(showUpToDateMessage, expectedDialog)"))
        assertTrue(control.contains("if (_uiState.value.pendingDialog != expectedDialog) return"))
        assertFalse(control.contains("pendingDialog !in setOf(null, ControlDialogKind.UPDATE)"))
    }

    @Test
    fun controlRefresh_reusesOneMachineSnapshotAndRequiresIndependentTopology() {
        val control = source("viewmodel/ControlViewModel.kt")
        val network = source("data/NetworkStatsManager.kt")

        assertTrue(control.contains("networkStatsManager.getNetworkStats(serviceStatus)"))
        assertTrue(control.contains("serviceStatus.healthy && netStats.iptablesActive"))
        assertTrue(control.contains("hasAuthoritativeRuntimeSettings: Boolean = false"))
        assertTrue(control.contains("revalidateRuntimeSettings()"))
        assertTrue(control.contains("runtimeStateUncertain = true"))
        assertTrue(control.split("rejectUnavailableSettingMutation()").size - 1 == 5)
        assertFalse(control.contains("networkStatsManager.getNetworkStats()"))
        assertFalse(network.contains("ServiceLifecycleController.getStatus()"))
    }

    @Test
    fun controlPolling_isScreenLifecycleBoundAndClearsStaleRunningStateOnFailure() {
        val control = source("viewmodel/ControlViewModel.kt")
        val screen = source("ui/screen/ControlScreen.kt")

        assertTrue(screen.contains("LifecycleStartEffect(activeViewModel)"))
        assertTrue(screen.contains("activeViewModel?.onScreenStarted()"))
        assertTrue(screen.contains("activeViewModel?.onScreenStopped()"))
        assertTrue(control.contains("if (screenStarted) return"))
        assertTrue(control.contains("pollingJob?.cancel()"))
        assertTrue(control.contains("isRunning = false"))
        assertTrue(control.contains("iptablesActive = false"))
        assertTrue(control.contains("private suspend fun refreshStatusAfterUpdate()"))
        assertTrue(control.contains("if (screenStarted) pollStatusOnce()"))
        assertFalse(control.contains("delay(1000)"))
    }

    @Test
    fun controlPreferencesRead_doesNotBlockTheMainDispatcher() {
        val control = source("viewmodel/ControlViewModel.kt")
        val ioBoundary = control.indexOf("val showQuicBanner = withContext(Dispatchers.IO)")
        val preferenceRead = control.indexOf(
            "prefs.getBoolean(\"quic_banner_dismissed\", false)",
            startIndex = ioBoundary,
        )
        val statePublication = control.indexOf("showQuicBanner = showQuicBanner", preferenceRead)

        assertTrue(ioBoundary >= 0)
        assertTrue(preferenceRead > ioBoundary)
        assertTrue(statePublication > preferenceRead)
        assertFalse(control.contains("showQuicBanner = !prefs.getBoolean"))
        assertTrue(control.contains("prefs.edit().putBoolean(\"quic_banner_dismissed\", true).apply()"))
    }

    @Test
    fun logsPollingAndReads_areScreenLifecycleBound() {
        val logs = source("viewmodel/LogsViewModel.kt")
        val screen = source("ui/screen/LogsScreen.kt")

        assertTrue(screen.contains("LifecycleStartEffect(activeViewModel)"))
        assertTrue(screen.contains("activeViewModel?.onScreenStarted()"))
        assertTrue(screen.contains("activeViewModel?.onScreenStopped()"))
        assertTrue(logs.contains("if (!screenStarted) return"))
        assertTrue(logs.contains("commandLoadJob?.cancel()"))
        assertTrue(logs.contains("outputLoadJob?.cancel()"))
        assertTrue(logs.split("if (!screenStarted) return").size - 1 >= 4)
        assertTrue(logs.contains("commandLoadGeneration++"))
        assertTrue(logs.contains("outputLoadGeneration++"))
        assertTrue(logs.contains("if (screenStarted)"))
        assertTrue(logs.contains("logs = \"\""))
        assertTrue(logs.contains("fetchCmdlineSafely()"))
        assertTrue(logs.contains("fetchLogsSafely(tab)"))
        assertTrue(logs.contains("internal fun prepareShare(tab: LogTab, text: String)"))
        assertTrue(logs.contains("LogClearOutcome.BLOCKED -> R.string.logs_clear_blocked"))
        assertTrue(logs.contains("text.take(MAX_LOG_FILTER_CHARS)"))
        assertTrue(logs.contains("catch (_: Exception)"))
        assertTrue(screen.contains("state.outputLoadState == LogsLoadState.READY"))
        assertTrue(screen.contains("activeViewModel?.prepareShare(state.currentTab, state.logs)"))
        assertFalse(screen.contains("redactedBoundedLogShareText("))
    }

    @Test
    fun hostEditors_requireFreshBaselinesAndRejectChangedSources() {
        val hosts = source("viewmodel/HostsEditorViewModel.kt")
        val hostlist = source("viewmodel/HostlistContentViewModel.kt")
        val dns = source("viewmodel/DnsManagerViewModel.kt")
        val repository = source("data/HostlistRepository.kt")
        val hostlistUiState = hostlist
            .substringAfter("data class HostlistContentUiState(")
            .substringBefore(") {")

        assertTrue(hosts.contains("val hasAuthoritativeBaseline: Boolean = false"))
        assertTrue(hosts.contains("if (current != baselineContent)"))
        assertTrue(hosts.contains("writeIfUnchanged(snapshot, current, normalized)"))
        assertTrue(hosts.contains("removeIfUnchanged(snapshot, current)"))
        assertTrue(hostlist.contains("val hasAuthoritativeEditorBaseline: Boolean = false"))
        assertFalse(hostlistUiState.contains("filePath"))
        assertTrue(hostlist.contains("private val filePath: String"))
        assertTrue(hostlist.contains("writeIfUnchangedWithRollback"))
        assertTrue(hostlist.contains("editorBaseline = saved.persistedContent"))
        assertFalse(hostlist.contains("editorBaseline = canonicalContent"))
        assertTrue(hostlist.contains("editorLoadJob?.isActive == true"))
        assertTrue(hostlist.contains("if (generation != loadGeneration) return@launch"))
        assertTrue(hostlist.contains("loadSearchPage(sanitizedQuery)"))
        assertTrue(repository.contains("snapshot.content != expectedContent"))
        assertTrue(repository.contains("Written(val persistedContent: String)"))
        assertTrue(dns.contains("clearSelectionOnSuccess = true"))
        assertTrue(dns.contains("hostsData = if (stateUncertain) null"))
        assertTrue(dns.contains("loadError = if (stateUncertain)"))
        assertTrue(dns.contains("HostsOverlayMutationOutcome.SourceChanged"))
        assertTrue(dns.contains("R.string.dns_hosts_changed"))
    }

    @Test
    fun hostsOverlayMutations_recheckTheExactSourceAndVerifyPostState() {
        val repository = source("data/HostsOverlayRepository.kt")
        val conditionalWrite = repository.indexOf("internal fun writeIfUnchanged(")
        val writeSnapshot = repository.indexOf("val liveOverlay = snapshotOverlay()", conditionalWrite)
        val writeOverlayCompare = repository.indexOf("if (liveOverlay != expectedOverlay)", writeSnapshot)
        val writeEffectiveCompare = repository.indexOf("if (liveEffective != expectedEffectiveContent)", writeOverlayCompare)
        val writeAttempt = repository.indexOf("val written = try {", writeEffectiveCompare)
        val write = repository.indexOf("write(content, delimiterPrefix)", writeAttempt)
        val writeFailure = repository.indexOf("if (!written)", write)
        val writePostState = repository.indexOf("val persisted = try {", writeFailure)
        val conditionalRemove = repository.indexOf("internal fun removeIfUnchanged(")
        val removeSnapshot = repository.indexOf("val liveOverlay = snapshotOverlay()", conditionalRemove)
        val removeOverlayCompare = repository.indexOf("if (liveOverlay != expectedOverlay)", removeSnapshot)
        val removeEffectiveCompare = repository.indexOf("if (liveEffective != expectedEffectiveContent)", removeOverlayCompare)
        val removeAttempt = repository.indexOf("val removed = try {", removeEffectiveCompare)
        val remove = repository.indexOf("remove()", removeAttempt)
        val removeFailure = repository.indexOf("if (!removed)", remove)
        val removePostState = repository.indexOf(
            "if (overlayAfterRemove != HostsOverlaySnapshot.Missing)",
            removeFailure,
        )

        assertTrue(conditionalWrite >= 0 && writeSnapshot > conditionalWrite)
        assertTrue(writeOverlayCompare > writeSnapshot && writeEffectiveCompare > writeOverlayCompare)
        assertTrue(writeAttempt > writeEffectiveCompare && write > writeAttempt)
        assertTrue(writeFailure > write && writePostState > writeFailure)
        assertTrue(conditionalRemove > writePostState && removeSnapshot > conditionalRemove)
        assertTrue(removeOverlayCompare > removeSnapshot && removeEffectiveCompare > removeOverlayCompare)
        assertTrue(removeAttempt > removeEffectiveCompare && remove > removeAttempt)
        assertTrue(removeFailure > remove && removePostState > removeFailure)

        val hostlists = source("data/HostlistRepository.kt")
        val conditionalHostlistWrite = hostlists.indexOf("internal fun writeIfUnchangedWithRollback(")
        val hostlistWrite = hostlists.indexOf("val written = writeOrFalse", conditionalHostlistWrite)
        val guardedPostRead = hostlists.indexOf("readForEditingOrNull(path)", hostlistWrite)
        val hostlistRollback = hostlists.indexOf("restoreOrFalse(path, snapshot)", guardedPostRead)
        assertTrue(hostlistWrite > conditionalHostlistWrite)
        assertTrue(guardedPostRead > hostlistWrite && hostlistRollback > guardedPostRead)
    }

    @Test
    fun destructiveTransitions_requireViewModelOwnedAcknowledgement() {
        val hosts = source("viewmodel/HostsEditorViewModel.kt")
        val hostlist = source("viewmodel/HostlistContentViewModel.kt")
        val presets = source("viewmodel/PresetsViewModel.kt")
        val hostsScreen = source("ui/screen/HostsEditorScreen.kt")
        val hostlistScreen = source("ui/screen/HostlistContentScreen.kt")
        val presetsScreen = source("ui/screen/PresetsScreen.kt")
        val presetDialog = source("ui/components/PresetEditorDialog.kt")
        val config = source("viewmodel/ConfigEditorViewModel.kt")
        val configScreen = source("ui/screen/ConfigEditorScreen.kt")
        val dns = source("viewmodel/DnsManagerViewModel.kt")
        val dnsScreen = source("ui/screen/DnsManagerScreen.kt")
        val logs = source("viewmodel/LogsViewModel.kt")
        val logsScreen = source("ui/screen/LogsScreen.kt")

        assertTrue(config.contains("loadCommandLine(discardUnsavedChanges: Boolean = false)"))
        assertTrue(config.contains("hasUnsavedChanges && !discardUnsavedChanges"))
        assertTrue(hosts.contains("if (!state.actionsEnabled || !state.hasUnsavedChanges) return"))
        assertTrue(hosts.contains("loadHosts(discardUnsavedChanges: Boolean = false)"))
        assertTrue(hosts.contains("fun resetHostsOverlay("))
        assertTrue(hosts.contains("confirmed: Boolean = false"))
        assertTrue(hosts.contains("if (!confirmed ||"))
        assertTrue(hosts.contains("(state.hasUnsavedChanges && !discardUnsavedChanges)"))
        assertTrue(hostlist.contains("exitEditMode(discardUnsavedChanges: Boolean = false)"))
        assertTrue(hostlist.contains("(state.hasUnsavedChanges && !discardUnsavedChanges)"))
        assertTrue(presets.contains("closePresetEditor(discardUnsavedChanges: Boolean = false)"))
        assertTrue(presets.contains("editor.hasUnsavedChanges && !discardUnsavedChanges"))
        assertTrue(presets.split("if (_uiState.value.editingPreset != null) return").size - 1 == 1)
        assertTrue(presets.contains("if (state.editingPreset != null ||"))
        assertTrue(dns.contains("resetDns(confirmed: Boolean = false)"))
        assertTrue(dns.contains("if (!confirmed || !_uiState.value.canEditSelection) return"))
        assertTrue(logs.contains("clearLogs(expectedTab: LogTab, confirmed: Boolean = false)"))
        assertTrue(logs.contains("if (_uiState.value.currentTab != expectedTab) return"))
        assertTrue(logs.contains("if (!confirmed || !screenStarted) return"))

        assertTrue(configScreen.contains("loadCommandLine(discardUnsavedChanges = true)"))
        assertTrue(hostsScreen.contains("R.string.hosts_reset_body_unsaved"))
        assertTrue(hostsScreen.contains("loadHosts(discardUnsavedChanges = true)"))
        assertTrue(hostsScreen.contains("confirmed = true"))
        assertTrue(hostsScreen.contains("discardUnsavedChanges = hasUnsavedChanges"))
        assertTrue(hostlistScreen.contains("exitEditMode(discardUnsavedChanges = true)"))
        assertTrue(presetDialog.contains("onDismiss(true)"))
        assertTrue(presetsScreen.contains("closePresetEditor(discardUnsavedChanges)"))
        assertTrue(dnsScreen.contains("resetDns(confirmed = true)"))
        assertTrue(logsScreen.contains("expectedTab = state.currentTab"))
        assertTrue(logsScreen.contains("confirmed = true"))
    }

    @Test
    fun hostlistCatalog_clearsStaleEntriesAndDisablesMutationsAfterReadFailure() {
        val hostlists = source("viewmodel/HostlistsViewModel.kt")
        val repository = source("data/HostlistRepository.kt")

        assertTrue(hostlists.contains("val hasAuthoritativeCatalog: Boolean = false"))
        assertTrue(hostlists.contains("hostlists = emptyList()"))
        assertTrue(hostlists.contains("hasAuthoritativeCatalog = false"))
        assertTrue(hostlists.contains("canReloadCatalog && hasAuthoritativeCatalog && loadError == null"))
        assertFalse(repository.contains("awk 'END { print NR + 0 }'"))
        assertFalse(repository.contains("sed -n"))
        assertTrue(repository.split("normalizeHostlistDataLineAwk").size - 1 >= 4)
        assertTrue(repository.contains("isValidWritableContent(path, content)"))
    }

    @Test
    fun controlAndStrategies_useStateOwnedMessagesInsteadOfLossySharedFlows() {
        val control = source("viewmodel/ControlViewModel.kt")
        val strategies = source("viewmodel/StrategiesViewModel.kt")
        val controlScreen = source("ui/screen/ControlScreen.kt")
        val strategiesScreen = source("ui/screen/StrategiesScreen.kt")

        listOf(control, strategies).forEach { viewModel ->
            assertTrue(viewModel.contains("val message: UiText? = null"))
            assertTrue(viewModel.contains("fun clearMessage()"))
            assertTrue(viewModel.contains("private fun publishMessage(message: UiText)"))
            assertFalse(viewModel.contains("MutableSharedFlow"))
        }
        assertTrue(controlScreen.contains("AppSnackbarEffect(state.message"))
        assertTrue(strategiesScreen.contains("AppSnackbarEffect(state.message"))
        assertFalse(controlScreen.contains("events.collect"))
        assertFalse(strategiesScreen.contains("snackbar.collect"))
        assertTrue(strategies.contains("private fun invalidatePickerLoad()"))
        assertTrue(strategies.split("invalidatePickerLoad()").size - 1 >= 3)
    }

    @Test
    fun strategies_doNotMutateOrOpenPickersFromFailedOrLoadingState() {
        val strategies = source("viewmodel/StrategiesViewModel.kt")
        val strategiesScreen = source("ui/screen/StrategiesScreen.kt")

        assertTrue(strategies.contains("currentState.loadError != null"))
        assertTrue(strategies.contains("category?.type != categoryType"))
        assertTrue(strategiesScreen.contains("val settingsEnabled = !state.isLoading && state.loadError == null"))
        assertTrue(strategiesScreen.contains("enabled = settingsEnabled"))
        assertTrue(strategiesScreen.contains("showPacketPicker && settingsEnabled"))
        assertTrue(strategiesScreen.contains("showDebugPicker && settingsEnabled"))
        assertTrue(strategiesScreen.contains("it.take(MAX_STRATEGY_SEARCH_CHARS)"))
    }

    @Test
    fun strategies_rejectUnsupportedFilterChangesAndNoOpRestarts() {
        val strategies = source("viewmodel/StrategiesViewModel.kt")

        assertTrue(strategies.contains("val category = currentState.categories.firstOrNull"))
        assertTrue(strategies.contains("currentState.pickerCategoryKey == categoryKey"))
        assertTrue(strategies.contains("currentState.pickerItems.any { it.id == strategyId }"))
        assertTrue(strategies.contains("!pickerContainsStrategy ||"))
        assertTrue(strategies.contains("!category.canSwitchFilter ||"))
        assertTrue(strategies.contains("val targetFilterMode = newFilterMode ?: category.filterMode"))
        assertTrue(strategies.contains("category.strategy == strategyId && category.filterMode == targetFilterMode"))
        assertTrue(strategies.contains("currentState.pktCount == packetCount.toString()"))
        assertTrue(strategies.contains("currentState.debugMode == value"))
        assertTrue(strategies.contains("currentState.pickerCategoryKey != categoryKey"))
        assertTrue(strategies.contains("pickerIds != baseline.displayIds"))
        assertTrue(strategies.contains("!hasExactStrategyOrderMembership(currentIds, baseline.catalogIds)"))
        assertTrue(strategies.contains("liveOrder == baseline.runtimeOrder"))
        assertTrue(strategies.contains("expectedOrder = baseline.runtimeOrder"))
    }

    @Test
    fun strategyOrderSave_usesTheCurrentImmutableOrderSnapshot() {
        val strategies = source("viewmodel/StrategiesViewModel.kt")

        assertTrue(strategies.contains("val pendingSnapshot = currentState.pendingOrders + (categoryKey to normalizedIds)"))
        assertTrue(strategies.contains("val validated = normalizeStrategyOrder(normalizedIds, liveIds)"))
        assertFalse(strategies.contains("val pending = _uiState.value.pendingOrders[categoryKey].orEmpty()"))
    }

    @Test
    fun configEditor_restoresWrittenCommandWhenRuntimeCommitIsAmbiguous() {
        val editor = source("viewmodel/ConfigEditorViewModel.kt")
        val rollbackCatch = editor.indexOf("} catch (_: RuntimeConfigRollbackException) {")
        val restore = editor.indexOf(
            "restoreCommandLineOrFalse(expectedCommandFile, commandSnapshot)",
            startIndex = rollbackCatch,
        )

        assertTrue(rollbackCatch >= 0)
        assertTrue(restore > rollbackCatch)
        assertTrue(editor.contains("val hasAuthoritativeBinding: Boolean = false"))
        assertTrue(editor.contains("baselineManualSnapshot"))
        assertTrue(editor.contains("commandLineRepository.readBinding(expectedCommandFile)"))
        assertTrue(editor.contains("ConfigSaveOutcome.SourceChanged"))
        assertTrue(editor.contains("canonicalProtectedText(persistedSnapshot.content)"))
        assertTrue(editor.contains("commandLineRepository.isContentSizeAllowed(text)"))
        assertTrue(editor.contains("validateCommandLineOrFailed(expectedCommandFile)"))
        assertTrue(editor.contains("snapshotCommandLineOrNull(expectedCommandFile)"))
        assertTrue(editor.contains("private fun restoreCommandLineOrFalse("))
        assertTrue(editor.contains("ConfigSaveOutcome.Invalid"))
        assertTrue(editor.contains("catch (failure: Exception)"))
        assertTrue(source("data/CommandLineRepository.kt").contains("val manualSnapshot: CommandLineSnapshot"))
        assertTrue(source("data/CommandLineRepository.kt").contains("--validate-cmdline-machine"))
        assertTrue(source("viewmodel/HostsEditorViewModel.kt").contains("hostsRepository.isContentSizeAllowed(normalized)"))
        assertTrue(source("viewmodel/HostsEditorViewModel.kt").contains("hostsRepository.isValidContent(normalized)"))
        assertTrue(source("viewmodel/HostlistContentViewModel.kt").contains("hostlistRepository.isEditableContentSizeAllowed(content)"))
        assertTrue(source("viewmodel/DnsManagerViewModel.kt").contains("hostsRepository.isContentSizeAllowed(merged)"))
    }

    @Test
    fun configEditor_rejectsCleanWritesAndModeSwitchWithoutRestart() {
        val editor = source("viewmodel/ConfigEditorViewModel.kt")
        val screen = source("ui/screen/ConfigEditorScreen.kt")

        assertTrue(editor.contains("if (forceCmdline && !restart) return"))
        assertTrue(editor.contains("if (!restart && !state.hasUnsavedChanges) return"))
        assertTrue(editor.contains("it.result == ConfigEditorResult.EMPTY"))
        assertTrue(editor.contains("it.result == ConfigEditorResult.INVALID_COMMAND"))
        assertTrue(screen.contains("state.hasUnsavedChanges,"))
        assertTrue(screen.contains("saveAndRestartEnabled = state.actionsEnabled && state.hasAuthoritativeBinding"))
        assertTrue(screen.contains("enabled = saveAndRestartEnabled"))
    }

    @Test
    fun presets_rejectActionsWithoutAnAuthoritativeCatalog() {
        val presets = source("viewmodel/PresetsViewModel.kt")
        val screen = source("ui/screen/PresetsScreen.kt")
        val repository = source("data/PresetRepository.kt")
        val dialog = source("ui/components/PresetEditorDialog.kt")

        assertTrue(presets.contains("val hasAuthoritativeCatalog: Boolean = false"))
        assertTrue(presets.contains("!state.hasAuthoritativeCatalog"))
        assertTrue(presets.contains("val hasAuthoritativeBaseline: Boolean = false"))
        assertTrue(presets.contains("expectedContent = editor.baselineContent"))
        assertTrue(screen.contains("val settingsEnabled = !state.isLoading && state.hasAuthoritativeCatalog"))
        assertTrue(screen.contains("settingsEnabled && editor.hasAuthoritativeBaseline"))
        assertTrue(repository.contains("if (!sourceMatches) return@safelyMutate PresetMutationOutcome.SourceChanged"))
        assertTrue(repository.contains("cleanupCandidate(candidate, PresetMutationOutcome.IoFailed)"))
        assertTrue(repository.contains("snapshotFileOrNull(fileName)"))
        assertTrue(repository.contains("restoreFileOrFalse(fileName, oldFile)"))
        assertTrue(repository.contains("writeConfigOrFalse(requireNotNull(oldConfig))"))
        assertTrue(dialog.contains("dismissEnabled: Boolean = true"))
        assertTrue(presets.contains("state.activeMode in ACTIVE_PRESET_MODES"))
        assertTrue(presets.contains("state.activePresetFile == fileName"))
        assertTrue(presets.contains("state.activeMode == CATEGORY_MODE"))
    }

    @Test
    fun restoredDestinations_revalidateAuthoritativeStateOnCompositionEntry() {
        val entryBoundaries = listOf(
            "viewmodel/ConfigEditorViewModel.kt" to "ui/screen/ConfigEditorScreen.kt",
            "viewmodel/HostsEditorViewModel.kt" to "ui/screen/HostsEditorScreen.kt",
            "viewmodel/DnsManagerViewModel.kt" to "ui/screen/DnsManagerScreen.kt",
            "viewmodel/HostlistsViewModel.kt" to "ui/screen/HostlistsScreen.kt",
            "viewmodel/StrategiesViewModel.kt" to "ui/screen/StrategiesScreen.kt",
            "viewmodel/HostlistContentViewModel.kt" to "ui/screen/HostlistContentScreen.kt",
            "viewmodel/PresetsViewModel.kt" to "ui/screen/PresetsScreen.kt",
        )

        entryBoundaries.forEach { (viewModelPath, screenPath) ->
            val viewModel = source(viewModelPath)
            assertTrue(viewModel.contains("fun onScreenEntered()"))
            assertTrue(viewModel.contains("fun onScreenStopped()"))
            val screen = source(screenPath)
            assertTrue(screen.contains("LifecycleStartEffect(activeViewModel)"))
            assertFalse(screen.contains("LaunchedEffect(activeViewModel)"))
            assertTrue(screen.contains("activeViewModel?.onScreenEntered()"))
            assertTrue(screen.contains("onStopOrDispose { activeViewModel?.onScreenStopped() }"))
        }

        val queuedRevalidationViewModels = entryBoundaries.map { it.first }
        queuedRevalidationViewModels.forEach { viewModelPath ->
            val viewModel = source(viewModelPath)
            assertTrue(viewModel.contains("private var refreshAfterOperation = false"))
            assertTrue(viewModel.contains("refreshAfterOperation = true"))
            assertTrue(viewModel.contains("private fun runPendingRefresh()"))
            val stopped = viewModel
                .substringAfter("fun onScreenStopped()")
                .substringBefore("\n    }")
            assertTrue(stopped.contains("refreshAfterOperation = false"))
            assertTrue(viewModel.windowed("runPendingRefresh()".length)
                .count { it == "runPendingRefresh()" } >= 2)
        }

        listOf(
            "viewmodel/DnsManagerViewModel.kt",
            "viewmodel/HostlistsViewModel.kt",
            "viewmodel/StrategiesViewModel.kt",
        ).forEach { viewModelPath ->
            val viewModel = source(viewModelPath)
            assertTrue(viewModel.contains("private var hasEnteredScreen = false"))
            assertTrue(viewModel.contains("val firstEntry = !hasEnteredScreen"))
            assertTrue(viewModel.contains("if (firstEntry) return"))
        }

        listOf(
            "viewmodel/ConfigEditorViewModel.kt",
            "viewmodel/HostsEditorViewModel.kt",
        ).forEach { viewModelPath ->
            val viewModel = source(viewModelPath)
            assertTrue(viewModel.contains("if (firstEntry && !restoredEditorState) return"))
            assertTrue(viewModel.contains("preserveUnsavedDraft = true"))
        }

        val hostlistContent = source("viewmodel/HostlistContentViewModel.kt")
        assertTrue(hostlistContent.contains("if (firstEntry && !restoredEditing) return"))
        assertTrue(hostlistContent.contains("revalidateCurrentMode(state)"))
        assertTrue(hostlistContent.contains("searchJob?.cancel()"))

        val presets = source("viewmodel/PresetsViewModel.kt")
        assertTrue(presets.contains("if (operationInProgress.get())"))
        assertTrue(presets.contains("operationInProgress.set(false)"))

        assertTrue(source("ui/screen/StrategiesScreen.kt").contains("pickingCategoryKey = null"))
    }

    @Test
    fun composeUi_doesNotLoadDataThroughRepositoriesOrRootBoundaries() {
        val directory = repositoryDirectory("android-app/app/src/main/java/com/zapret2/app/ui")
        val sources = directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val forbiddenCalls = listOf(
            "StrategyRepository.getStrategyDetails(",
            "StrategyRepository.getSavedOrder(",
            "RuntimeConfigStore.",
            "ServiceLifecycleController.executeRoot(",
            "ServiceLifecycleController.getStatus(",
            "ServiceLifecycleController.start(",
            "ServiceLifecycleController.stop(",
            "ServiceLifecycleController.restart(",
            "Shell.cmd(",
            "RootFileIo.",
        )
        sources.forEach { source ->
            val text = source.readText()
            forbiddenCalls.forEach { token ->
                assertFalse("${source.name} performs data work from Compose through $token", text.contains(token))
            }
        }
    }

    private fun source(relative: String): String = repositoryDirectory(
        "android-app/app/src/main/java/com/zapret2/app/$relative",
    ).readText()

    private fun repositoryDirectory(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository path: $relativePath")
    }
}
