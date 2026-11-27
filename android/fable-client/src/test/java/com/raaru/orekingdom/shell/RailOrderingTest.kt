package com.raaru.orekingdom.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailOrderingTest {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun caps() = Capabilities(
        post = emptyList(), react = emptyList(), tip = false, gift = false, commercialPost = false,
        spotlightBid = false, pumpModule = "none", pumpDayIncluded = false,
        atmUnlockIncluded = false, nightshiftConsole = false, autopilotSurface = false, modLoft = false,
    )

    private fun ent() = Entitlements("free", caps(), null, false, emptyMap())

    private fun ch(id: String, cat: String, name: String = id.removePrefix("ch_"), kind: String = "text", position: Int = 0) =
        ChannelDto(id, cat, name, kind, 0, "free", "member", position)

    private class FakeTransport(
        var entitlements: Entitlements,
        var channels: List<ChannelDto>,
        var spotlight: SpotlightDto,
    ) : ShellTransport {
        val pushes = MutableSharedFlow<Entitlements>(extraBufferCapacity = 8)
        override suspend fun getEntitlements() = entitlements
        override suspend fun getChannels() = channels
        override suspend fun getSpotlight(): SpotlightDto = spotlight
        override val entitlementPushes = pushes
        override suspend fun unsubscribe(channelIds: List<String>) {}
    }

    private val vacant = SpotlightDto(false, null, null, 50)

    private fun started(list: List<ChannelDto>): EntitlementStore {
        val t = FakeTransport(ent(), list, vacant)
        val s = EntitlementStore(t, scope, InMemoryLastVisitedStore())
        s.start()
        runBlocking { withTimeout(5_000) { s.rail.first { it.status == RailStatus.Ready } } }
        return s
    }

    @Test
    fun withinCategoryOrderFollowsDtoOrderNotPositionOrName() {
        val s = started(
            listOf(
                ch("ch_c", "THE FLOOR", name = "alpha", position = 2),
                ch("ch_a", "THE FLOOR", name = "zulu", position = 0),
                ch("ch_b", "THE FLOOR", name = "mike", position = 1),
            ),
        )
        val categories = s.rail.value.categories
        assertEquals(1, categories.size)
        assertEquals(listOf("ch_c", "ch_a", "ch_b"), categories[0].channels.map { it.id })
    }

    @Test
    fun categoriesAppearInFirstAppearanceOrder() {
        val s = started(
            listOf(
                ch("ch_1", "ZETA"),
                ch("ch_2", "ALPHA"),
                ch("ch_3", "MID"),
            ),
        )
        assertEquals(listOf("ZETA", "ALPHA", "MID"), s.rail.value.categories.map { it.title })
    }

    @Test
    fun interleavedCategoriesMergeAtFirstAppearance() {
        val s = started(
            listOf(
                ch("ch_a1", "A"),
                ch("ch_b1", "B"),
                ch("ch_a2", "A"),
            ),
        )
        val categories = s.rail.value.categories
        assertEquals(listOf("A", "B"), categories.map { it.title })
        assertEquals(listOf("ch_a1", "ch_a2"), categories[0].channels.map { it.id })
        assertEquals(listOf("ch_b1"), categories[1].channels.map { it.id })
    }

    @Test
    fun moduleKindChannelsNeverReachTheRail() {
        val s = started(
            listOf(
                ch("ch_general", "THE FLOOR"),
                ch("ch_pump", "MODULES", kind = "module"),
            ),
        )
        val ids = s.rail.value.categories.flatMap { it.channels }.map { it.id }
        assertEquals(listOf("ch_general"), ids)
    }

    @Test
    fun unreadCountsLandOnTheRightChannels() {
        val s = started(
            listOf(
                ch("ch_a", "THE FLOOR"),
                ch("ch_b", "THE FLOOR"),
            ),
        )
        s.setUnread("ch_b", 3)
        val channels = s.rail.value.categories.single().channels
        assertEquals(0, channels.first { it.id == "ch_a" }.unreadCount)
        assertEquals(3, channels.first { it.id == "ch_b" }.unreadCount)
    }

    @Test
    fun railIsReadyWithNoLastVisitedByDefault() {
        val s = started(listOf(ch("ch_a", "THE FLOOR")))
        assertEquals(RailStatus.Ready, s.rail.value.status)
        assertTrue(s.rail.value.lastVisitedChannelId == null)
    }
}
