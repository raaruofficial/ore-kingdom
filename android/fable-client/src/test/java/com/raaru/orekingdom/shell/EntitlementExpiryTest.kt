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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntitlementExpiryTest {

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

    private fun ent(role: String, expiresAt: String?, founding: Boolean) =
        Entitlements(role, caps(), expiresAt, founding, emptyMap())

    private class FakeTransport(
        var entitlements: Entitlements,
        var channels: List<ChannelDto>,
        var spotlight: SpotlightDto,
    ) : ShellTransport {
        val pushes = MutableSharedFlow<Entitlements>(extraBufferCapacity = 8)
        override suspend fun getEntitlements() = entitlements
        override suspend fun getChannels() = channels
        override suspend fun getSpotlight() = spotlight
        override val entitlementPushes = pushes
        override suspend fun unsubscribe(channelIds: List<String>) {}
    }

    private val vacant = SpotlightDto(false, null, null, 50)
    private val channels = listOf(ChannelDto("ch_general", "THE FLOOR", "general", "text", 0, "free", "member", 0))

    private fun started(t: FakeTransport): EntitlementStore {
        val s = EntitlementStore(t, scope, InMemoryLastVisitedStore())
        s.start()
        runBlocking { withTimeout(5_000) { s.rail.first { it.status == RailStatus.Ready } } }
        return s
    }

    private fun awaitEntitlements(s: EntitlementStore, expected: Entitlements) {
        runBlocking { withTimeout(5_000) { s.entitlements.first { it == expected } } }
    }

    @Test
    fun bootstrapPublishesExpiryRoleAndFoundingVerbatim() {
        val read = ent("member", "2026-12-24T00:00:00Z", true)
        val s = started(FakeTransport(read, channels, vacant))
        val v = s.entitlements.value!!
        assertEquals("member", v.role)
        assertEquals("2026-12-24T00:00:00Z", v.expiresAt)
        assertTrue(v.founding)
        assertEquals(read, v)
    }

    @Test
    fun pushReplacesEntitlementsWholesale() {
        val t = FakeTransport(ent("member", "2026-12-24T00:00:00Z", true), channels, vacant)
        val s = started(t)
        val pushed = ent("operator", "2027-01-31T00:00:00Z", false)
        t.pushes.tryEmit(pushed)
        awaitEntitlements(s, pushed)
        val v = s.entitlements.value!!
        assertEquals("operator", v.role)
        assertEquals("2027-01-31T00:00:00Z", v.expiresAt)
        assertEquals(false, v.founding)
    }

    @Test
    fun pushWithNullExpiryReplacesPriorExpiry() {
        val t = FakeTransport(ent("member", "2026-12-24T00:00:00Z", true), channels, vacant)
        val s = started(t)
        val lapsed = ent("free", null, true)
        t.pushes.tryEmit(lapsed)
        awaitEntitlements(s, lapsed)
        assertNull(s.entitlements.value!!.expiresAt)
        assertEquals("free", s.entitlements.value!!.role)
    }
}
