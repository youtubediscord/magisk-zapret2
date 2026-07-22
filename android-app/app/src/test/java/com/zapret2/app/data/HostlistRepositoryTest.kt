package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostlistRepositoryTest {
    private val directory = HostlistRepository.LISTS_DIR

    @Test
    fun detailRoute_derivesOnlyBoundedDirectChildPathsFromFileNames() {
        val repository = HostlistRepository()

        assertEquals("$directory/example.txt", repository.pathForFileName("example.txt"))
        assertNull(repository.pathForFileName("../example.txt"))
        assertNull(repository.pathForFileName("bad name.txt"))
        assertNull(repository.pathForFileName(".hidden.txt"))
        assertNull(repository.pathForFileName("example.conf"))
        assertEquals("$directory/example.TXT", repository.pathForFileName("example.TXT"))
        assertTrue(repository.isAllowedPath("$directory/example.TXT"))
    }

    @Test
    fun successfulConditionalWriteCarriesTheExactPersistedEditorBaseline() {
        val persisted = "example.org\n"

        val outcome = HostlistConditionalWriteOutcome.Written(persisted)

        assertEquals(persisted, outcome.persistedContent)
    }

    @Test
    fun recordParser_acceptsOnlyExactBoundedDirectChildren() {
        assertEquals(
            HostlistFileRecord("empty.txt", "$directory/empty.txt", 0, 0),
            parseHostlistRecord("empty.txt|$directory/empty.txt|0|0"),
        )
        assertEquals(
            HostlistFileRecord("large.txt", "$directory/large.txt", 200, 4096),
            parseHostlistRecord("large.txt|$directory/large.txt|200|4096"),
        )
        assertEquals(
            HostlistFileRecord("upper.TXT", "$directory/upper.TXT", 4, 10),
            parseHostlistRecord("upper.TXT|$directory/upper.TXT|4|10"),
        )

        listOf(
            "",
            "missing-fields",
            "negative.txt|$directory/negative.txt|-1|50",
            "bad-size.txt|$directory/bad-size.txt|4|unknown",
            "escape.txt|$directory/../escape.txt|4|10",
            "mismatch.txt|$directory/other.txt|4|10",
            "pipe|name.txt|$directory/pipe|name.txt|4|10",
            "large.txt|$directory/large.txt|4|${HostlistRepository.MAX_HOSTLIST_BYTES.toLong() + 1}",
        ).forEach { line -> assertNull("Expected rejection: $line", parseHostlistRecord(line)) }
    }

    @Test
    fun dataLineCount_ignoresWhitespaceAndComments() {
        assertEquals(
            3,
            countHostlistDataLines(
                """
                    # heading

                    example.com
                      # indented comment
                    *.video.example
                    2001:db8::/32
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun editorValidation_matchesDomainMasksAndIpSetFiles() {
        assertTrue(
            isValidHostlistContent(
                "custom.txt",
                "# supported masks\n*.example.com\nscontent-*.cdninstagram.com\n",
            ),
        )
        assertTrue(
            isValidHostlistContent(
                "ipset-custom.txt",
                "# networks\n192.0.2.0/24\n2001:db8::/32\n",
            ),
        )
        assertFalse(isValidHostlistContent("custom.txt", "# comments only\n"))
        assertFalse(isValidHostlistContent("custom.txt", "valid.example\nbad host\n"))
        assertFalse(isValidHostlistContent("ipset-custom.txt", "example.com\n"))
        assertFalse(isValidHostlistContent("ipset-custom.txt", "192.0.2.0/99\n"))
    }
}
