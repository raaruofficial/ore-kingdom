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

class RailDerivationEdgeTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())

    @After
    fun tearDown() {
        scope.coroutineContext[Job]?.cancel()
    }

    private fun caps() = Capabilities(
        post = emptyList(),
        react = emptyList(),
        tip = false,
        gift = false,
        commercialPost = false,
        spotlightBid = false,
        pumpModule = "none",
        pumpDayIncluded = false,
        atmUnlockIncluded = false,
        nightshiftConsole = false,
        autopilotSurface = false,
        modLoft = false
    )

    private fun ent() = Entitlements("free", caps(), null, false, emptyMap())

    private fun ch(
        id: String,
        cat: String,
        name: String = id.removePrefix("ch_"),
        kind: String = "text",
        position: Int = 0
    ) = ChannelDto(id, cat, name, kind, 0, "free", "member", position)

    private val vacant = SpotlightDto(false, null, null, 50)

    private fun started(channels: List<ChannelDto>): EntitlementStore {
        val store = EntitlementStore(
            FakeTransport(ent(), channels, vacant),
            scope,
            InMemoryLastVisitedStore()
        )
        store.start()
        runBlocking { withTimeout(5_000) { store.rail.first { it.status == RailStatus.Ready } } }
        return store
    }

    private class FakeTransport(
        var entitlements: Entitlements,
        var channels: List<ChannelDto>,
        var spotlight: SpotlightDto
    ) : ShellTransport {
        val pushes = MutableSharedFlow<Entitlements>(extraBufferCapacity = 8)
        override suspend fun getEntitlements(): Entitlements = entitlements
        override suspend fun getChannels(): List<ChannelDto> = channels
        override suspend fun getSpotlight(): SpotlightDto = spotlight
        override val entitlementPushes = pushes
        override suspend fun unsubscribe(channelIds: List<String>) {}
    }

    @Test
    fun unknownKindMapsToStageReserved() {
        val s = started(
            listOf(
                ch("ch_a", "THE FLOOR", kind = "stage"),
                ch("ch_b", "THE FLOOR", kind = "announcement")
            )
        )
        val channels = s.rail.value.categories.flatMap { it.channels }
        assertEquals(2, channels.size)
        assertTrue(channels.all { it.kind == ChannelKind.StageReserved })
    }

    @Test
    fun systemKindMapsToSystem() {
        val s = started(
            listOf(
                ch("ch_system", "THE FLOOR", kind = "system"),
                ch("ch_text", "THE FLOOR", kind = "text")
            )
        )
        val channels = s.rail.value.categories.flatMap { it.channels }
        assertEquals(ChannelKind.System, channels[0].kind)
        assertEquals(ChannelKind.Text, channels[1].kind)
    }

    @Test
    fun unreadForUnknownChannelNeverRenders() {
        val s = started(listOf(ch("ch_a", "THE FLOOR")))
        s.setUnread("ch_ghost", 5)
        val channels = s.rail.value.categories.flatMap { it.channels }
        assertEquals(1, channels.size)
        assertEquals(0, channels[0].unreadCount)
    }

    @Test
    fun blankCategoryGroupsUnderEmptyTitle() {
        val s = started(listOf(ch("ch_a", "")))
        val categories = s.rail.value.categories
        assertEquals(1, categories.size)
        assertEquals("", categories[0].title)
    }

    @Test
    fun duplicateChannelIdsAreNotDedupedByTheStore() {
        val s = started(
            listOf(
                ch("ch_a", "THE FLOOR"),
                ch("ch_a", "THE FLOOR")
            )
        )
        val channels = s.rail.value.categories.flatMap { it.channels }
        // the store does not dedupe; duplicate DTO ids are a host-contract violation the rail would render twice
        assertEquals(2, channels.size)
        assertEquals(listOf("ch_a", "ch_a"), channels.map { it.id })
    }
}
