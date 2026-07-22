package com.zapret2.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorInputBudgetTest {

    @Test
    fun utf8Budget_countsAsciiMultibyteAndSupplementaryText() {
        fun fits(value: String): Boolean = fitsNormalizedEditorBudget(
            value = value,
            maxBytes = 8,
            trimTrailingNewlines = false,
            appendTrailingNewline = false,
        )

        assertTrue(fits("a".repeat(8)))
        assertFalse(fits("a".repeat(9)))
        assertTrue(fits("я".repeat(4)))
        assertFalse(fits("я".repeat(5)))
        assertTrue(fits("😀".repeat(2)))
        assertFalse(fits("😀".repeat(3)))
    }

    @Test
    fun normalizedBudget_matchesCanonicalLineEndingRules() {
        assertTrue(
            fitsNormalizedEditorBudget(
                "1234567\r\n",
                maxBytes = 8,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            ),
        )
        assertFalse(
            fitsNormalizedEditorBudget(
                "12345678\n",
                maxBytes = 8,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            ),
        )
        assertTrue(
            fitsNormalizedEditorBudget(
                "12\r\n34\r56",
                maxBytes = 8,
                trimTrailingNewlines = false,
                appendTrailingNewline = false,
            ),
        )
        assertFalse(
            fitsNormalizedEditorBudget(
                "",
                maxBytes = 0,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            ),
        )
    }

    @Test
    fun commandBudget_countsThePersistedFinalNewline() {
        assertTrue(
            fitsNormalizedEditorBudget(
                "1234567\n",
                maxBytes = 8,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            ),
        )
        assertFalse(
            fitsNormalizedEditorBudget(
                "12345678",
                maxBytes = 8,
                trimTrailingNewlines = true,
                appendTrailingNewline = true,
            ),
        )
    }
}
