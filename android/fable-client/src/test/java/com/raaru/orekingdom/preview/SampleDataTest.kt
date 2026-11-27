package com.raaru.orekingdom.preview

import com.raaru.orekingdom.shell.SpotlightUiState
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
        // The gallery's design rule: sample data carries no price strings. Scan the
        // human-authored sample string fields directly — a toString() sweep over the
        // state objects also sweeps runtime type names, which are not sample data.
        val authored = buildList {
            SampleData.railState.categories.forEach { c ->
                add(c.title); c.channels.forEach { add(it.name) }
            }
            SampleData.channelState.messages.forEach { add(it.body); add(it.authorName) }
            SampleData.memberListState.members.forEach { add(it.displayName); it.activity?.let(::add) }
            add(SampleData.profileState.displayName); SampleData.profileState.activity?.let(::add)
            SampleData.modQueueState.items.forEach { add(it.snapshot.body) }
            SampleData.pumpState.wallets.forEach { add(it.balanceText); add(it.tankStateText) }
            SampleData.nightshiftState.fleet?.let { f ->
                add(f.headline); f.lines.forEach { add(it.label); add(it.value) }
            }
            SampleData.nightshiftState.suggestions.forEach { add(it.title); add(it.body) }
            (SampleData.spotlightOccupied as? SpotlightUiState.Occupied)?.let { add(it.body) }
            SampleData.passState.recipients.forEach { add(it.displayName) }
        }
        assertFalse(authored.joinToString("").contains('$'))
    }
}
