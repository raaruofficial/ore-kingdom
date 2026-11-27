package com.raaru.orekingdom.passes

import org.junit.Assert.assertEquals
import org.junit.Test

class CrownDisplayStateTest {
    @Test
    fun defaultsAreLoadingWithNoHolder() {
        val ui = CrownDisplayUi()
        assertEquals(CrownStatus.Loading, ui.status)
        assertEquals(null, ui.holderName)
        assertEquals(null, ui.month)
    }

    @Test
    fun holderAndMonthPassThroughVerbatim() {
        val ui = CrownDisplayUi(
            status = CrownStatus.Ready,
            holderName = "satoshi_ore",
            month = "November 2026"
        )
        assertEquals(CrownStatus.Ready, ui.status)
        assertEquals("satoshi_ore", ui.holderName)
        assertEquals("November 2026", ui.month)
    }

    @Test
    fun errorStateCarriesNoHolder() {
        val ui = CrownDisplayUi(status = CrownStatus.Error)
        assertEquals(null, ui.holderName)
    }
}
