package com.raaru.orekingdom.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleDataTest {
    @Test
    fun everySampleStateConstructs() {
        val samples = listOf(
            SampleData.railState,
            SampleData.spotlightVacant,
            SampleData.spotlightOccupied,
            SampleData.modules,
            SampleData.channelState,
            SampleData.memberListState,
            SampleData.profileState,
            SampleData.entitlements,
            SampleData.modQueueState,
            SampleData.pumpState,
            SampleData.nightshiftState,
            SampleData.passState
        )
        assertEquals(12, samples.size)
        assertFalse(samples.any { it == null })
    }

    @Test
    fun sampleRailHasCategoriesAndChannels() {
        assertTrue(SampleData.railState.categories.isNotEmpty())
        assertTrue(SampleData.railState.categories.all { it.channels.isNotEmpty() })
    }

    @Test
    fun sampleMemberIdsAreUnique() {
        val ids = SampleData.memberListState.members.map { it.userId }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun sampleModulesCoverPumpAndNightshift() {
        val routes = SampleData.modules.map { it.route }
        assertTrue("pump" in routes)
        assertTrue("nightshift" in routes)
    }

    @Test
    fun noPriceStringsInSampleData() {
        val combined = listOf(
            SampleData.railState,
            SampleData.channelState,
            SampleData.memberListState,
            SampleData.profileState,
            SampleData.modQueueState,
            SampleData.pumpState,
            SampleData.nightshiftState,
            SampleData.passState,
            SampleData.spotlightVacant,
            SampleData.spotlightOccupied
        ).joinToString(separator = "") { it.toString() }
        assertFalse(combined.contains('$'))
    }
}
