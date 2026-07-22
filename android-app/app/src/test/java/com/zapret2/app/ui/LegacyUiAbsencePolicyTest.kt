package com.zapret2.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

class LegacyUiAbsencePolicyTest {

    private val appDir: Path = locateAppDir()
    private val mainDir = appDir.resolve("src/main")

    @Test
    fun productionUiUsesComposeOnlyAndDeletedCompatibilitySymbolsCannotReturn() {
        val scanned = buildList {
            add(mainDir.resolve("java/com/zapret2/app/MainActivity.kt"))
            addAll(kotlinFiles(mainDir.resolve("java/com/zapret2/app/ui")))
            addAll(kotlinFiles(mainDir.resolve("java/com/zapret2/app/viewmodel")))
            add(mainDir.resolve("java/com/zapret2/app/data/StrategyRepository.kt"))
            add(mainDir.resolve("java/com/zapret2/app/data/NetworkStatsManager.kt"))
            add(mainDir.resolve("java/com/zapret2/app/data/UpdateExecution.kt"))
        }.filter(Path::isRegularFile)

        val forbidden = listOf(
            "android.view.View",
            "android.widget.Toast",
            "androidx.fragment",
            "AppCompat",
            "com.google.android.material",
            "R.drawable",
            "Fluent",
            "Win11",
            "TelegramHelper",
            "UpdateArtifact",
            "SectionHeaderStyle",
            "NetworkChangeListener",
            "getNetworkTypeIcon",
            "getCategoryStrategyIndex",
            "updateCategoryStrategyByName",
            "updateCategoryFilterMode",
            "getStrategyByIndex",
            "StrategyInfo",
            "iconRes",
            "fitsUtf8EditorBudget",
            "isValidPresetFileName",
            "MAX_COMBINED_SAVED_EDITOR_CHARS",
            "MAX_SIMULTANEOUS_SAVED_EDITOR_FIELDS",
            "MAX_COMBINED_PENDING_ORDER_CHARS",
        )
        val violations = scanned.flatMap { file ->
            val text = file.readText()
            forbidden.filter(text::contains).map { token -> "${file.name}: $token" }
        }
        assertTrue("Legacy production symbols returned:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun directDependenciesCannotRestoreViewStackOrUnusedRootService() {
        val buildFile = appDir.resolve("build.gradle.kts").readText()
        listOf(
            "androidx.appcompat:appcompat",
            "androidx.fragment:fragment",
            "androidx.recyclerview:recyclerview",
            "androidx.viewpager:viewpager",
            "androidx.viewpager2:viewpager2",
            "androidx.constraintlayout:constraintlayout",
            "com.google.android.material:material",
            "com.github.topjohnwu.libsu:service",
        ).forEach { dependency ->
            assertFalse("Forbidden direct dependency returned: $dependency", buildFile.contains(dependency))
        }
    }

    @Test
    fun removedViewResourceFamiliesAndRoundLauncherCannotReturn() {
        val res = mainDir.resolve("res")
        val forbiddenFamilies = listOf("layout", "menu", "navigation", "drawable", "color")
        val forbiddenDirectories = Files.walk(res).use { stream ->
            stream.filter(Files::isDirectory)
                .filter { directory ->
                    forbiddenFamilies.any { family ->
                        directory.name == family || directory.name.startsWith("$family-")
                    }
                }
                .toList()
        }
        assertTrue(
            "Legacy resource directory families returned: $forbiddenDirectories",
            forbiddenDirectories.isEmpty(),
        )

        val manifest = mainDir.resolve("AndroidManifest.xml").readText()
        assertFalse("Duplicate round launcher contract returned", manifest.contains("android:roundIcon"))
        assertTrue("FileProvider contract must remain", manifest.contains("androidx.core.content.FileProvider"))
        assertTrue("Launcher must remain a mipmap", manifest.contains("android:icon=\"@mipmap/ic_launcher\""))

        val roundLaunchers = Files.walk(res).use { stream ->
            stream.filter(Path::isRegularFile)
                .filter { it.name.startsWith("ic_launcher_round") }
                .toList()
        }
        assertTrue("Duplicate round launcher resources returned: $roundLaunchers", roundLaunchers.isEmpty())
    }

    @Test
    fun installerProvider_isPrivateCleartextDisabledAndUpdateCacheScoped() {
        val manifest = mainDir.resolve("AndroidManifest.xml").readText()
        val providerPaths = mainDir.resolve("res/xml/file_paths.xml").readText()
        val legacyBackupRules = mainDir.resolve("res/xml/full_backup_rules.xml").readText()
        val extractionRules = mainDir.resolve("res/xml/data_extraction_rules.xml").readText()
        val updateManager = mainDir.resolve(
            "java/com/zapret2/app/data/UpdateManager.kt",
        ).readText()

        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/full_backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:exported=\"false\""))
        assertFalse(manifest.contains("android.permission.ACCESS_WIFI_STATE"))
        val backupDomains = setOf(
            "root",
            "file",
            "database",
            "sharedpref",
            "external",
            "device_root",
            "device_file",
            "device_database",
            "device_sharedpref",
        )
        backupDomains.forEach { domain ->
            assertTrue(legacyBackupRules.contains("<exclude domain=\"$domain\" path=\".\" />"))
            assertTrue(extractionRules.split("<exclude domain=\"$domain\" path=\".\" />").size - 1 == 2)
        }
        assertTrue(extractionRules.contains("<cloud-backup>"))
        assertTrue(extractionRules.contains("<device-transfer>"))
        assertFalse(legacyBackupRules.contains("<include"))
        assertFalse(extractionRules.contains("<include"))
        assertTrue(providerPaths.contains("name=\"updates\""))
        assertTrue(providerPaths.contains("path=\"updates/\""))
        assertFalse(providerPaths.contains("path=\".\""))
        assertTrue(updateManager.contains("UPDATE_CACHE_DIRECTORY = \"updates\""))
        assertTrue(updateManager.contains("canonicalApk.parentFile == updateCache"))
        assertTrue(updateManager.contains("canonicalApk.length() in 1L..MAX_DOWNLOAD_BYTES"))
        assertTrue(updateManager.contains("setExactMode(canonicalApk, PRIVATE_FILE_MODE)"))
        assertFalse(updateManager.contains("Uri.fromFile"))
        assertFalse(updateManager.contains("setReadable(true, false)"))
        assertFalse(updateManager.contains("setExecutable(true, false)"))
        assertFalse(updateManager.contains("setReadable("))
        assertFalse(updateManager.contains("setWritable("))
        assertFalse(updateManager.contains("setExecutable("))
        assertTrue(updateManager.contains("Os.chmod(file.absolutePath, mode)"))
        assertTrue(updateManager.contains("(Os.stat(file.absolutePath).st_mode and 0x1FF) == mode"))
        assertTrue(updateManager.contains("PRIVATE_FILE_MODE = 0b110_000_000"))
        assertTrue(updateManager.contains("PRIVATE_DIRECTORY_MODE = 0b111_000_000"))
        assertTrue(updateManager.contains("PRIVATE_EXECUTABLE_MODE = 0b111_000_000"))

