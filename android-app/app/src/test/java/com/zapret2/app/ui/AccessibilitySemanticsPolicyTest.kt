package com.zapret2.app.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilitySemanticsPolicyTest {

    @Test
    fun customInteractiveSurfaces_declareRolesAndSelectionGroups() {
        val sources = uiSources()

        sources.forEach { source ->
            val lines = source.readLines()
            lines.forEachIndexed { index, line ->
                val requiredRole = when {
                    ".clickable(" in line -> "role = Role.Button"
                    ".toggleable(" in line -> "role = Role."
                    ".selectable(" in line -> "role = Role.RadioButton"
                    else -> null
                } ?: return@forEachIndexed

                val semanticWindow = lines
                    .subList(index, minOf(index + 10, lines.size))
                    .joinToString("\n")
                val isBlockingOverlay = source.name == "LoadingOverlay.kt" &&
                    semanticWindow.contains("clearAndSetSemantics")
                assertTrue(
                    "${source.name}:${index + 1} must expose an explicit semantic role",
                    isBlockingOverlay || semanticWindow.contains(requiredRole),
                )
            }

            if (lines.any { ".selectable(" in it }) {
                assertTrue(
                    "${source.name} must expose a selectable group for its radio-button surfaces",
                    lines.any { ".selectableGroup()" in it },
                )
            }
        }
    }

    @Test
    fun rowOwnedSelectionSemantics_areNotDuplicatedByVisualIndicators() {
        uiSources().forEach { source ->
            val lines = source.readLines()
            lines.forEachIndexed { index, line ->
                if (!Regex("\\b(?:Checkbox|Switch|RadioButton)\\s*\\(").containsMatchIn(line)) {
                    return@forEachIndexed
                }

                val controlWindow = lines
                    .subList(index, minOf(index + 10, lines.size))
                    .joinToString("\n")
                val delegatesInteractionToParent =
                    controlWindow.contains("onClick = null") ||
                        controlWindow.contains("onCheckedChange = null")
                if (delegatesInteractionToParent) {
                    assertTrue(
                        "${source.name}:${index + 1} parent-owned selection semantics must be exposed once",
                        controlWindow.contains("Modifier.clearAndSetSemantics { }"),
                    )
                }
            }
        }
    }

    @Test
    fun customInteractiveSurfaces_guaranteeMinimumTouchTargets() {
        uiSources().forEach { source ->
            val lines = source.readLines()
            lines.forEachIndexed { index, line ->
                if (listOf(".clickable(", ".toggleable(", ".selectable(").none { it in line }) {
                    return@forEachIndexed
                }

                val layoutWindow = lines
                    .subList(maxOf(0, index - 10), minOf(index + 8, lines.size))
                    .joinToString("\n")
                val isFullScreenBlockingOverlay = source.name == "LoadingOverlay.kt" &&
                    layoutWindow.contains(".fillMaxSize()") &&
                    layoutWindow.contains("clearAndSetSemantics")
                assertTrue(
                    "${source.name}:${index + 1} must guarantee a 48dp minimum touch target",
                    isFullScreenBlockingOverlay ||
                        layoutWindow.contains(".heightIn(min = SizeTokens.MinimumTouchTarget)"),
                )
            }
        }
    }

    @Test
    fun materialTouchTargetEnforcement_isNeverDisabled() {
        val sourceText = uiSources().joinToString("\n") { it.readText() }

        assertFalse(sourceText.contains("LocalMinimumInteractiveComponentEnforcement"))
        assertFalse(
            Regex("LocalMinimumInteractiveComponentSize\\s+provides")
                .containsMatchIn(sourceText),
        )
    }

    @Test
    fun multiActionGroups_adaptToWidthAndLargeText() {
        val sources = uiSources().associateBy { it.name }
        listOf(
            "ConfigEditorScreen.kt",
            "HostsEditorScreen.kt",
            "DnsManagerScreen.kt",
            "PresetsScreen.kt",
        ).forEach { fileName ->
            val source = requireNotNull(sources[fileName]) { "Missing UI source: $fileName" }.readText()
            assertTrue(
                "$fileName must use the shared adaptive action layout",
                source.contains("AdaptiveActionGroup(stacked = compactActions)") ||
                    source.contains("AdaptiveActionGroup(stacked = vertical)"),
            )
            assertTrue(
                "$fileName must derive compact actions from a reviewed width token",
                source.contains("maxWidth < SizeTokens.CompactActionsBreakpoint"),
            )
        }

        val component = requireNotNull(sources["AdaptiveActionGroup.kt"]) {
            "Missing shared adaptive action layout"
        }.readText()
        assertTrue(component.contains("LocalDensity.current.fontScale"))
        assertTrue(component.contains("AccessibilityTokens.StackGroupsFontScale"))
        assertTrue(component.contains("content(Modifier.fillMaxWidth())"))
        assertTrue(component.contains("content(Modifier.weight(1f))"))

        val control = requireNotNull(sources["ControlScreen.kt"]) {
            "Missing UI source: ControlScreen.kt"
        }.readText()
        assertTrue(control.contains("maxWidth < SizeTokens.CompactActionsBreakpoint"))
        assertTrue(control.contains("AdaptiveEqualWidthGroup(stacked = compactGroups)"))

        val hostlistEditor = requireNotNull(sources["HostlistContentScreen.kt"]) {
            "Missing UI source: HostlistContentScreen.kt"
        }.readText()
        assertTrue(hostlistEditor.contains("AdaptiveEqualWidthGroup("))
        assertTrue(hostlistEditor.contains("stacked = maxWidth < SizeTokens.CompactActionsBreakpoint"))

        val settingRow = requireNotNull(sources["SettingRow.kt"]) {
            "Missing shared setting row"
        }.readText()
        assertTrue(settingRow.contains("widthIn(max = SizeTokens.TrailingValueMax)"))
        assertTrue(settingRow.contains("overflow = TextOverflow.Ellipsis"))

        val presetDialog = requireNotNull(sources["PresetEditorDialog.kt"]) {
            "Missing preset editor dialog"
        }.readText()
        assertTrue(presetDialog.contains("Column(horizontalAlignment = Alignment.End)"))
        assertFalse(
            Regex("confirmButton\\s*=\\s*\\{\\s*Row")
                .containsMatchIn(presetDialog),
        )

        val logs = requireNotNull(sources["LogsScreen.kt"]) {
            "Missing UI source: LogsScreen.kt"
        }.readText()
        assertTrue(logs.contains("PrimaryScrollableTabRow("))
        assertFalse(logs.contains("PrimaryTabRow("))
    }

    @Test
    fun contentRowsAllowWrappingInsteadOfForcingOneLine() {
        uiSources()
            .filterNot { it.name == "LogsScreen.kt" }
            .forEach { source ->
                assertFalse(
                    "${source.name} must not force content labels to one visual line",
                    Regex("maxLines\\s*=\\s*1\\b").containsMatchIn(source.readText()),
                )
            }
    }

    @Test
    fun iconOnlyMaterialControls_keepAccessibleNamesAcrossVisualStates() {
        uiSources().forEach { source ->
            val lines = source.readLines()
            lines.forEachIndexed { index, line ->
                if (!Regex("\\bIcon(?:Toggle)?Button\\s*\\(").containsMatchIn(line)) {
                    return@forEachIndexed
                }

                val indentation = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val endIndex = ((index + 1) until lines.size).firstOrNull { candidateIndex ->
                    val candidate = lines[candidateIndex]
                    candidate.trim() == "}" &&
                        candidate.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0) == indentation
                } ?: minOf(index + 24, lines.lastIndex)
                val controlWindow = lines
                    .subList(index, endIndex + 1)
                    .joinToString("\n")
                val hasNonNullDescription = Regex(
                    "contentDescription\\s*=\\s*(?!null\\b)[^\\r\\n]+",
                ).containsMatchIn(controlWindow)

                assertTrue(
                    "${source.name}:${index + 1} icon-only control must retain an accessible name",
                    hasNonNullDescription,
                )
            }
        }
    }

    @Test
    fun presetCards_exposeApplyAndEditActionsExactlyOnce() {
        val source = uiSources().single { it.name == "PresetsScreen.kt" }.readText()
        val card = source.substringAfter("private fun PresetCard(")
            .substringBefore("private fun PresetLoadErrorState(")

        assertFalse(card.contains(".clickable("))
        assertFalse(card.contains(".semantics { selected = isActive }"))
        assertTrue(card.contains("IconButton(onClick = onEdit, enabled = enabled)"))
        assertTrue(card.contains("FilledTonalButton("))
        assertTrue(card.contains("onClick = onApply"))
        assertTrue(card.contains("R.string.presets_active"))
    }

    @Test
    fun blockingOverlay_exposesPoliteIndeterminateProgress() {
        val overlay = uiSources().single { it.name == "LoadingOverlay.kt" }.readText()

        assertTrue(overlay.contains("clearAndSetSemantics"))
        assertTrue(overlay.contains("liveRegion = LiveRegionMode.Polite"))
        assertTrue(overlay.contains("progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate"))
    }

    @Test
    fun groupedStatusSurfaces_exposeOneNamedStateNode() {
        val control = uiSources().single { it.name == "ControlScreen.kt" }.readText()
        val indicator = uiSources().single { it.name == "StatusIndicator.kt" }.readText()

        assertTrue(control.contains("Modifier.semantics(mergeDescendants = true)"))
        assertTrue(control.contains("exposeState = false"))
        assertTrue(indicator.contains("exposeState: Boolean = true"))
        assertTrue(indicator.contains("if (exposeState)"))
        assertTrue(indicator.contains("Modifier.clearAndSetSemantics { }"))
    }

    @Test
    fun strategyCards_keepSelectionAndReorderPositionOnTheirNamedTargets() {
        val source = uiSources().single { it.name == "StrategiesScreen.kt" }.readText()
        val card = source.substringAfter("private fun StrategyCard(")
            .substringBefore("private fun StrategyArguments(")
        val cardContainer = card.substringBefore("Column(")

        assertFalse(cardContainer.contains(".semantics"))
        assertTrue(card.contains("Modifier.selectable("))
        assertTrue(card.contains("Modifier.semantics(mergeDescendants = true)"))
        assertTrue(card.contains("stateDescription = positionDescription"))
    }

    @Test
    fun visualSectionAndNavigationTitles_areSemanticHeadings() {
        val sectionHeader = uiSources().single { it.name == "SectionHeader.kt" }.readText()
        val appShell = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/MainActivity.kt",
        ).readText()

        assertTrue(sectionHeader.contains(".semantics { heading() }"))
        assertTrue(
            "The screen title, medium overflow title, and navigation group labels must be headings",
            Regex("\\.semantics \\{ heading\\(\\) }").findAll(appShell).count() >= 3,
        )
        assertTrue(
            Regex("private fun DrawerSectionHeader[\\s\\S]*?\\.semantics \\{ heading\\(\\) }")
                .containsMatchIn(appShell),
        )
        assertTrue(
            Regex("nav_more_destinations[\\s\\S]{0,500}\\.semantics \\{ heading\\(\\) }")
                .containsMatchIn(appShell),
        )
    }

    @Test
    fun fullScreenTextInputs_andPresetEditorRespectImeInsets() {
        val sources = uiSources().associateBy { it.name }
        listOf(
            "ConfigEditorScreen.kt",
            "HostsEditorScreen.kt",
            "HostlistContentScreen.kt",
            "LogsScreen.kt",
            "StrategiesScreen.kt",
            "PresetEditorDialog.kt",
        ).forEach { fileName ->
            val source = requireNotNull(sources[fileName]) { "Missing UI source: $fileName" }.readText()
            assertTrue("$fileName must keep text inputs clear of the IME", source.contains(".imePadding()"))
        }

        val presetEditor = requireNotNull(sources["PresetEditorDialog.kt"])
            .readText()
        assertFalse(presetEditor.contains("EditorMinHeight"))
        assertTrue(presetEditor.contains("minLines = 3"))
        assertTrue(presetEditor.contains("maxLines = 10"))
    }

    @Test
    fun dnsResetRequiresExplicitLocalizedConfirmation() {
        val source = uiSources().single { it.name == "DnsManagerScreen.kt" }.readText()
        val dialogStart = source.indexOf("if (showResetConfirmation && selectionEnabled)")
        val mutation = source.indexOf("activeViewModel?.resetDns(confirmed = true)")

        assertTrue(source.contains("var showResetConfirmation by rememberSaveable"))
        assertTrue(source.contains("onClick = { showResetConfirmation = true }"))
        assertTrue(source.contains("R.string.dns_reset_title"))
        assertTrue(source.contains("R.string.dns_reset_body"))
        assertTrue(
            "DNS reset must only occur inside its confirmation dialog",
            dialogStart >= 0 && mutation > dialogStart,
        )
        assertTrue(
            "DNS reset must have one guarded UI mutation call",
            Regex("activeViewModel\\?\\.resetDns\\(confirmed = true\\)")
                .findAll(source)
                .count() == 1,
        )
    }

    private fun uiSources(): List<File> {
        val directory = repositoryDirectory("android-app/app/src/main/java/com/zapret2/app/ui")
        return directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun repositoryDirectory(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isDirectory) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository path: $relativePath")
    }

    private fun repositoryFile(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository file: $relativePath")
    }
}
