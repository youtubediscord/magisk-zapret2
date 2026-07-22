package com.zapret2.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory

class DependencyTrustPolicyTest {

    @Test
    fun dependencyLocks_coverRealResolvedConfigurations() {
        val buildScript = projectFile("build.gradle.kts").readText()
        assertTrue(buildScript.contains("dependencyLocking"))
        assertTrue(buildScript.contains("lockAllConfigurations()"))

        val entries = projectFile("gradle.lockfile")
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertEquals("Unexpected locked dependency entry count", 347, entries.size)
        assertTrue(entries.any { "debugRuntimeClasspath" in it })
        assertTrue(entries.any { "debugUnitTestRuntimeClasspath" in it })
        assertTrue(entries.any { "releaseRuntimeClasspath" in it })
    }

    @Test
    fun verificationMetadata_isStrictChecksumOnlyWithoutBlanketTrust() {
        val metadata = projectFile("../gradle/verification-metadata.xml")
        val raw = metadata.readText()
        assertTrue(raw.contains("<verify-metadata>true</verify-metadata>"))
        assertFalse(raw.contains("<trusted-artifacts>"))
        assertFalse(raw.contains("<ignored-keys>"))

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(metadata)
        val components = document.getElementsByTagName("component")
        val artifacts = document.getElementsByTagName("artifact")
        val checksums = document.getElementsByTagName("sha256")
        assertEquals("Unexpected verified component count", 589, components.length)
        assertEquals("Unexpected verified artifact count", 962, artifacts.length)
        assertEquals("Every resolved artifact must have exactly one SHA-256", artifacts.length, checksums.length)
        assertEquals(
            "Unexpected verification metadata bytes",
            "a43be8deda50158426cb7ea12fc68993d4ae0949d75d984838c4406e329a3044",
            sha256(metadata),
        )
    }

    @Test
    fun ciEnforcesStrictVerificationOnEveryAndroidGradleInvocation() {
        val workflow = projectFile("../../.github/workflows/build.yml").readLines()
        val gradleInvocations = workflow.filter { "./gradlew" in it }
        assertTrue("Expected Android Gradle invocations in CI", gradleInvocations.isNotEmpty())
        assertTrue(
            "Every Android Gradle invocation must fail closed on dependency verification",
            gradleInvocations.all { "--dependency-verification=strict" in it },
        )
        assertTrue(workflow.none { "dependency-verification=lenient" in it })
        assertTrue(workflow.none { "dependency-verification=off" in it })
    }

    private fun projectFile(relativePath: String): File = File(relativePath).also {
        assertTrue("Missing policy input: ${it.absolutePath}", it.isFile)
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
