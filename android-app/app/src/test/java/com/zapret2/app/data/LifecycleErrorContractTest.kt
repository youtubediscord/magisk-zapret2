package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LifecycleErrorContractTest {

    @Test
    fun parserAcceptsExactCompleteWireRecord() {
        val error = LifecycleErrorContract.parseLines(
            listOf(
                "Z2_ERROR_SCHEMA=1",
                "Z2_ERROR_DOMAIN=PROCESS",
                "Z2_ERROR_CODE=PROCESS_LAUNCH_FAILED",
                "Z2_ERROR_STAGE=START_PROCESS",
                "Z2_ERROR_RETRYABLE=1",
            ),
        )

        assertEquals(LifecycleErrorDomain.PROCESS, error?.domain)
        assertEquals(LifecycleErrorCode.PROCESS_LAUNCH_FAILED, error?.code)
        assertEquals("START_PROCESS", error?.stage)
        assertTrue(error?.retryable == true)
    }

    @Test
    fun parserRejectsMissingDuplicateUnknownAndContradictoryRecords() {
        val valid = listOf(
            "Z2_ERROR_SCHEMA=1",
            "Z2_ERROR_DOMAIN=NONE",
            "Z2_ERROR_CODE=NONE",
            "Z2_ERROR_STAGE=NONE",
            "Z2_ERROR_RETRYABLE=0",
        )

        assertNull(LifecycleErrorContract.parseLines(valid.dropLast(1)))
        assertNull(LifecycleErrorContract.parseLines(valid + "Z2_ERROR_STAGE=NONE"))
        assertNull(
            LifecycleErrorContract.parseLines(
                valid.map {
                    if (it == "Z2_ERROR_CODE=NONE") "Z2_ERROR_CODE=UNKNOWN" else it
                },
            ),
        )
        assertNull(
            LifecycleErrorContract.parseLines(
                valid.map {
                    if (it == "Z2_ERROR_DOMAIN=NONE") "Z2_ERROR_DOMAIN=ROOT" else it
                },
            ),
        )
        assertNull(
            LifecycleErrorContract.parseLines(
                listOf(
                    "Z2_ERROR_SCHEMA=1",
                    "Z2_ERROR_DOMAIN=PROCESS",
                    "Z2_ERROR_CODE=FIREWALL_BUILD_FAILED",
                    "Z2_ERROR_STAGE=START_IPV4_BUILD_RULE",
                    "Z2_ERROR_RETRYABLE=1",
                ),
            ),
        )
    }

    @Test
    fun shellAndAndroidEnumerationsStayMirrored() {
        val common = File("../../zapret2/scripts/common.sh")
        assertTrue("Missing shell contract: ${common.absolutePath}", common.isFile)
        val source = common.readText()

        assertEquals(
            LifecycleErrorDomain.entries.map { it.name }.toSet(),
            shellContractTokens(source, "z2_error_domain_is_valid", "z2_error_code_is_valid"),
        )
        assertEquals(
            LifecycleErrorCode.entries.map { it.name }.toSet(),
            shellContractTokens(source, "z2_error_code_is_valid", "z2_error_stage_is_valid"),
        )
    }

    private fun shellContractTokens(source: String, start: String, end: String): Set<String> {
        val body = source.substringAfter("$start() {").substringBefore("$end() {")
        return Regex("[A-Z][A-Z0-9_]+").findAll(body).map { it.value }.toSet()
    }
}
