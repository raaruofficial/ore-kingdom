package com.raaru.orekingdom.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceLabelTest {

    @Test
    fun labelsMapToStandingCopy() {
        assertEquals(OreComponentCopy.PresenceOnline, presenceLabel(OrePresence.Online))
        assertEquals(OreComponentCopy.PresenceIdle, presenceLabel(OrePresence.Idle))
        assertEquals(OreComponentCopy.PresenceOffline, presenceLabel(OrePresence.Offline))
    }

    @Test
    fun labelsAreDistinctAndNotBlank() {
        val online = presenceLabel(OrePresence.Online)
        val idle = presenceLabel(OrePresence.Idle)
        val offline = presenceLabel(OrePresence.Offline)
        assertNotEquals(online, idle)
        assertNotEquals(online, offline)
        assertNotEquals(idle, offline)
        assertTrue(online.isNotBlank())
        assertTrue(idle.isNotBlank())
        assertTrue(offline.isNotBlank())
    }

    @Test
    fun presenceHasExactlyThreeEntries() {
        assertEquals(
            listOf(OrePresence.Online, OrePresence.Idle, OrePresence.Offline),
            OrePresence.values().toList(),
        )
    }
}
