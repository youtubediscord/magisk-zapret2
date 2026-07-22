package com.zapret2.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionMetadataPolicyTest {

    @Test
    fun sourcePlaceholders_shareOneCanonicalProjectVersion() {
        val series = repositoryFile("version.series").readText().trim()
        val appGradle = repositoryFile("android-app/app/build.gradle.kts").readText()
        val moduleProp = repositoryFile("module.prop").readText()
        val updateJson = repositoryFile("update.json").readText()

        val appVersionCode = capture(appGradle, "versionCode\\s*=\\s*([0-9]+)").toLong()
        val appVersion = capture(appGradle, "versionName\\s*=\\s*\"([^\"]+)\"")

        assertEquals("$series.$appVersionCode", appVersion)
        assertEquals("v$appVersion", capture(moduleProp, "(?m)^version=([^\\r\\n]+)$"))
        assertEquals(appVersionCode, capture(moduleProp, "(?m)^versionCode=([0-9]+)$").toLong())
        assertEquals("v$appVersion", capture(updateJson, "\"version\"\\s*:\\s*\"([^\"]+)\""))
        assertEquals(appVersionCode, capture(updateJson, "\"versionCode\"\\s*:\\s*([0-9]+)").toLong())
        assertEquals(appVersionCode, requireNotNull(projectReleaseVersionCode(appVersion)))
    }

    @Test
    fun ciAndLocalBuilder_publishTheSameVersionTuple() {
        val workflow = repositoryFile(".github/workflows/build.yml").readText()
        val builder = repositoryFile("build.sh").readText()

        assertTrue(workflow.contains("VERSION=\"\${VERSION_SERIES}.\${VERSION_CODE}\""))
        assertTrue(workflow.contains("version=v\${VERSION}"))
        assertTrue(workflow.contains("versionCode=\${VERSION_CODE}"))
        assertTrue(workflow.contains("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"))
        assertTrue(builder.contains("VERSION=\"\${VERSION_SERIES}.\${VERSION_CODE}\""))
        assertTrue(builder.contains("VERSION_CODE=\"\${BASH_REMATCH[1]}\""))
        assertTrue(builder.contains("10#\$VERSION_CODE > 2100000000"))
        assertTrue(builder.contains("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$"))
    }

    @Test
    fun ciValidatesCanonicalSourceBeforeStampingTheReleaseVersion() {
        val workflow = repositoryFile(".github/workflows/build.yml").readText()
        val unitTests = workflow.indexOf("- name: Run Android unit tests")
        val debugBuild = workflow.indexOf("- name: Build Debug APK")
        val lint = workflow.indexOf("- name: Run Android lint")
        val keystore = workflow.indexOf("- name: Create keystore")
        val versionStamp = workflow.indexOf("- name: Set version from CI metadata")
        val releaseBuild = workflow.indexOf("- name: Build Release APK")

        mapOf(
            "unit tests" to unitTests,
            "debug build" to debugBuild,
            "lint" to lint,
            "keystore" to keystore,
            "version stamp" to versionStamp,
            "release build" to releaseBuild,
        ).forEach { (step, index) ->
            assertTrue("Missing CI step: $step", index >= 0)
        }
        assertTrue(unitTests < debugBuild)
        assertTrue(debugBuild < lint)
        assertTrue(lint < keystore)
        assertTrue(keystore < versionStamp)
        assertTrue(versionStamp < releaseBuild)
    }

    private fun capture(text: String, pattern: String): String =
        Regex(pattern).find(text)?.groupValues?.get(1)
            ?: error("Missing version metadata pattern: $pattern")

    private fun repositoryFile(relativePath: String): File {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate repository file: $relativePath")
    }
}
