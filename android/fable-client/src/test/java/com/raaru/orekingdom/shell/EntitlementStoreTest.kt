package com.raaru.orekingdom.shell

import com.raaru.orekingdom.designsystem.components.ComposerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementStoreTest {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun caps(post: List<String> = emptyList()) = Capabilities(
        post = post, react = post, tip = false, gift = false, commercialPost = false,
        spotlightBid = false, pumpModule = "none", pumpDayIncluded = false,
        atmUnlockIncluded = false, nightshiftConsole = false, autopilotSurface = false, modLoft = false,
    )

    private fun ent(post: List<String> = emptyList(), modules: Map<String, String> = emptyMap()) =
        Entitlements("free", caps(post), null, false, modules)

    private fun ch(id: String, cat: String, kind: String = "text", minPost: String = "member", pos: Int = 0) =
        ChannelDto(id, cat, id.removePrefix("ch_"), kind, 0, "free", minPost, pos)

    private class FakeTransport(
        var entitlements: Entitlements,
        var channels: List<ChannelDto>,
        var spotlight: SpotlightDto,
    ) : ShellTransport {
        val pushes = MutableSharedFlow<Entitlements>(extraBufferCapacity = 8)
        val unsubscribed = mutableListOf<List<String>>()
        override suspend fun getEntitlements() = entitlements
        override suspend fun getChannels() = channels
        override suspend fun getSpotlight() = spotlight
        override val entitlementPushes = pushes
        override suspend fun unsubscribe(channelIds: List<String>) { unsubscribed += channelIds }
    }

    private val vacant = SpotlightDto(false, null, null, 50)

    private fun channels() = listOf(
        ch("ch_announcements", "TOWN CRIER", minPost = "house"),
        ch("ch_general", "THE FLOOR"),
        ch("ch_pump", "THE PUMP", kind = "module"),
        ch("ch_help", "THE FLOOR"),
    )

    private fun started(t: FakeTransport, lv: LastVisitedStore = InMemoryLastVisitedStore()): EntitlementStore {
        val s = EntitlementStore(t, scope, lv)
        s.start()
        runBlocking { withTimeout(5_000) { s.rail.first { it.status == RailStatus.Ready } } }
        return s
    }

    @Test
    fun bootstrapPublishesReadyRailInServerOrderWithoutModuleChannels() {
        val s = started(FakeTransport(ent(listOf("ch_general")), channels(), vacant))
        val rail = s.rail.value
        assertEquals(listOf("TOWN CRIER", "THE FLOOR"), rail.categories.map { it.title })
        assertEquals(listOf("ch_general", "ch_help"), rail.categories[1].channels.map { it.id })
        assertTrue(rail.categories.flatMap { it.channels }.none { it.id == "ch_pump" })
        assertEquals(ComposerState.Enabled, rail.categories[1].channels[0].composer)
    }

    @Test
    fun duplicateChannelDtoIdsAreDedupedFirstWins() {
        // P-5: a repeated id in the host payload must not reach the rail —
        // ChannelRail keys rows channel:<id>, so a duplicate crashes the list.
        val dupes = channels() + ch("ch_general", "THE FLOOR")
        val s = started(FakeTransport(ent(listOf("ch_general")), dupes, vacant))
        val ids = s.rail.value.categories.flatMap { it.channels }.map { it.id }
        assertEquals(listOf("ch_general", "ch_help"), ids)

        // Same on the push path: a lapsed push carrying a duplicate id.
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s2 = started(t)
        t.channels = channels() + ch("ch_help", "THE FLOOR")
        t.pushes.tryEmit(ent(listOf("ch_general", "ch_help")))
        runBlocking {
            withTimeout(5_000) {
                s2.rail.first { r ->
                    r.categories.flatMap { it.channels }.count { it.id == "ch_help" } == 1 &&
                        r.categories[1].channels[1].composer == ComposerState.Enabled
                }
            }
        }
    }

    @Test
    fun composerStatesFollowCapabilitiesNotRoleNames() {
        val s = started(FakeTransport(ent(listOf("ch_general")), channels(), vacant))
        val all = s.rail.value.categories.flatMap { it.channels }.associateBy { it.id }
        assertTrue(all.getValue("ch_announcements").composer is ComposerState.Disabled)
        assertTrue(all.getValue("ch_help").composer is ComposerState.Upsell)
    }

    @Test
    fun selectChannelHonoursLastVisitedAndIgnoresUnknownAndModule() {
        val lv = InMemoryLastVisitedStore()
        val s = started(FakeTransport(ent(), channels(), vacant), lv)
        assertNull(s.rail.value.lastVisitedChannelId)
        s.selectChannel("ch_help")
        assertEquals("ch_help", lv.get())
        assertEquals("ch_help", s.rail.value.lastVisitedChannelId)
        s.selectChannel("nope")
        s.selectChannel("ch_pump")
        assertEquals("ch_help", lv.get())
        assertEquals("ch_help", s.rail.value.lastVisitedChannelId)
    }

    @Test
    fun staleLastVisitedIsNotSurfaced() {
        val lv = InMemoryLastVisitedStore().apply { set("ch_gone") }
        val s = started(FakeTransport(ent(), channels(), vacant), lv)
        assertNull(s.rail.value.lastVisitedChannelId)
    }

    @Test
    fun setUnreadClampsNegativesAndPublishes() {
        val s = started(FakeTransport(ent(), channels(), vacant))
        s.setUnread("ch_general", 7)
        assertEquals(7, s.rail.value.categories[1].channels[0].unreadCount)
        s.setUnread("ch_general", -3)
        assertEquals(0, s.rail.value.categories[1].channels[0].unreadCount)
    }

    @Test
    fun spotlightMapsOccupiedAndVacant() {
        val occupied = SpotlightDto(true, SpotlightPostDto("u_1", "hi", true), "2026-12-01T00:00:00Z", 50)
        val s = started(FakeTransport(ent(), channels(), occupied))
        val ui = s.spotlight.value as SpotlightUiState.Occupied
        assertEquals("u_1", ui.authorId)
        assertEquals(50, ui.floorUsd)
        assertTrue(ui.commercial)
        assertNull(ui.authorName)

        val s2 = started(FakeTransport(ent(), channels(), vacant))
        assertEquals(SpotlightUiState.Vacant(50), s2.spotlight.value)
    }

    @Test
    fun occupiedWithoutPostOrWindowFallsBackToVacant() {
        val bad = SpotlightDto(true, null, null, 50)
        val s = started(FakeTransport(ent(), channels(), bad))
        assertEquals(SpotlightUiState.Vacant(50), s.spotlight.value)
    }

    @Test
    fun entitlementPushRederivesRailWithoutRestart() {
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s = started(t)
        assertTrue(s.rail.value.categories[1].channels[1].composer is ComposerState.Upsell)
        t.pushes.tryEmit(ent(listOf("ch_general", "ch_help")))
        runBlocking {
            withTimeout(5_000) {
                s.rail.first { r -> r.categories.getOrNull(1)?.channels?.getOrNull(1)?.composer == ComposerState.Enabled }
            }
        }
        assertEquals(listOf("ch_general", "ch_help"), s.entitlements.value?.capabilities?.post)
    }

    @Test
    fun pushWithLapsedCapabilitiesDowngradesComposers() {
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s = started(t)
        t.pushes.tryEmit(ent(emptyList()))
        runBlocking {
            withTimeout(5_000) {
                s.rail.first { r -> r.categories.getOrNull(1)?.channels?.getOrNull(0)?.composer is ComposerState.Upsell }
            }
        }
    }
}
