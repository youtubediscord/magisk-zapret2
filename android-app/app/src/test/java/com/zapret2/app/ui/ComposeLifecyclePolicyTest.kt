package com.zapret2.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeLifecyclePolicyTest {

    @Test
    fun everyStatefulDestination_collectsStateLifecycleAware() {
        val statefulScreens = listOf(
            "ControlScreen.kt",
            "ProfilesScreen.kt",
            "PresetsScreen.kt",
            "HostlistsScreen.kt",
            "HostsEditorScreen.kt",
            "DnsManagerScreen.kt",
            "LogsScreen.kt",
            "HostlistContentScreen.kt",
        )
        statefulScreens.forEach { fileName ->
            val source = productionFile("ui/screen/$fileName").readText()
            assertTrue(
                "$fileName must use lifecycle-aware state collection",
                source.contains("collectAsStateWithLifecycle()"),
            )
            assertFalse(
                "$fileName restored raw collectAsState",
                source.contains(".collectAsState()"),
            )
            assertTrue(
                "$fileName must bind its active work to the destination STARTED lifecycle",
                source.contains("LifecycleStartEffect(activeViewModel)"),
            )
            assertTrue(
                "$fileName must release its active lifecycle ownership on stop",
                source.contains("onStopOrDispose { activeViewModel?.onScreenStopped() }"),
            )
        }

        val productionUi = productionFile("ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse(productionUi.contains(".collectAsState()"))
        assertFalse(productionUi.contains("observeForever"))
    }

    @Test
    fun pollingObserversAndCoroutines_haveCompositionOrViewModelOwnership() {
        val control = productionFile("viewmodel/ControlViewModel.kt").readText()
        val logs = productionFile("viewmodel/LogsViewModel.kt").readText()
        listOf(control, logs).forEach { source ->
            assertTrue(source.contains("while (isActive)"))
            assertTrue(source.contains("pollingJob?.cancel()"))
            assertTrue(source.contains("fun onScreenStopped()"))
        }

        val motion = productionFile("ui/components/MotionPreference.kt").readText()
        assertTrue(motion.contains("mutableStateOf(true)"))
        assertTrue(motion.contains("mutableLongStateOf(0L)"))
        assertTrue(motion.contains("LaunchedEffect(resolver, refreshGeneration)"))
        assertTrue(motion.contains("withContext(Dispatchers.IO)"))
        assertTrue(motion.contains("registerContentObserver("))
        assertTrue(motion.contains("unregisterContentObserver(observer)"))
        assertTrue(motion.contains("DisposableEffect(resolver)"))

        val production = productionFile("").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
        assertFalse(production.contains("GlobalScope"))
        assertFalse(production.contains("MainScope("))
        assertFalse(Regex("""(?<![A-Za-z])CoroutineScope\(""").containsMatchIn(production))
    }

    @Test
    fun longLivedComposeEffects_useCurrentCallbacks() {
        val hostlist = productionFile("ui/screen/HostlistContentScreen.kt").readText()
        assertTrue(hostlist.contains("val currentOnLoadMore by rememberUpdatedState(onLoadMore)"))
        assertTrue(hostlist.contains(".collect { currentOnLoadMore() }"))
        assertTrue(hostlist.contains("LaunchedEffect(state.searchQuery, listState)"))
        assertTrue(hostlist.contains("listState.scrollToItem(0)"))

        val snackbar = productionFile("ui/components/AppSnackbarEffect.kt").readText()
        assertTrue(
            Regex("val currentOnConsumed by rememberUpdatedState\\(onConsumed\\)")
                .findAll(snackbar)
                .count() == 2,
        )
    }

    @Test
    fun suspendMutationAdapters_neverConvertCancellationIntoOrdinaryFailure() {
        val viewModelRunCatching = productionFile("viewmodel").walkTopDown()
            .filter { it.isFile && it.name.endsWith("ViewModel.kt") }
            .filter { it.readText().contains("runCatching") }
            .map { it.name }
            .toList()
        assertTrue(
            "Suspend ViewModel work must use explicit cancellation-transparent catches: $viewModelRunCatching",
            viewModelRunCatching.isEmpty(),
        )

        val profiles = productionFile("viewmodel/ProfilesViewModel.kt").readText()
        assertTrue(
            Regex(
                """catch \(cancelled: CancellationException\)\s*\{\s*throw cancelled\s*}\s*catch \(_: Exception\)""",
            ).containsMatchIn(profiles),
        )
    }

    @Test
    fun saveableTransientUi_isInvalidatedWhenItsAuthorityOrOperationChanges() {
        val hosts = productionFile("ui/screen/HostsEditorScreen.kt").readText()
        assertTrue(hosts.contains("state.hasAuthoritativeBaseline,"))
        assertTrue(hosts.contains("if (state.operation != null || !hasUnsavedChanges)"))
        assertTrue(hosts.contains("if (showResetDialog && state.actionsEnabled)"))

        val hostlist = productionFile("ui/screen/HostlistContentScreen.kt").readText()
        assertTrue(
            hostlist.contains(
                "LaunchedEffect(state.isEditing, state.hasUnsavedChanges, state.isSaving)",
            ),
        )
        assertTrue(hostlist.contains("showDiscardDialog = false"))

        val logs = productionFile("ui/screen/LogsScreen.kt").readText()
        assertTrue(logs.contains("LaunchedEffect(state.isClearing)"))
        assertTrue(logs.contains("if (showClearConfirmation && !state.isClearing)"))
        assertTrue(
            logs.contains(
                "rememberSaveable(state.currentTab) { mutableStateOf(false) }",
            ),
        )

        val presetEditor = productionFile("ui/components/PresetEditorDialog.kt").readText()
        assertTrue(
            presetEditor.contains(
                "rememberSaveable(fileName) { mutableStateOf(false) }",
            ),
        )
        assertTrue(
            presetEditor.contains("LaunchedEffect(dismissEnabled, hasUnsavedChanges)"),
        )
        assertTrue(
            presetEditor.contains(
                "if (showDiscardConfirmation && dismissEnabled && hasUnsavedChanges)",
            ),
        )
    }

    private fun productionFile(relativePath: String): File = repositoryDirectory(
        "android-app/app/src/main/java/com/zapret2/app/$relativePath",
    )

    private fun repositoryDirectory(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.exists()) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository path: $relativePath")
    }
}
