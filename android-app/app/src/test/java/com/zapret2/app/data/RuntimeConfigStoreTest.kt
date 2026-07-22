package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
        pkt_out=20
        pkt_in=10
        active_preset=Default v1 (game filter).txt
        nfqws_uid=0:0
        log_mode=none
    """.trimIndent()

    @Test
    fun runtimeCore_requiresTheCompleteRuntimeV1Contract() {
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("qnum=200\n", "")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("schema_version=1", "schema_version=2")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("[core]", "[CORE]")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("[core]", "[ core ]")))
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("[core]", "  [core]  ")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("autostart=1", "AUTOSTART=1")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore("$completeRuntime\nqnum=201"))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore("$completeRuntime\n[core]\nqnum=201"))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("qnum=200", "qnum=65536")))
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("pkt_out=20", "pkt_out=101")))
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("pkt_out=20", "pkt_out=999999999")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("pkt_out=20", "pkt_out=020")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("pkt_out=20", "pkt_out=1000000000")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("desync_mark=0x40000000", "desync_mark=0x100000000")))
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("desync_mark=0x40000000", "desync_mark=4294967295")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("Default v1 (game filter).txt", "../Default.txt")))
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("Default v1 (game filter).txt", "Custom Options.txt")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("Default v1 (game filter).txt", "runtime.ini")))
        assertFalse(
            RuntimeConfigStore.hasCompleteRuntimeCore(
                completeRuntime.replace("Default v1 (game filter).txt", "\u044f".repeat(126) + ".txt"),
            ),
        )
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("nfqws_uid=0:0", "nfqws_uid=root")))
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("nfqws_uid=0:0", "nfqws_uid=2147483647:2147483647")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("nfqws_uid=0:0", "nfqws_uid=01:0")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore(completeRuntime.replace("nfqws_uid=0:0", "nfqws_uid=2147483648:0")))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore("$completeRuntime\nfuture_option=value"))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore("$completeRuntime\nfuture_option=\"unterminated"))
        assertFalse(RuntimeConfigStore.hasCompleteRuntimeCore("$completeRuntime\nfuture_option=value\tpoison"))
    }

    @Test
    fun coreSettingsUpdate_mapsEverySupportedFieldToRuntimeKeys() {
        val pairs = RuntimeConfigStore.CoreSettingsUpdate(
            activePreset = "Default v1 (game filter).txt",
            logMode = "file",
            pktOut = 20,
            pktIn = 10,
            autostart = true,
            wifiOnly = false,
            desyncMark = "0x40000000",
            nfqwsUid = "0:0",
        ).toCorePairs()

        assertEquals(
            linkedMapOf(
                "active_preset" to "Default v1 (game filter).txt",
                "log_mode" to "file",
                "pkt_out" to "20",
                "pkt_in" to "10",
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
        val pairs = RuntimeConfigStore.CoreSettingsUpdate(pktOut = 21, autostart = false).toCorePairs()

        assertEquals(mapOf("pkt_out" to "21", "autostart" to "0"), pairs)
        assertFalse(pairs.containsKey("pkt_in"))
        assertTrue(RuntimeConfigStore.CoreSettingsUpdate().toCorePairs().isEmpty())
    }

    @Test
    fun coreReadsAndWrites_ignoreCaseOrWhitespaceDecoySections() {
        val content = "[CORE]\npkt_out=91\n[ core ]\npkt_out=92\n$completeRuntime\n"

        assertEquals("20", RuntimeConfigStore.parseSectionValues(content, "core")["pkt_out"])
        val updated = RuntimeConfigStore.upsertSectionValues(
            content = content,
            sectionName = "core",
            updates = mapOf("pkt_out" to "21"),
        )
        assertEquals("21", RuntimeConfigStore.parseSectionValues(updated, "core")["pkt_out"])
        assertTrue(updated.contains("[CORE]\npkt_out=91"))
        assertTrue(updated.contains("[ core ]\npkt_out=92"))
        assertTrue(RuntimeConfigStore.hasCompleteRuntimeCore(updated))
    }

    @Test
    fun optionalSectionReadsAndWrites_failClosedOnExactDuplicates() {
        val duplicated = "$completeRuntime\n[dns_manager]\nselected_dns=one\n[dns_manager]\nselected_dns=two\n"
        val duplicateKey = "$completeRuntime\n[dns_manager]\nselected_dns=one\nselected_dns=two\n"
        val malformed = "$completeRuntime\n[dns_manager]\nselected_dns=\"unterminated\n"

        assertTrue(RuntimeConfigStore.parseSectionValues(duplicated, "dns_manager").isEmpty())
        assertTrue(RuntimeConfigStore.parseSectionValues(duplicateKey, "dns_manager").isEmpty())
        assertTrue(RuntimeConfigStore.parseSectionValues(malformed, "dns_manager").isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigStore.upsertSectionValues(
                content = duplicated,
                sectionName = "dns_manager",
                updates = mapOf("selected_dns" to "three"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigStore.upsertSectionValues(
                content = duplicateKey,
                sectionName = "dns_manager",
                updates = mapOf("selected_dns" to "three"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeConfigStore.upsertSectionValues(
                content = malformed,
                sectionName = "dns_manager",
                updates = mapOf("selected_dns" to "three"),
            )
        }
    }

    @Test
    fun positiveCountParser_preservesTheFullRuntimeContractRange() {
        assertEquals(999_999_999, MAX_PACKET_COUNT)
        assertEquals(1, RuntimeConfigStore.positiveCountOrNull("1"))
        assertEquals(101, RuntimeConfigStore.positiveCountOrNull("101"))
        assertEquals(999_999_999, RuntimeConfigStore.positiveCountOrNull("999999999"))
        assertEquals(null, RuntimeConfigStore.positiveCountOrNull(null))
        assertEquals(null, RuntimeConfigStore.positiveCountOrNull("0"))
        assertEquals(null, RuntimeConfigStore.positiveCountOrNull("01"))
        assertEquals(null, RuntimeConfigStore.positiveCountOrNull("1000000000"))
    }
}
