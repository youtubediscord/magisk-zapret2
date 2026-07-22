package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootFileIoTest {

    @Test
    fun canonicalProtectedText_matchesShellReadRepresentation() {
        assertEquals("one\ntwo", canonicalProtectedText("one\r\ntwo\n\n"))
        assertEquals("one\ntwo", canonicalProtectedText("one\rtwo"))
        assertEquals("", canonicalProtectedText("\n"))
    }

    @Test
    fun shellQuote_wrapsEmptyAndOrdinaryValues() {
        assertEquals("''", RootFileIo.shellQuote(""))
        assertEquals("'/data/adb/modules/zapret2/config.sh'", RootFileIo.shellQuote("/data/adb/modules/zapret2/config.sh"))
    }

    @Test
    fun shellQuote_escapesEveryEmbeddedSingleQuote() {
        assertEquals("'a'\\''b'\\''c'", RootFileIo.shellQuote("a'b'c"))
    }

    @Test
    fun simpleFileName_acceptsSafeNamesAndCaseInsensitiveSuffix() {
        assertTrue(RootFileIo.isSimpleFileName("preset-main.sh", ".sh"))
        assertTrue(RootFileIo.isSimpleFileName("CUSTOM.TXT", ".txt"))
        assertTrue(RootFileIo.isSimpleFileName("name with spaces.txt", ".txt"))
    }

    @Test
    fun simpleFileName_rejectsTraversalSeparatorsControlsAndWrongSuffix() {
        val invalidNames = listOf(
            "",
            "   ",
            ".",
            "..",
            "../preset.sh",
            "nested/preset.sh",
            "nested\\preset.sh",
            "quoted'preset.sh",
            "quoted\"preset.sh",
            "line\nbreak.sh",
            "tab\tname.sh",
            "delete\u007fname.sh",
            "preset.txt",
            "a".repeat(256) + ".sh",
        )

        invalidNames.forEach { name ->
            assertFalse("Expected unsafe name to be rejected: $name", RootFileIo.isSimpleFileName(name, ".sh"))
        }
    }

    @Test
    fun simpleFileName_rejectsBoundaryWhitespaceButKeepsInternalSpaces() {
        assertTrue(RootFileIo.isSimpleFileName("name with spaces.txt", ".txt"))

        listOf(
            " leading.txt",
            "trailing.txt ",
            "\tleading.txt",
            "trailing.txt\t",
        ).forEach { name ->
            assertFalse(
                "Expected boundary whitespace to be rejected: [$name]",
                RootFileIo.isSimpleFileName(name, ".txt"),
            )
        }
    }

    @Test
    fun simpleFileName_enforcesTheFilesystemUtf8ByteLimit() {
        assertTrue(RootFileIo.isSimpleFileName("a".repeat(251) + ".txt", ".txt"))
        assertTrue(RootFileIo.isSimpleFileName("\u044f".repeat(125) + ".txt", ".txt"))
        assertFalse(RootFileIo.isSimpleFileName("\u044f".repeat(126) + ".txt", ".txt"))
    }

    @Test
    fun directChildPath_acceptsOnlyOneSafeChildBelowExactParent() {
        val parent = "/data/adb/modules/zapret2/zapret2/lists"

        assertTrue(RootFileIo.isDirectChildPath("$parent/youtube.txt", parent, ".txt"))
        assertTrue(RootFileIo.isDirectChildPath("$parent/discord.TXT", "$parent/", ".txt"))

        val invalidPaths = listOf(
            parent,
            "$parent/",
            "$parent/../config.sh",
            "$parent/nested/file.txt",
            "$parent\\file.txt",
            "/data/adb/modules/zapret2/zapret2/lists-copy/file.txt",
            "$parent/file.sh",
        )
        invalidPaths.forEach { path ->
            assertFalse("Expected non-child path to be rejected: $path", RootFileIo.isDirectChildPath(path, parent, ".txt"))
        }
    }

    @Test
    fun privilegedWritersFailBeforeShellWhenCoordinatorOwnershipIsMissing() {
        assertThrows(IllegalStateException::class.java) {
            RootFileIo.ensureDirectory("/data/adb/modules/zapret2/zapret2/lists")
        }
        assertThrows(IllegalStateException::class.java) {
            RootFileIo.removeFile("/data/adb/modules/zapret2/zapret2/lists/test.txt")
        }
        assertThrows(IllegalStateException::class.java) {
            RootFileIo.writeTextAtomically(
                "/data/adb/modules/zapret2/zapret2/lists/test.txt",
                "example.org",
                "__TEST_EOF__",
            )
        }
    }
}
