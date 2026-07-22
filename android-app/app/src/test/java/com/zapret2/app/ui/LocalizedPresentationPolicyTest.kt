package com.zapret2.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class LocalizedPresentationPolicyTest {

    @Test
    fun completeCatalogs_preserveTranslationAndFormatContracts() {
        val english = catalogIn("src/main/res/values/strings.xml")
        val russian = catalogIn("src/main/res/values-ru/strings.xml")
        val translatableStrings = english.strings.filterValues(LocalizedString::translatable)
        val translatablePlurals = english.plurals.filterValues(LocalizedPlural::translatable)

        assertEquals(494, translatableStrings.size)
        assertEquals(14, translatablePlurals.size)
        assertEquals(translatableStrings.keys, russian.strings.keys)
        translatableStrings.forEach { (name, base) ->
            val localized = russian.strings.getValue(name)
            assertTrue("Russian string $name must remain translatable", localized.translatable)
            assertEquals("formatted mismatch for $name", base.formatted, localized.formatted)
            if (base.formatted) {
                assertEquals(
                    "format arguments mismatch for $name",
                    formatSignature(base.text),
                    formatSignature(localized.text),
                )
            }
        }

        assertEquals(translatablePlurals.keys, russian.plurals.keys)
        translatablePlurals.forEach { (name, base) ->
            val localized = russian.plurals.getValue(name)
            assertEquals(setOf("one", "other"), base.quantities.keys)
            assertTrue(
                "Russian plural $name must define one/few/many/other",
                localized.quantities.keys.containsAll(setOf("one", "few", "many", "other")),
            )
            val baseSignatures = base.quantities.values.map(::formatSignature).toSet()
            val localizedSignatures = localized.quantities.values.map(::formatSignature).toSet()
            assertEquals("Base plural $name has inconsistent arguments", 1, baseSignatures.size)
            assertEquals("Russian plural $name has inconsistent arguments", 1, localizedSignatures.size)
            assertEquals("Plural arguments mismatch for $name", baseSignatures, localizedSignatures)
        }
    }

    @Test
    fun everyDefaultStringAndPluralHasAProductionConsumer() {
        val catalog = catalogIn("src/main/res/values/strings.xml")
        val productionText = File("src/main").walkTopDown()
            .filter { file ->
                file.isFile && file.extension in setOf("kt", "xml") &&
                    file.name != "DestinationScreenPreviews.kt" && file.name != "strings.xml"
            }
            .joinToString("\n") { it.readText() }
        val missing = buildList<String> {
            catalog.strings.keys.filterTo(this) { name ->
                !Regex("(?:R\\.string\\.|@string/)${Regex.escape(name)}(?![A-Za-z0-9_])")
                    .containsMatchIn(productionText)
            }
            catalog.plurals.keys.filterTo(this) { name ->
                !Regex("(?:R\\.plurals\\.|@plurals/)${Regex.escape(name)}(?![A-Za-z0-9_])")
                    .containsMatchIn(productionText)
            }
        }

        assertEquals("Unconsumed default resources", emptyList<String>(), missing)
        assertEquals(521, catalog.strings.size + catalog.plurals.size)
    }

    @Test
    fun productionComposeCopy_usesResourcesOrRuntimeData() {
        val sourceRoot = File("src/main/java/com/zapret2/app")
        val uiFiles = sourceRoot.walkTopDown().filter { file ->
            file.isFile && file.extension == "kt" &&
                (file.name == "MainActivity.kt" || "/ui/" in file.invariantSeparatorsPath) &&
                file.name != "DestinationScreenPreviews.kt"
        }
        val forbidden = listOf(
            Regex("\\bText\\s*\\(\\s*\""),
            Regex("\\btext\\s*=\\s*\""),
            Regex("\\bcontentDescription\\s*=\\s*\""),
            Regex("\\bstateDescription\\s*=\\s*\""),
            Regex("\\bpaneTitle\\s*=\\s*\""),
            Regex("\\bonClickLabel\\s*=\\s*\""),
            Regex("UiText\\.Dynamic\\s*\\(\\s*\""),
        )
        val violations = uiFiles.flatMap { file ->
            val text = file.readText()
            forbidden.filter { it.containsMatchIn(text) }.map { "${file.name}: ${it.pattern}" }
        }.toList()

        assertTrue("Hardcoded production Compose copy: $violations", violations.isEmpty())
    }

    @Test
    fun viewModelOwnedUiCopy_usesResourcesWhileDynamicTextRemainsRuntimeData() {
        val viewModelRoot = File("src/main/java/com/zapret2/app/viewmodel")
        assertTrue("Missing ViewModel source root: ${viewModelRoot.absolutePath}", viewModelRoot.isDirectory)
        val literalDynamicCopy = Regex("UiText\\.(?:Dynamic|dynamic)\\s*\\(\\s*\"")
        val violations = viewModelRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { literalDynamicCopy.containsMatchIn(it.readText()) }
            .map { it.name }
            .toList()

        assertTrue("Hardcoded ViewModel UiText.Dynamic copy: $violations", violations.isEmpty())
    }

    @Test
    fun localePreviews_useRuntimeResourceCopyForStateMessages() {
        val previews = source("ui/screen/DestinationScreenPreviews.kt")

        assertFalse(
            Regex("(?:loadingText|loadError|message)\\s*=\\s*UiText\\.Dynamic")
                .containsMatchIn(previews),
        )
        assertTrue(
            previews.contains("strategyDisplayName = UiText.Resource(R.string.state_disabled)"),
        )
        assertTrue(previews.contains("R.string.strategies_category_subtitle_no_filter"))
        assertFalse(previews.contains("subtitle = \"UDP - disabled\""))
    }

    @Test
    fun strategyCategorySubtitles_localizeAppOwnedFilterState() {
        val viewModel = source("viewmodel/StrategiesViewModel.kt")
        val screen = source("ui/screen/StrategiesScreen.kt")

        assertTrue(viewModel.contains("val subtitle: UiText"))
        assertTrue(viewModel.contains("R.string.strategies_category_subtitle_target"))
        assertTrue(viewModel.contains("R.string.strategies_category_subtitle_no_filter"))
        assertFalse(viewModel.contains("else -> \"none\""))
        assertTrue(screen.contains("subtitle = category.subtitle.resolve()"))
        assertTrue(screen.contains("category.subtitle.resolve()"))
    }

    @Test
    fun updateCheckFailures_areTypedBeforeTheyReachLocalizedPresentation() {
        val manager = source("data/UpdateManager.kt")
        val execution = source("data/UpdateExecution.kt")
        val control = source("viewmodel/ControlViewModel.kt")
        val dialog = source("ui/components/UpdateDialog.kt")
        val uiText = source("ui/UiText.kt")

        assertTrue(manager.contains("data class Error(val reason: UpdateCheckFailure)"))
        assertFalse(Regex("UpdateResult\\.Error\\s*\\(\\s*\"").containsMatchIn(manager))
        assertFalse(manager.contains("No changelog available"))
        assertTrue(control.contains("publishMessage(result.reason.toUiText())"))
        assertFalse(control.contains("control_update_check_failed, result.message"))
        assertTrue(dialog.contains("R.string.update_changelog_unavailable"))
        assertTrue(execution.contains("val failure: UpdateFailure"))
        assertTrue(execution.contains("val reason: ArtifactValidationReason"))
        assertFalse(execution.contains("data class Validation(val diagnostic: String)"))
        assertTrue(execution.contains("data object ModuleRejected"))
        assertFalse(execution.contains("data class ModuleRejected(val diagnostic: String)"))
        assertFalse(execution.contains("Module deferred:"))
        assertFalse(execution.contains("APK deferred:"))
        assertFalse(Regex("DownloadResult\\.Error\\s*\\(\\s*\"").containsMatchIn(manager))
        assertFalse(
            Regex("(?:Module|Apk)ArtifactOutcome\\.(?:Failed|Deferred)\\s*\\(\\s*\"")
                .containsMatchIn(manager),
        )
        assertTrue(control.contains("details = terminal.failure.toUiText()"))
        assertTrue(control.contains("private fun ArtifactValidationReason.toUiText()"))
        assertFalse(control.contains("is UpdateFailure.Validation -> diagnostic"))
        assertTrue(control.contains("CONTROL_ERROR_DETAIL_WRAPPER_FALLBACKS"))
        assertTrue(control.contains("persistControlErrorDetails"))
        assertTrue(control.contains("control_update_module_recovery_required_details"))
        assertFalse(control.contains("toSafeUpdateDiagnostic("))
        assertTrue(manager.contains("): ArtifactValidationReason?"))
        assertFalse(manager.contains("Downloaded APK validation failed"))
        assertFalse(manager.contains("Downloaded APK signature does not match"))
        assertFalse(manager.contains("Archive contains too many entries"))
        assertFalse(manager.contains("Installed module is already current or newer"))
        assertTrue(uiText.contains("argument.resolveComposable(depth + 1)"))
    }

    @Test
    fun ownedPresentationResources_haveEnglishRussianParityAndRealTranslations() {
        val english = stringsIn("src/main/res/values/strings.xml")
        val russian = stringsIn("src/main/res/values-ru/strings.xml")
        val owned = setOf(
            "state_disabled",
            "strategy_no_bypass",
            "network_type_wifi",
            "network_type_mobile",
            "network_type_ethernet",
            "network_type_vpn",
            "network_type_none",
        )

        assertEquals(owned, english.keys.intersect(owned))
        assertEquals(owned, russian.keys.intersect(owned))
        assertNotEquals(english.getValue("state_disabled"), russian.getValue("state_disabled"))
        assertNotEquals(english.getValue("strategy_no_bypass"), russian.getValue("strategy_no_bypass"))
        assertNotEquals(english.getValue("network_type_mobile"), russian.getValue("network_type_mobile"))
        assertNotEquals(english.getValue("network_type_none"), russian.getValue("network_type_none"))
    }

    @Test
    fun typedSentinels_doNotInjectStableEnglishIntoLocalizedUi() {
        val repository = source("data/StrategyRepository.kt")
        val networkManager = source("data/NetworkStatsManager.kt")
        val controlViewModel = source("viewmodel/ControlViewModel.kt")

        assertFalse(repository.contains("\"Disabled\""))
        assertFalse(repository.contains("\"No DPI bypass\""))
        assertFalse(networkManager.contains("getNetworkTypeString"))
        assertTrue(controlViewModel.contains("UiText.Resource(netStats.networkType.labelRes)"))
    }

    private fun source(relativePath: String): String =
        File("src/main/java/com/zapret2/app/$relativePath").also {
            assertTrue("Missing source: ${it.absolutePath}", it.isFile)
        }.readText()

    private fun stringsIn(relativePath: String): Map<String, String> {
        val file = File(relativePath)
        assertTrue("Missing catalog: ${file.absolutePath}", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            repeat(nodes.length) { index ->
                val element = nodes.item(index) as Element
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }

    private fun catalogIn(relativePath: String): LocalizedCatalog {
        val file = File(relativePath)
        assertTrue("Missing catalog: ${file.absolutePath}", file.isFile)
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val strings = linkedMapOf<String, LocalizedString>()
        val plurals = linkedMapOf<String, LocalizedPlural>()

        root.childElements().forEach { element ->
            val name = element.getAttribute("name")
            when (element.tagName) {
                "string" -> check(
                    strings.put(
                        name,
                        LocalizedString(
                            text = element.textContent,
                            translatable = element.booleanAttribute("translatable", default = true),
                            formatted = element.booleanAttribute("formatted", default = true),
                        ),
                    ) == null,
                ) { "Duplicate string resource: $name" }
                "plurals" -> {
                    val quantities = linkedMapOf<String, String>()
                    element.childElements("item").forEach { item ->
                        val quantity = item.getAttribute("quantity")
                        check(quantities.put(quantity, item.textContent) == null) {
                            "Duplicate plural quantity: $name/$quantity"
                        }
                    }
                    check(
                        plurals.put(
                            name,
                            LocalizedPlural(
                                quantities = quantities,
                                translatable = element.booleanAttribute("translatable", default = true),
                            ),
                        ) == null,
                    ) { "Duplicate plural resource: $name" }
                }
            }
        }
        return LocalizedCatalog(strings, plurals)
    }

    private fun formatSignature(text: String): List<String> {
        val arguments = FORMAT_ARGUMENT.findAll(text)
            .filterNot { it.value == "%%" }
            .toList()
        check(arguments.size <= 1 || arguments.all { it.groupValues[1].isNotEmpty() }) {
            "Multiple format arguments must use explicit positions: $text"
        }
        var implicitPosition = 0
        return arguments.map { argument ->
            val position = argument.groupValues[1].ifEmpty { (++implicitPosition).toString() }
            "$position:${argument.value.last().lowercaseChar()}"
        }.sorted()
    }

    private fun Element.childElements(tagName: String? = null): List<Element> = buildList {
        repeat(childNodes.length) { index ->
            val child = childNodes.item(index) as? Element ?: return@repeat
            if (tagName == null || child.tagName == tagName) add(child)
        }
    }

    private fun Element.booleanAttribute(name: String, default: Boolean): Boolean =
        if (hasAttribute(name)) getAttribute(name).toBooleanStrict() else default

    private data class LocalizedCatalog(
        val strings: Map<String, LocalizedString>,
        val plurals: Map<String, LocalizedPlural>,
    )

    private data class LocalizedString(
        val text: String,
        val translatable: Boolean,
        val formatted: Boolean,
    )

    private data class LocalizedPlural(
        val quantities: Map<String, String>,
        val translatable: Boolean,
    )

    private companion object {
        val FORMAT_ARGUMENT = Regex(
            "%(?:(\\d+)\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[tT]?[a-zA-Z%]",
        )
    }
}
