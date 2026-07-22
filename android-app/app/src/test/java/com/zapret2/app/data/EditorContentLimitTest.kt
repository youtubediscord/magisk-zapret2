package com.zapret2.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorContentLimitTest {

    @Test
    fun commandLineValidation_acceptsOnlyOneExactVersionedMachineRecord() {
        val fileName = "Custom Options"

        assertTrue(
            parseCommandLineValidation(
                listOf("Z2_CMDLINE\t1\tOK\t$fileName"),
                commandSucceeded = true,
                fileName = fileName,
            ) == CommandLineValidation.VALID,
        )
        assertTrue(
            parseCommandLineValidation(
                listOf("Z2_CMDLINE\t1\tINVALID\t$fileName"),
                commandSucceeded = false,
                fileName = fileName,
            ) == CommandLineValidation.INVALID,
        )
        assertTrue(
            parseCommandLineValidation(
                listOf("Z2_CMDLINE\t1\tOK\tother", "diagnostic noise"),
                commandSucceeded = true,
                fileName = fileName,
            ) == CommandLineValidation.FAILED,
        )
        assertTrue(
            parseCommandLineValidation(
                listOf("Z2_CMDLINE\t1\tOK\t$fileName"),
                commandSucceeded = false,
                fileName = fileName,
            ) == CommandLineValidation.FAILED,
        )
        assertTrue(
            parseCommandLineValidation(
                listOf("Z2_CMDLINE_ERROR\tBINARY_UNAVAILABLE"),
                commandSucceeded = false,
                fileName = fileName,
            ) == CommandLineValidation.FAILED,
        )
        assertTrue(
            parseCommandLineValidation(
                listOf("Z2_CMDLINE_ERROR\tINTEGRITY_UNAVAILABLE"),
                commandSucceeded = false,
                fileName = fileName,
            ) == CommandLineValidation.FAILED,
        )
    }

    @Test
    fun commandLineLimit_countsUtf8BytesBeforeMutation() {
        val repository = CommandLineRepository()

        assertTrue(repository.isContentSizeAllowed("a".repeat(256 * 1024 - 1)))
        assertTrue(repository.isContentSizeAllowed("a".repeat(256 * 1024 - 1) + "\n"))
        assertFalse(repository.isContentSizeAllowed("a".repeat(256 * 1024)))
        assertFalse(repository.isContentSizeAllowed("я".repeat(128 * 1024)))
    }

    @Test
    fun hostsAndHostlistLimits_rejectOversizedDraftsBeforeMutation() {
        val persistableAtLimit = "a".repeat(1024 * 1024 - 1)
        val unpersistableAtLimit = persistableAtLimit + "a"

        assertTrue(HostsOverlayRepository().isContentSizeAllowed(persistableAtLimit))
        assertTrue(HostsOverlayRepository().isContentSizeAllowed("$persistableAtLimit\n"))
        assertFalse(HostsOverlayRepository().isContentSizeAllowed(unpersistableAtLimit))
        assertTrue(HostlistRepository().isEditableContentSizeAllowed(persistableAtLimit))
        assertTrue(HostlistRepository().isEditableContentSizeAllowed("$persistableAtLimit\n"))
        assertFalse(HostlistRepository().isEditableContentSizeAllowed(unpersistableAtLimit))
        assertTrue(PresetContentPolicy.isPersistable(persistableAtLimit))
        assertFalse(PresetContentPolicy.isPersistable(unpersistableAtLimit))
    }
}
