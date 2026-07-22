package com.zapret2.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrategiesOrderStateTest {

    @Test
    fun sanitizePendingOrder_deduplicatesRejectsInvalidAndPinsDisabledFirst() {
        assertEquals(
            listOf("disabled", "alpha", "beta"),
            sanitizePendingStrategyOrder(
                listOf("alpha", "../bad", "beta", "alpha", "disabled", "has space"),
            ),
        )
    }

    @Test
    fun normalizeOrder_dropsUnknownAndAppendsKnownStrategies() {
        assertEquals(
            listOf("disabled", "gamma", "alpha", "beta"),
            normalizeStrategyOrder(
                ids = listOf("gamma", "unknown", "disabled"),
                knownIds = listOf("disabled", "alpha", "beta", "gamma"),
            ),
        )
    }

    @Test
    fun exactOrderMembership_rejectsMissingUnknownAndDuplicateIdentifiers() {
        val catalog = listOf("disabled", "alpha", "beta")

        assertTrue(hasExactStrategyOrderMembership(listOf("beta", "disabled", "alpha"), catalog))
        assertEquals(false, hasExactStrategyOrderMembership(listOf("disabled", "alpha"), catalog))
        assertEquals(
            false,
            hasExactStrategyOrderMembership(listOf("disabled", "alpha", "unknown"), catalog),
        )
        assertEquals(
            false,
            hasExactStrategyOrderMembership(listOf("disabled", "alpha", "alpha", "beta"), catalog),
        )
    }

    @Test
    fun pendingOrder_isBounded() {
        val sanitized = sanitizePendingStrategyOrder(
            (0 until MAX_PENDING_STRATEGY_IDS + 20).map { "strategy_$it" },
        )

        assertEquals(MAX_PENDING_STRATEGY_IDS, sanitized.size)
        assertTrue(sanitized.all(::isValidStrategyIdentifier))
    }

    @Test
    fun pendingOrders_haveACombinedSavedStateBudget() {
        val longIds = (0 until MAX_PENDING_STRATEGY_IDS).map { index ->
            "s".repeat(110) + "_$index"
        }
        val sanitized = sanitizePendingStrategyOrder(longIds)

        assertTrue(sanitized.sumOf { it.length } <= MAX_PENDING_STRATEGY_ORDER_CHARS)
        assertTrue(sanitized.size <= MAX_PENDING_STRATEGY_IDS)
        assertEquals(4, MAX_PENDING_ORDER_CATEGORIES)
        assertEquals(
            64 * 1024,
            MAX_PENDING_STRATEGY_ORDER_CHARS * MAX_PENDING_ORDER_CATEGORIES,
        )
    }

    @Test
    fun pendingOrderRecovery_keepsTheMostRecentValidCategories() {
        assertEquals(
            listOf("category_2", "category_3", "category_4", "category_5"),
            boundedPendingOrderCategoryKeys(
                listOf("category_1", "../invalid", "category_2", "category_3", "category_4", "category_5"),
            ),
        )
    }
}