        val networkManager = mainDir.resolve(
            "java/com/zapret2/app/data/NetworkStatsManager.kt",
        ).readText()
        assertFalse(networkManager.contains("WifiManager"))
        assertFalse(networkManager.contains("wifiSsid"))
    }

    @Test
    fun exportedComponentsAndExternalIntents_areNarrowlyScoped() {
        val manifest = mainDir.resolve("AndroidManifest.xml").readText()
        val declaredPermissions = Regex(
            """<uses-permission\s+android:name="([^"]+)"\s*/>""",
        ).findAll(manifest).map { it.groupValues[1] }.toSet()
        assertTrue(
            declaredPermissions == setOf(
                "android.permission.INTERNET",
                "android.permission.REQUEST_INSTALL_PACKAGES",
                "android.permission.ACCESS_NETWORK_STATE",
            ),
        )
        val componentDeclarations = Regex(
            """<(activity|activity-alias|service|receiver|provider)\b[^>]*>""",
        ).findAll(manifest).map { it.value }.toList()
        val exportedComponents = componentDeclarations.filter {
            it.contains("android:exported=\"true\"")
        }
        assertTrue(exportedComponents.size == 1)
        assertTrue(exportedComponents.single().contains("android:name=\".MainActivity\""))
        assertTrue(manifest.contains("android.intent.action.MAIN"))
        assertTrue(manifest.contains("android.intent.category.LAUNCHER"))
        assertFalse(manifest.contains("<data "))
        assertFalse(manifest.contains("<service"))
        assertFalse(manifest.contains("<receiver"))
        assertFalse(manifest.contains("<activity-alias"))

        val provider = componentDeclarations.single { it.contains("androidx.core.content.FileProvider") }
        assertTrue(provider.contains("android:authorities=\"\${applicationId}.provider\""))
        assertTrue(provider.contains("android:exported=\"false\""))
        assertTrue(provider.contains("android:grantUriPermissions=\"true\""))

        val about = mainDir.resolve("java/com/zapret2/app/ui/screen/AboutScreen.kt").readText()
        assertTrue(about.contains("private enum class AboutDestination"))
        assertTrue(about.contains("val openDestination: (AboutDestination) -> Unit"))
        assertFalse(about.contains("val openUrl: (String) -> Unit"))
        assertTrue(about.split("https://").size - 1 == 4)
        assertFalse(about.contains("http://"))

        val logs = mainDir.resolve("java/com/zapret2/app/ui/screen/LogsScreen.kt").readText()
        val preparation = logs.indexOf("activeViewModel?.prepareShare(state.currentTab, state.logs)")
        val shareIntent = logs.indexOf("Intent(Intent.ACTION_SEND)", startIndex = preparation)
        assertTrue(preparation >= 0 && shareIntent > preparation)
        assertTrue(logs.contains("Intent.createChooser(shareIntent"))

        val logsViewModel = mainDir.resolve("java/com/zapret2/app/viewmodel/LogsViewModel.kt").readText()
        val exactPayload = logsViewModel.indexOf("requestedText != state.logs")
        val redaction = logsViewModel.indexOf("redactedBoundedLogShareText(requestedText)")
        assertTrue(exactPayload >= 0 && redaction > exactPayload)
    }

    @Test
    fun startupThemesStayPlatformOnlyAcrossApiAndNightQualifiers() {
        val themeFiles = Files.walk(mainDir.resolve("res")).use { stream ->
            stream.filter(Path::isRegularFile)
                .filter { it.name == "themes.xml" }
                .toList()
        }
        assertTrue("Expected platform startup theme resources", themeFiles.isNotEmpty())
        val allowedParents = setOf(
            "android:style/Theme.Material.Light.NoActionBar",
            "android:style/Theme.Material.NoActionBar",
            "Theme.Zapret2.Base",
        )
        themeFiles.forEach { file ->
            val text = file.readText()
            Regex("parent=\"([^\"]+)\"").findAll(text).forEach { match ->
                assertTrue(
                    "Non-platform startup parent in $file: ${match.groupValues[1]}",
                    match.groupValues[1] in allowedParents,
                )
            }
            Regex("<item\\s+name=\"([^\"]+)\"").findAll(text).forEach { match ->
                assertTrue(
                    "Non-platform startup attribute in $file: ${match.groupValues[1]}",
                    match.groupValues[1].startsWith("android:"),
                )
            }
        }
    }

    @Test
    fun snackbarEffectsConsumeDurableStateBeforeSuspendingAndToastCannotReturn() {
        val effect = mainDir.resolve(
            "java/com/zapret2/app/ui/components/AppSnackbarEffect.kt",
        ).readText()
        val durableOverload = effect.substringAfter("Consumes durable state")
        val consumeIndex = durableOverload.indexOf("onConsumed()")
        val independentLaunchIndex = durableOverload.indexOf("scope.launch")
        val showIndex = durableOverload.indexOf("hostState.showSnackbar")
        assertTrue(
            "Snackbar state must be consumed before an independent showSnackbar job",
            consumeIndex in 0 until independentLaunchIndex && independentLaunchIndex in 0 until showIndex,
        )

        val screens = kotlinFiles(mainDir.resolve("java/com/zapret2/app/ui/screen"))
        val unsafe = screens.filter { file ->
            val text = file.readText()
            text.contains("LaunchedEffect(state.message)") || text.contains("android.widget.Toast")
        }
        assertTrue("Unsafe/replay-prone notifications returned: $unsafe", unsafe.isEmpty())
    }

    @Test
    fun destinationPreviewMatrixInvokesEveryRealScreenEntryPoint() {
        val previews = mainDir.resolve(
            "java/com/zapret2/app/ui/screen/DestinationScreenPreviews.kt",
        ).readText()
        val entryPoints = listOf(
            "ControlScreen",
            "StrategiesScreen",
            "PresetsScreen",
            "HostlistsScreen",
            "DnsManagerScreen",
            "HostsEditorScreen",
            "ConfigEditorScreen",
            "LogsScreen",
            "AboutScreen",
            "HostlistContentScreen",
        )
        entryPoints.forEach { entryPoint ->
            assertTrue("Real destination preview missing: $entryPoint", previews.contains("$entryPoint("))
        }
        assertTrue(
            "State-backed destinations must cover normal and non-happy deterministic states",
            Regex("previewState\\s*=").findAll(previews).count() >= 35,
        )
        listOf(
            "ModuleInstallState.PARTIAL",
            "isCheckingForUpdates = true",
            "ControlStatus.ROOT_DENIED",
            "isLoading = true",
            "PresetsOperation.LOAD",
            "PresetsUiState()",
            "HostlistsUiState()",
            "HostlistsLoadError.ROOT_COMMAND_FAILED",
            "DnsManagerOperation.LOAD",
            "dnsPresets = emptyList()",
            "actionsEnabled = false",
            "LogsLoadState.LOADING",
            "LogsLoadState.ERROR",
            "HostlistContentLoadState.LOADING",
            "HostlistContentLoadState.ERROR",
            "HostlistContentLoadState.READY",
        ).forEach { stateMarker ->
            assertTrue("Destination preview state missing: $stateMarker", previews.contains(stateMarker))
        }
        listOf(
            "ServiceStatusCard(",
            "CategoriesListView(",
            "PresetCard(",
            "HostlistItem(",
            "DnsServiceItem(",
            "CommandTab(",
            "HostlistViewContent(",
        ).forEach { fragment ->
            assertFalse("Fragment-only destination preview returned: $fragment", previews.contains(fragment))
        }
    }

    private fun kotlinFiles(root: Path): List<Path> =
        if (!root.exists()) emptyList() else Files.walk(root).use { stream ->
            stream.filter(Path::isRegularFile)
                .filter { it.extension == "kt" }
                .toList()
        }

    private fun locateAppDir(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(6) {
            val direct = current.resolve("src/main/AndroidManifest.xml")
            if (direct.exists()) return current
            val nested = current.resolve("app/src/main/AndroidManifest.xml")
            if (nested.exists()) return current.resolve("app")
            current = current.parent ?: return@repeat
        }
        error("Unable to locate Android app module from ${System.getProperty("user.dir")}")
    }
}
