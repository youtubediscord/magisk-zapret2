package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LifecycleErrorContractTest {

    @Test
    fun parserAcceptsExactCompleteWireRecord() {
        val error = LifecycleErrorContract.parseLines(
            listOf(
                "Z2_ERROR_SCHEMA=1",
                "Z2_ERROR_STATUS=ERROR",
                "Z2_ERROR_DOMAIN=PROCESS",
                "Z2_ERROR_STAGE=START_PROCESS",
                "Z2_ERROR_CODE=PROCESS_LAUNCH_FAILED",
                "Z2_ERROR_DETAIL=nfqws2 exited before ownership publication",
            ),
        )

        assertEquals("ERROR", error?.status)
        assertEquals("PROCESS", error?.domain)
        assertEquals("PROCESS_LAUNCH_FAILED", error?.code)
        assertEquals("START_PROCESS", error?.stage)
        assertEquals("nfqws2 exited before ownership publication", error?.detail)
    }

    @Test
    fun parserAcceptsUnknownFutureIdentityWithoutAnApkChange() {
        val error = LifecycleErrorContract.parseLines(
            listOf(
                "Z2_ERROR_SCHEMA=1",
                "Z2_ERROR_STATUS=ERROR",
                "Z2_ERROR_DOMAIN=FUTURE_DOMAIN",
                "Z2_ERROR_STAGE=FUTURE_STAGE",
                "Z2_ERROR_CODE=FUTURE_FAILURE",
                "Z2_ERROR_DETAIL=future module diagnostic",
            ),
        )

        assertEquals("FUTURE_DOMAIN", error?.domain)
        assertEquals("FUTURE_STAGE", error?.stage)
        assertEquals("FUTURE_FAILURE", error?.code)
    }

    @Test
    fun parserRejectsMissingDuplicateContradictoryOrUnboundedRecords() {
        val valid = listOf(
            "Z2_ERROR_SCHEMA=1",
            "Z2_ERROR_STATUS=OK",
            "Z2_ERROR_DOMAIN=NONE",
            "Z2_ERROR_STAGE=NONE",
            "Z2_ERROR_CODE=NONE",
            "Z2_ERROR_DETAIL=",
        )

        assertTrue(LifecycleErrorContract.parseLines(valid)?.isNone == true)
        assertNull(LifecycleErrorContract.parseLines(valid.dropLast(1)))
        assertNull(LifecycleErrorContract.parseLines(valid + "Z2_ERROR_STAGE=NONE"))
        assertNull(
            LifecycleErrorContract.parseLines(
                valid.map {
                    if (it == "Z2_ERROR_STATUS=OK") "Z2_ERROR_STATUS=ERROR" else it
                },
            ),
        )
        assertNull(
            LifecycleErrorContract.parseLines(
                valid.map {
                    if (it.startsWith("Z2_ERROR_DETAIL=")) {
                        "Z2_ERROR_DETAIL=${"x".repeat(LifecycleErrorContract.MAX_DETAIL_BYTES + 1)}"
                    } else {
                        it
                    }
                },
            ),
        )
    }

    @Test
    fun formatterShowsTheCompleteSingleLineFieldEnvelope() {
        val error = LifecycleErrorContract.error(
            domain = "CONFIG",
            stage = "RUNTIME_PARSE",
            code = "INVALID_QNUM",
            detail = "qnum=70000,\nexpected 1..65535",
        )

        assertEquals(
            """
                schema=1
                status=ERROR
                domain=CONFIG
                stage=RUNTIME_PARSE
                code=INVALID_QNUM
                detail=qnum=70000, expected 1..65535
            """.trimIndent(),
            error.diagnosticText(),
        )
    }

    @Test
    fun androidParser_acceptsTheRealShellRuntimeEnvelope() {
        val directory = Files.createTempDirectory("runtime-envelope-").toFile()
        try {
            val process = ProcessBuilder(
                "sh",
                repositoryRoot().resolve("zapret2/scripts/runtime-config.sh").path,
                "--inspect-machine",
                directory.resolve("runtime.ini").path,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readLines()

            assertEquals(output.joinToString("\n"), 0, process.waitFor())
            val envelope = LifecycleErrorContract.parseLines(output)
            assertEquals("ERROR", envelope?.status)
            assertEquals("CONFIG", envelope?.domain)
            assertEquals("RUNTIME_OPEN", envelope?.stage)
            assertEquals("RUNTIME_MISSING", envelope?.code)
        } finally {
            directory.delete()
        }
    }

    private fun repositoryRoot(): File {
        val workingDirectory = requireNotNull(System.getProperty("user.dir"))
        return generateSequence(File(workingDirectory).canonicalFile) { it.parentFile }
            .firstOrNull { it.resolve("zapret2/scripts/runtime-config.sh").isFile }
            ?: error("Repository root was not found")
    }
}
