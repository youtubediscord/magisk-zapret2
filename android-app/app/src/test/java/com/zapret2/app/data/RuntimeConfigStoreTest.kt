package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeConfigStoreTest {

    private val completeRuntime = """
        [core]
        schema_version=1
        config_format=runtime-v1
        runtime_source=test
        autostart=1
        wifi_only=0
        debug=0
        qnum=200
        desync_mark=0x40000000
        active_preset=Default v1 (game filter).txt
        nfqws_uid=0:0
        log_mode=none
    """.trimIndent()

    @Test
    fun sharedFixtures_areOwnedByTheRealShellParser() {
        fixtureCases().forEach { (fixture, expected) ->
            assertEquals("${fixture.name}: shell verdict", expected, shellVerdict(fixture))
        }
    }

    @Test
    fun kotlinTextEditor_outputIsAcceptedByTheRealShellParser() {
        val source = fixtureDirectory().resolve("valid-default.ini").readText()
        val updated = RuntimeConfigCodec.upsertSectionValues(
            content = source,
            sectionName = "core",
            updates = mapOf("active_preset" to "Kotlin #1; mobile.txt"),
        )
        val candidate = Files.createTempFile("runtime-kotlin-writer-", ".ini").toFile()
        try {
            candidate.writeText(updated)
            assertEquals("VALID", shellVerdict(candidate))
        } finally {
            candidate.delete()
        }
    }

    @Test
    fun shellWriter_outputIsAcceptedByTheRealShellParser() {
        val directory = Files.createTempDirectory("runtime-shell-writer-").toFile()
        val target = directory.resolve("runtime.ini")
        try {
            val process = ProcessBuilder(
                "sh",
                repositoryRoot().resolve("zapret2/scripts/runtime-config.sh").path,
                target.path,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(output, 0, process.waitFor())
            assertEquals("VALID", shellVerdict(target))
        } finally {
            target.delete()
            directory.delete()
        }
    }

    @Test
    fun runtimeDiagnostics_renderTheOpaqueEnvelopeWithoutEnums() {
        val error = LifecycleErrorContract.error(
            domain = "FUTURE_DOMAIN",
            stage = "FUTURE_STAGE",
            code = "FUTURE_CODE",
            detail = "new module detail",
        )

        assertEquals(
            """
                schema=1
                status=ERROR
                domain=FUTURE_DOMAIN
                stage=FUTURE_STAGE
                code=FUTURE_CODE
                detail=new module detail
            """.trimIndent(),
            RuntimeConfigReadResult.Failure(error).diagnosticText(),
        )
        assertEquals(
            error.diagnosticText(),
            RuntimeConfigMutationResult.WriteFailed(error).diagnosticTextOrNull(),
        )
    }

    @Test
    fun coreSettingsUpdate_mapsEverySupportedFieldToRuntimeKeys() {
        val pairs = RuntimeConfigStore.CoreSettingsUpdate(
            activePreset = "Default v1 (game filter).txt",
            logMode = "file",
            autostart = true,
            wifiOnly = false,
            desyncMark = "0x40000000",
            nfqwsUid = "0:0",
        ).toCorePairs()

        assertEquals(
            linkedMapOf(
                "active_preset" to "Default v1 (game filter).txt",
                "log_mode" to "file",
                "autostart" to "1",
                "wifi_only" to "0",
                "desync_mark" to "0x40000000",
                "nfqws_uid" to "0:0",
            ),
            pairs,
        )
    }

    @Test
    fun coreSettingsUpdate_omitsUnspecifiedFieldsWithoutInventingDefaults() {
        val pairs = RuntimeConfigStore.CoreSettingsUpdate(autostart = false).toCorePairs()

        assertEquals(mapOf("autostart" to "0"), pairs)
        assertTrue(RuntimeConfigStore.CoreSettingsUpdate().toCorePairs().isEmpty())
    }

    @Test
    fun coreTextEdits_ignoreCaseOrWhitespaceDecoySections() {
        val content = "[CORE]\nautostart=0\n[ core ]\nautostart=0\n$completeRuntime\n"

        val parsed = RuntimeConfigCodec.parseSection(content, "core")
        assertTrue(parsed is RuntimeConfigSectionResult.Valid)
        assertEquals("1", (parsed as RuntimeConfigSectionResult.Valid).values["autostart"])
        val updated = RuntimeConfigCodec.upsertSectionValues(
            content = content,
            sectionName = "core",
            updates = mapOf("autostart" to "0"),
        )
        val updatedSection = RuntimeConfigCodec.parseSection(updated, "core")
        assertEquals(
            "0",
            (updatedSection as RuntimeConfigSectionResult.Valid).values["autostart"],
        )
        assertTrue(updated.contains("[CORE]\nautostart=0"))
        assertTrue(updated.contains("[ core ]\nautostart=0"))
        val candidate = Files.createTempFile("runtime-decoy-", ".ini").toFile()
        try {
            candidate.writeText(updated)
            assertEquals("VALID", shellVerdict(candidate))
        } finally {
            candidate.delete()
        }
    }

    @Test
    fun optionalSectionReadsAndWrites_failClosedOnExactDuplicates() {
        val duplicated = "$completeRuntime\n[dns_manager]\nselected_dns=one\n[dns_manager]\nselected_dns=two\n"
        val duplicateKey = "$completeRuntime\n[dns_manager]\nselected_dns=one\nselected_dns=two\n"
        val malformed = "$completeRuntime\n[dns_manager]\nselected_dns=\"unterminated\n"

        listOf(duplicated, duplicateKey, malformed).forEach { content ->
            assertTrue(
                RuntimeConfigCodec.parseSection(content, "dns_manager") is
                    RuntimeConfigSectionResult.Malformed,
            )
            assertThrows(IllegalArgumentException::class.java) {
                RuntimeConfigCodec.upsertSectionValues(
                    content = content,
                    sectionName = "dns_manager",
                    updates = mapOf("selected_dns" to "three"),
                )
            }
        }
    }

    private fun fixtureCases(): List<Pair<File, String>> {
        val fixtureDirectory = fixtureDirectory()
        return fixtureDirectory.resolve("manifest.tsv").readLines()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { row ->
                val fields = row.split('\t')
                require(fields.size == 2) { "Invalid runtime fixture manifest row: $row" }
                fixtureDirectory.resolve(fields[0]) to fields[1]
            }
    }

    private fun shellVerdict(file: File): String {
        val process = ProcessBuilder(
            "sh",
            repositoryRoot().resolve("tests/shell/runtime-config-contract.sh").path,
            "--file",
            file.path,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertEquals(output, 0, process.waitFor())
        return output
    }

    private fun fixtureDirectory(): File =
        repositoryRoot().resolve("tests/fixtures/runtime-config")

    private fun repositoryRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("tests/fixtures/runtime-config/manifest.tsv").isFile }
            ?: error("Repository root with runtime fixtures was not found")
    }
}
