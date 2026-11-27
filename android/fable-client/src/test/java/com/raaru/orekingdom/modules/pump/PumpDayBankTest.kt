package com.raaru.orekingdom.modules.pump

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PumpDayBankTest {

    @Test
    fun priceConstantsMatchSectionEightExactly() {
        assertEquals("$10.00", PumpDayBankCopy.PUMP_DAY_USD)
        assertEquals("$7.00", PumpDayBankCopy.PUMP_DAY_ORE)
        assertEquals("+$2.50", PumpDayBankCopy.ATM_DAY_USD)
        assertEquals("$1.75", PumpDayBankCopy.ATM_DAY_ORE)
        assertEquals("$12.50", PumpDayBankCopy.EVERYTHING_DAY_USD)
        assertEquals("$8.75", PumpDayBankCopy.EVERYTHING_DAY_ORE)
    }

    @Test
    fun standingCopyCarriesTheRuledWords() {
        val standing = PumpDayBankCopy.STANDING.lowercase()
        assertTrue(standing.contains("banked"))
        assertTrue(standing.contains("never expire"))
        assertTrue(standing.contains("spent only when the user pumps"))
        assertEquals("First Pump Day free", PumpDayBankCopy.FIRST_DAY_FREE)
        assertTrue(PumpDayBankCopy.UNLIMITED.isNotBlank())
        assertEquals("Included", PumpDayBankCopy.ATM_INCLUDED)
        assertEquals("No charge", PumpDayBankCopy.NO_CHARGE)
    }

    @Test
    fun noForbiddenWordsInCopy() {
        val all = listOf(
            PumpDayBankCopy.STANDING, PumpDayBankCopy.UNLIMITED_NOTE,
            PumpDayBankCopy.FIRST_DAY_FREE_NOTE, PumpDayBankCopy.LINKING,
        ).joinToString(" ").lowercase()
        assertFalse(all.contains("referral"))
        assertFalse(all.contains("per-swap"))
    }

    @Test
    fun dayBankDefaultsAreTheLoadingShape() {
        val ui = PumpDayBankUi()
        assertEquals(PumpStatus.Loading, ui.status)
        assertEquals(0, ui.bankedDays)
        assertNull(ui.activeRemainingSeconds)
        assertFalse(ui.firstDayFree)
        assertFalse(ui.unlimited)
    }

    @Test
    fun atmDefaultsAreTheLoadingShape() {
        val ui = PumpAtmUi()
        assertEquals(PumpStatus.Loading, ui.status)
        assertEquals(PumpAtmState.Locked, ui.state)
    }

    @Test
    fun atmStateHasExactlyThreeValues() {
        assertEquals(
            listOf(PumpAtmState.Locked, PumpAtmState.Unlocked, PumpAtmState.Included),
            PumpAtmState.values().toList(),
        )
    }
}
