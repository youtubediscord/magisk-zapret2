package com.zapret2.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategyRepositoryTest {

    @Test
    fun categoryValidationProtocol_requiresOneExactSuccessRecord() {
        assertTrue(StrategyRepository.parseCategoryValidation(listOf("Z2_CATEGORIES\tOK")))
        assertTrue(!StrategyRepository.parseCategoryValidation(emptyList()))
        assertTrue(!StrategyRepository.parseCategoryValidation(listOf("Z2_CATEGORIES\tOK", "extra")))
        assertTrue(!StrategyRepository.parseCategoryValidation(listOf("Z2_CATEGORIES\tFAILED")))
    }

    @Test
    fun strategyParser_requiresUniqueSectionsKeysAndNonEmptyArgs() {
        val valid = StrategyRepository.parseStrategyDetailsContent(
            """
                [default]
                desc=Default
                args=--lua-desync=default

                [custom]
                desc=Custom
                args=--lua-desync=custom
                future_field=kept-for-forward-compatibility

                [disabled]
                desc=Disabled sentinel
                args=
            """.trimIndent(),
        )
        assertEquals(listOf("disabled", "default", "custom"), valid?.map { it.id })

        val quoted = StrategyRepository.parseStrategyDetailsContent(
            "[quoted]\ndesc='Quoted description'\nargs=\"--lua-desync=quoted\"",
        )
        assertEquals("Quoted description", quoted?.lastOrNull()?.description)
        assertEquals("--lua-desync=quoted", quoted?.lastOrNull()?.args)

        assertNull(
            StrategyRepository.parseStrategyDetailsContent(
                "[one]\nargs=--one\n[one]\nargs=--two",
            ),
        )
        assertNull(
            StrategyRepository.parseStrategyDetailsContent(
                "[one]\nargs=--one\nargs=--two",
            ),
        )
        assertNull(StrategyRepository.parseStrategyDetailsContent("[one]\ndesc=missing args"))
    }

    @Test
    fun categoriesParser_acceptsKnownActiveAndAlternateFilterBindings() {
        val parsed = StrategyRepository.parseCategoriesContent(
            """
                [youtube]
                protocol=tcp
                hostlist=youtube.txt
                ipset=ipset-youtube.txt
                filter_mode=hostlist
                strategy=example_strategy
            """.trimIndent(),
        )

        assertEquals(setOf("youtube"), parsed?.keys)
        assertEquals("example_strategy", parsed?.get("youtube")?.strategy)
        assertTrue(parsed?.get("youtube")?.canSwitchFilterMode == true)

        val quoted = StrategyRepository.parseCategoriesContent(
            "[quoted]\nprotocol='tcp'\nhostlist=\"quoted.txt\"\nfilter_mode='hostlist'\nstrategy=\"disabled\"",
        )
        assertEquals("quoted.txt", quoted?.get("quoted")?.hostlistFile)
    }

    @Test
    fun categoriesParser_rejectsDuplicateSectionsAndDuplicateKnownOrUnknownKeys() {
        val validSection = """
            protocol=tcp
            filter_mode=none
            strategy=disabled
        """.trimIndent()

        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\n$validSection\n[one]\n$validSection",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\n$validSection\nstrategy=another",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\n$validSection\nfuture=1\nfuture=2",
            ),
        )
    }

    @Test
    fun categoriesParser_rejectsMissingRequiredFieldsAndUnsafeValues() {
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nfilter_mode=none\nstrategy=disabled",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=tcp\nstrategy=disabled",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=tcp\nfilter_mode=none",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=tcp\nhostlist=../escape.txt\nfilter_mode=hostlist\nstrategy=disabled",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=invalid\nfilter_mode=none\nstrategy=disabled",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=tcp\nfilter_mode=none\nstrategy=",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=tcp\nhostlist_domains=good.example,bad value\nfilter_mode=hostlist-domains\nstrategy=example",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=tcp\nfilter_mode=none\nstrategy=disabled\nfuture_field=future-value",
            ),
        )
        assertNull(
            StrategyRepository.parseCategoriesContent(
                "[one]\nprotocol=\"tcp\nfilter_mode=none\nstrategy=disabled",
            ),
        )
    }
}
