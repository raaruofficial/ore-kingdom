package com.raaru.orekingdom.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Entitlement lapse paths (DeepSeek programme item 5): the push-driven
 * fail-closed clear, the pendingGone unsubscribe debt, and failure
 * behaviour of the periodic read. Supervisor-authored (see the
 * programme record); the harness mirrors EntitlementStoreTest with a
 * transport that can be told to fail individual reads.
 */
class EntitlementStoreLapseTest {

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

    private fun ent(post: List<String> = emptyList()) =
        Entitlements("member", caps(post), null, false, emptyMap())

    private fun ch(id: String, cat: String, kind: String = "text", minPost: String = "member") =
        ChannelDto(id, cat, id.removePrefix("ch_"), kind, 0, "free", minPost, 0)

    private class FakeTransport(
        var entitlements: Entitlements,
        var channels: List<ChannelDto>,
        var spotlight: SpotlightDto,
    ) : ShellTransport {
        val pushes = MutableSharedFlow<EntitlementPush>(extraBufferCapacity = 8)
        val unsubscribed = mutableListOf<List<String>>()
        var failEntitlements = false
        var failChannels = false
        var entitlementCalls = 0
        var channelCalls = 0
        override suspend fun getEntitlements(): Entitlements {
            entitlementCalls++
            if (failEntitlements) throw IllegalStateException("read failed")
            return entitlements
        }
        override suspend fun getChannels(): List<ChannelDto> {
            channelCalls++
            if (failChannels) throw IllegalStateException("read failed")
            return channels
        }
        override suspend fun getSpotlight() = spotlight
        override val entitlementPushes = pushes
        override suspend fun unsubscribe(channelIds: List<String>) {
            unsubscribed += channelIds
        }
    }

    private val vacant = SpotlightDto(false, null, null, 50)

    private fun channels() = listOf(
        ch("ch_announcements", "TOWN CRIER", minPost = "house"),
        ch("ch_general", "THE FLOOR"),
        ch("ch_pump", "THE PUMP", kind = "module"),
        ch("ch_help", "THE FLOOR"),
    )

    private fun started(t: FakeTransport): EntitlementStore {
        val s = EntitlementStore(t, scope, InMemoryLastVisitedStore())
        s.start()
        runBlocking { withTimeout(5_000) { s.rail.first { it.status == RailStatus.Ready } } }
        return s
    }

    private fun floorIds(s: EntitlementStore): List<String> =
        s.rail.value.categories.firstOrNull { it.title == "THE FLOOR" }
            ?.channels?.map { it.id } ?: emptyList()

    @Test
    fun lapsePushUnsubscribesDroppedChannels() {
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s = started(t)
        assertEquals(listOf("ch_general", "ch_help"), floorIds(s))
        t.channels = channels().filter { it.id != "ch_help" }
        t.pushes.tryEmit(EntitlementPush(ent(listOf("ch_general"))))
        runBlocking {
            withTimeout(5_000) { s.rail.first { r -> r.categories.flatMap { it.channels }.none { it.id == "ch_help" } } }
        }
        assertEquals(listOf("ch_general"), floorIds(s))
        assertEquals(listOf(listOf("ch_help")), t.unsubscribed)
    }

    @Test
    fun lapsePushWithFailingRefetchHidesChannelsAndOwesTheUnsubscribe() {
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s = started(t)
        t.channels = channels().filter { it.id != "ch_help" }
        t.failChannels = true
        t.pushes.tryEmit(EntitlementPush(ent(emptyList())))
        // Fail-closed: a push whose refetch fails leaves absence, not the
        // stale gated channels — and no unsubscribe is sent yet.
        runBlocking {
            withTimeout(5_000) { s.rail.first { it.categories.isEmpty() } }
        }
        assertEquals(RailStatus.Ready, s.rail.value.status)
        assertTrue(t.unsubscribed.isEmpty())
        // The next successful read pays the pendingGone debt.
        t.failChannels = false
        s.refresh()
        runBlocking {
            withTimeout(5_000) { s.rail.first { r -> r.categories.flatMap { it.channels }.map { it.id }.contains("ch_general") } }
        }
        assertEquals(listOf("ch_general"), floorIds(s))
        assertEquals(listOf(listOf("ch_help")), t.unsubscribed)
    }

    @Test
    fun pushCarryingChannelsAppliesThemWithoutRefetch() {
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s = started(t)
        val callsAfterBootstrap = t.channelCalls
        // If the store refetched now it would see this list (ch_general
        // gone); the list the push carries is the one that must land.
        t.channels = channels().filter { it.id != "ch_general" }
        val pushed = channels().filter { it.id != "ch_help" }
        t.pushes.tryEmit(EntitlementPush(ent(listOf("ch_general")), pushed))
        runBlocking {
            withTimeout(5_000) { s.rail.first { r -> r.categories.flatMap { it.channels }.none { it.id == "ch_help" } } }
        }
        assertEquals(listOf("ch_general"), floorIds(s))
        assertEquals(listOf(listOf("ch_help")), t.unsubscribed)
        assertEquals(callsAfterBootstrap, t.channelCalls)
    }

    @Test
    fun pushCarryingChannelsSettlesAnOwedUnsubscribe() {
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s = started(t)
        t.failChannels = true
        t.pushes.tryEmit(EntitlementPush(ent(emptyList())))
        // Fail-closed, as in the refetch test: absence, debt owed, no send.
        runBlocking {
            withTimeout(5_000) { s.rail.first { it.categories.isEmpty() } }
        }
        assertTrue(t.unsubscribed.isEmpty())
        // Channel reads are still failing — but a push carrying the list
        // pays the pendingGone debt itself, with no refetch involved.
        val pushed = channels().filter { it.id != "ch_help" }
        t.pushes.tryEmit(EntitlementPush(ent(listOf("ch_general")), pushed))
        runBlocking {
            withTimeout(5_000) { s.rail.first { r -> r.categories.flatMap { it.channels }.map { it.id }.contains("ch_general") } }
        }
        assertEquals(listOf("ch_general"), floorIds(s))
        assertEquals(listOf(listOf("ch_help")), t.unsubscribed)
    }

    @Test
    fun transientRefreshFailureKeepsTheLoadedRail() {
        val t = FakeTransport(ent(listOf("ch_general")), channels(), vacant)
        val s = started(t)
        val callsBefore = t.entitlementCalls
        t.failEntitlements = true
        s.refresh()
        runBlocking {
            withTimeout(5_000) {
                while (t.entitlementCalls <= callsBefore) delay(10)
            }
        }
        // A failed re-read after a successful bootstrap changes nothing:
        // same channels, still Ready (never blanked, never an error).
        assertEquals(RailStatus.Ready, s.rail.value.status)
        assertEquals(listOf("ch_general", "ch_help"), floorIds(s))
    }

    @Test
    fun bootstrapFailureShowsError() {
        val t = FakeTransport(ent(), channels(), vacant)
        t.failEntitlements = true
        val s = EntitlementStore(t, scope, InMemoryLastVisitedStore())
        s.start()
        runBlocking {
            withTimeout(5_000) { s.rail.first { it.status == RailStatus.Error } }
        }
    }
}
