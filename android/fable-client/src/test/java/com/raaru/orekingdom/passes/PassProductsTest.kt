package com.raaru.orekingdom.passes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassProductsTest {
    @Test
    fun productsAreTheUnifiedListInOrder() {
        assertEquals(listOf("member", "operator", "syndicate", "prestige"), PassCopy.PRODUCTS.map { it.id })
    }

    @Test
    fun memberOptionsMatchTheModel() {
        val member = PassCopy.PRODUCTS[0]
        assertEquals(2, member.options.size)
        assertEquals("7d", member.options[0].id)
        assertEquals("$7 / 7 days", member.options[0].price)
        assertEquals("$4.90", member.options[0].ore)
        assertEquals("30d", member.options[1].id)
        assertEquals("$20 / 30 days", member.options[1].price)
        assertEquals("$14.00", member.options[1].ore)
    }

    @Test
    fun operatorOptionsMatchTheModel() {
        val operator = PassCopy.PRODUCTS[1]
        assertEquals(2, operator.options.size)
        assertEquals("$49 / 7 days", operator.options[0].price)
        assertEquals("$34.30", operator.options[0].ore)
        assertEquals("$99 / 30 days", operator.options[1].price)
        assertEquals("$69.30", operator.options[1].ore)
    }

    @Test
    fun operatorIncludesNamesEveryEntitlement() {
        assertEquals("Includes unlimited Pump Days, the ATM unlock, and Nightshift intel", PassCopy.PRODUCTS[1].includes)
    }

    @Test
    fun syndicateIsMonthlyOnly() {
        val syndicate = PassCopy.PRODUCTS[2]
        assertEquals(1, syndicate.options.size)
        assertEquals("30d", syndicate.options[0].id)
        assertEquals("$299 / 30 days", syndicate.options[0].price)
        assertEquals("$209.30", syndicate.options[0].ore)
    }

    @Test
    fun prestigeIsTheLifetimeSeat() {
        val prestige = PassCopy.PRODUCTS[3]
        assertTrue(prestige.prestige)
        assertEquals(1, prestige.options.size)
        assertEquals("lifetime", prestige.options[0].id)
        assertEquals("10,000 SKR — one-time, lifetime", prestige.options[0].price)
        assertNull(prestige.options[0].ore)
        assertEquals("of 100 seats remaining", PassCopy.SEATS_SUFFIX)
    }

    @Test
    fun standingCopyCarriesTheModelRules() {
        assertEquals("Nothing auto-renews. Every period is a fresh, deliberate purchase.", PassCopy.NO_AUTO_RENEW)
        assertEquals("Reading is free forever.", PassCopy.FREE_FOREVER)
    }
}
