package com.zapret2.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogsShareTest {

    @Test
    fun onlyRealLogTabsMapToClearableRuntimeSelections() {
        assertNull(LogTab.COMMAND.runtimeSelectionOrNull())
        assertEquals(
            com.zapret2.app.data.RuntimeLogSelection.MAIN,
            LogTab.LOGS.runtimeSelectionOrNull(),
        )
        assertEquals(
            com.zapret2.app.data.RuntimeLogSelection.WARNINGS,
            LogTab.WARNINGS.runtimeSelectionOrNull(),
        )
    }

    @Test
    fun sharePreparation_requiresTheCurrentReadyTabAndExactPayload() {
        val state = LogsUiState(
            currentTab = LogTab.LOGS,
            outputLoadState = LogsLoadState.READY,
            logs = "safe log line",
        )

        assertEquals(
            LogSharePreparation.Ready("safe log line"),
            prepareLogShare(state, true, LogTab.LOGS, "safe log line"),
        )
        assertEquals(
            LogSharePreparation.Rejected,
            prepareLogShare(state, true, LogTab.WARNINGS, "safe log line"),
        )
        assertEquals(
            LogSharePreparation.Rejected,
            prepareLogShare(state, true, LogTab.LOGS, "stale log line"),
        )
        assertEquals(
            LogSharePreparation.Rejected,
            prepareLogShare(state, false, LogTab.LOGS, "safe log line"),
        )
        assertEquals(
            LogSharePreparation.Empty,
            prepareLogShare(state.copy(logs = ""), true, LogTab.LOGS, ""),
        )
    }

    @Test
    fun redactedShareText_trimsAndKeepsRecentTail() {
        assertEquals("recent", redactedBoundedLogShareText("  recent  "))

        val source = "old" + "x".repeat(MAX_LOG_SHARE_CHARS) + "tail"
        val bounded = redactedBoundedLogShareText(source)
        assertEquals(MAX_LOG_SHARE_CHARS, bounded.length)
        assertTrue(bounded.endsWith("tail"))
        assertTrue(!bounded.startsWith("old"))
    }

    @Test
    fun redactedShareText_emptyInputRemainsEmpty() {
        assertEquals("", redactedBoundedLogShareText(" \n\t "))
    }

    @Test
    fun redactedShareText_removesSecretsAndPrivateIdentifiers() {
        val shared = redactedBoundedLogShareText(
            """
            Authorization: Bearer ey.secret.token
            password=hunter2 token='abc-123' api_key=key-value
            host=private-router.local ssid="Family WiFi"
            peers=10.1.2.3,192.168.1.8,172.31.2.4,127.0.0.1,169.254.4.2,::1,fd00::2
            files=/data/user/0/com.example/files/private.txt C:\Users\alice\secret.txt
            nfqueue=200 port=443 public_dns=8.8.8.8 module=/data/adb/modules/zapret2
            ERROR dry-run failed: NFQUEUE unavailable
            """.trimIndent(),
        )

        listOf(
            "ey.secret.token", "hunter2", "abc-123", "key-value", "private-router.local",
            "Family WiFi", "10.1.2.3", "192.168.1.8", "172.31.2.4", "127.0.0.1",
            "169.254.4.2", "::1", "fd00::2", "com.example", "alice",
        ).forEach { privateValue ->
            assertTrue("Leaked private value: $privateValue", privateValue !in shared)
        }
        assertTrue("[REDACTED_SECRET]" in shared)
        assertTrue("[REDACTED_PRIVATE]" in shared)
        assertTrue("nfqueue=200 port=443 public_dns=8.8.8.8" in shared)
        assertTrue("module=/data/adb/modules/zapret2" in shared)
        assertTrue("ERROR dry-run failed: NFQUEUE unavailable" in shared)
    }

    @Test
    fun redactionRunsBeforeBoundingSoTruncatedSecretsCannotLeak() {
        val source = "x".repeat(MAX_LOG_SHARE_CHARS) + "\nauthorization=Basic dXNlcjpwYXNz"
        val shared = redactedBoundedLogShareText(source)

        assertEquals(MAX_LOG_SHARE_CHARS, shared.length)
        assertTrue("dXNlcjpwYXNz" !in shared)
        assertTrue(shared.endsWith("authorization=[REDACTED_SECRET]"))
    }
}
