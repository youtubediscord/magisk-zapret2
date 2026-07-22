package com.zapret2.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseCiPolicyTest {

    @Test
    fun sourceWarningsAndWhitespaceFailClosed() {
        val workflow = repositoryFile(".github/workflows/build.yml").readText()
        val appBuild = repositoryFile("android-app/app/build.gradle.kts").readText()
        val attributes = repositoryFile(".gitattributes").readLines().toSet()

        assertTrue(workflow.contains("git diff --check \"\$EMPTY_TREE\" \"\$GITHUB_SHA\" -- ."))
        assertTrue(appBuild.contains("allWarningsAsErrors.set(true)"))
        assertTrue(appBuild.contains("warningsAsErrors = true"))
        assertTrue(attributes.contains("* text=auto eol=lf"))
        assertTrue(attributes.contains("*.bat text eol=crlf"))
        assertTrue(attributes.contains("*.jar binary"))
        assertTrue(attributes.contains("*.png binary"))

        val compatibilityFailure = workflow.substringAfter("if [ \"\$WARN_COUNT\" -gt 0 ]; then")
            .substringBefore("else")
        assertTrue(compatibilityFailure.contains("::error::"))
        assertTrue(compatibilityFailure.contains("exit 1"))
        assertFalse(compatibilityFailure.contains("::warning::"))
        assertFalse(workflow.contains("::warning file=\$lua_file"))
    }

    @Test
    fun committedSecretsAndReleaseDebugConfigurationFailClosed() {
        val workflow = repositoryFile(".github/workflows/build.yml").readText()
        val appBuild = repositoryFile("android-app/app/build.gradle.kts").readText()
        val manifest = repositoryFile("android-app/app/src/main/AndroidManifest.xml").readText()
        val proguardRules = repositoryFile("android-app/app/proguard-rules.pro").readText()
        val gitIgnore = repositoryFile(".gitignore").readLines().toSet()
        val buildTypes = appBuild.substringAfter("buildTypes {")
        val releaseBuildType = buildTypes.substringAfter("release {").substringBefore("\n        }")

        assertTrue(workflow.contains("git ls-files"))
        assertTrue(workflow.contains("PRIVATE KEY-----"))
        assertTrue(workflow.contains("android:(debuggable|testOnly)"))
        assertTrue(buildTypes.contains("release {"))
        assertTrue(releaseBuildType.contains("isDebuggable = false"))
        assertTrue(releaseBuildType.contains("isMinifyEnabled = true"))
        assertTrue(releaseBuildType.contains("isShrinkResources = true"))
        assertTrue(releaseBuildType.contains("signingConfig = signingConfigs.getByName(\"release\")"))
        assertFalse(releaseBuildType.contains("signingConfigs.getByName(\"debug\")"))
        assertFalse(manifest.contains("android:debuggable=\"true\""))
        assertFalse(manifest.contains("android:testOnly=\"true\""))
        assertFalse(proguardRules.contains("-keep class com.topjohnwu.superuser.**"))
        assertTrue(gitIgnore.containsAll(setOf(".env", ".env.*", "*.jks", "*.keystore", "*.p12", "*.pfx")))
    }

    @Test
    fun releaseLogging_isConfinedToOneDebugOnlyBoundary() {
        val sourceRoot = repositoryDirectory("android-app/app/src/main/java")
        val logger = repositoryFile(
            "android-app/app/src/main/java/com/zapret2/app/AppDebugLog.kt",
        ).readText()
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "AppDebugLog.kt" }
            .filter { file ->
                val source = file.readText()
                "android.util.Log" in source ||
                    Regex("""\bLog\.(?:v|d|i|w|e|wtf)\(""").containsMatchIn(source) ||
                    "printStackTrace(" in source ||
                    "System.out." in source ||
                    "System.err." in source
            }
            .map { it.relativeTo(sourceRoot).path }
            .toList()

        assertTrue("Direct production logging escaped the debug boundary: $violations", violations.isEmpty())
        assertTrue(logger.contains("if (!BuildConfig.DEBUG) return"))
        assertTrue(logger.contains("Log.e(tag, message, error)"))
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

    private fun repositoryDirectory(relativePath: String): File {
        var current = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isDirectory) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository directory: $relativePath")
    }
}
