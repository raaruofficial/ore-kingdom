package com.raaru.orekingdom.channel

import com.raaru.orekingdom.designsystem.components.OreRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins for the extracted search-hit filter (DeepSeek full-spend
 * programme Q-15): visibleSearchHits drops hits outside the readable
 * set, dedupes by id first-wins, and never reorders.
 */
class SearchFilterTest {

    private fun threadHit(id: String, channelId: String, title: String = "title-$id"): SearchThreadHitUi =
        SearchThreadHitUi(id = id, channelId = channelId, title = title)

    private fun messageHit(messageId: String, channelId: String, channelName: String? = null): SearchMessageHitUi =
        SearchMessageHitUi(
            channelId = channelId,
            channelName = channelName,
            message = ChannelMessageUi(
                id = messageId,
                seq = null,
                authorName = "Author",
                authorRole = OreRole.Free,
                body = "body-$messageId",
                timestamp = "10:00",
            ),
        )

    @Test
    fun hitsOutsideTheReadableSetAreDropped() {
        val state = SearchUiState(threads = listOf(threadHit("t1", "ch_a"), threadHit("t2", "ch_b")), messages = listOf(messageHit("m1", "ch_a"), messageHit("m2", "ch_b")), readableChannelIds = setOf("ch_a"))
        val (threads, messages) = visibleSearchHits(state)
        assertEquals(listOf("t1"), threads.map { it.id })
        assertEquals(listOf("m1"), messages.map { it.message.id })
    }

    @Test
    fun duplicateIdsKeepTheFirstOccurrence() {
        val state = SearchUiState(threads = listOf(threadHit("t1", "ch_a", "first"), threadHit("t1", "ch_a", "second")), messages = listOf(messageHit("m1", "ch_a", "first"), messageHit("m1", "ch_a", "second")), readableChannelIds = setOf("ch_a"))
        val (threads, messages) = visibleSearchHits(state)
        assertEquals("first", threads.single().title)
        assertEquals("first", messages.single().channelName)
    }

    @Test
    fun emptyReadableSetDropsEverything() {
        val state = SearchUiState(threads = listOf(threadHit("t1", "ch_a")), messages = listOf(messageHit("m1", "ch_a")), readableChannelIds = emptySet())
        val (threads, messages) = visibleSearchHits(state)
        assertTrue(threads.isEmpty())
        assertTrue(messages.isEmpty())
    }

    @Test
    fun filteringNeverReorders() {
        val state = SearchUiState(threads = listOf(threadHit("t3", "ch_a"), threadHit("t1", "ch_a"), threadHit("t2", "ch_a")), messages = listOf(messageHit("m2", "ch_a"), messageHit("m1", "ch_a")), readableChannelIds = setOf("ch_a"))
        val (threads, messages) = visibleSearchHits(state)
        assertEquals(listOf("t3", "t1", "t2"), threads.map { it.id })
        assertEquals(listOf("m2", "m1"), messages.map { it.message.id })
    }

    @Test
    fun sameIdSurvivesInBothListsIndependently() {
        val state = SearchUiState(threads = listOf(threadHit("shared", "ch_a")), messages = listOf(messageHit("shared", "ch_a")), readableChannelIds = setOf("ch_a"))
        val (threads, messages) = visibleSearchHits(state)
        assertEquals(listOf("shared"), threads.map { it.id })
        assertEquals(listOf("shared"), messages.map { it.message.id })
    }
}
