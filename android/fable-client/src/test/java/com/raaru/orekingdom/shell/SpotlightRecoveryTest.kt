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
import org.junit.Test

class SpotlightRecoveryTest {

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

    private fun ch(id: String, cat: String) =
        ChannelDto(id, cat, id.removePrefix("ch_"), "text", 0, "free", "member", 0)

    private class FakeTransport(
        var entitlements: Entitlements,
        var channels: List<ChannelDto>,
        var spotlight: SpotlightDto,
        var spotlightFails: Boolean = false,
    ) : ShellTransport {
        val pushes = MutableSharedFlow<Entitlements>(extraBufferCapacity = 8)
        override suspend fun getEntitlements() = entitlements
        override suspend fun getChannels() = channels
        override suspend fun getSpotlight(): SpotlightDto {
            if (spotlightFails) throw IllegalStateException("spotlight down")
            return spotlight
        }
        override val entitlementPushes = pushes
        override suspend fun unsubscribe(channelIds: List<String>) {}
    }

    private val vacant = SpotlightDto(false, null, null, 50)
    private val windowEnd = "2026-12-01T00:00:00Z"
    private fun channels() = listOf(ch("ch_general", "THE FLOOR"))

    private fun started(t: FakeTransport): EntitlementStore {
        val s = EntitlementStore(t, scope, InMemoryLastVisitedStore())
        s.start()
        runBlocking { withTimeout(5_000) { s.rail.first { it.status == RailStatus.Ready } } }
        return s
    }

    private fun occupied(body: String, commercial: Boolean) =
        SpotlightDto(true, SpotlightPostDto("u_1", body, commercial), windowEnd, 50)

    @Test
    fun recoveryFromUnavailableToVacant() {
        val t = FakeTransport(ent(), channels(), vacant, spotlightFails = true)
        val s = started(t)
        assertEquals(SpotlightUiState.Unavailable, s.spotlight.value)
        t.spotlightFails = false
        s.refresh()
        assertEquals(SpotlightUiState.Vacant(50), s.spotlight.value)
    }

    @Test
    fun commercialFlagPassesThroughVerbatim() {
        val sTrue = started(FakeTransport(ent(), channels(), occupied("sale", commercial = true)))
        assertEquals(
            SpotlightUiState.Occupied("u_1", null, "sale", true, windowEnd, 50),
            sTrue.spotlight.value,
        )
        val sFalse = started(FakeTransport(ent(), channels(), occupied("sale", commercial = false)))
        assertEquals(
            SpotlightUiState.Occupied("u_1", null, "sale", false, windowEnd, 50),
            sFalse.spotlight.value,
        )
    }

    @Test
    fun laterReadReplacesVacantWithOccupied() {
        val t = FakeTransport(ent(), channels(), vacant)
        val s = started(t)
        assertEquals(SpotlightUiState.Vacant(50), s.spotlight.value)
        t.spotlight = occupied("now live", commercial = false)
        s.refresh()
        assertEquals(
            SpotlightUiState.Occupied("u_1", null, "now live", false, windowEnd, 50),
            s.spotlight.value,
        )
    }

    @Test
    fun occupiedWithEmptyBodyStillMapsToOccupied() {
        val s = started(FakeTransport(ent(), channels(), occupied("", commercial = false)))
        assertEquals(
            SpotlightUiState.Occupied("u_1", null, "", false, windowEnd, 50),
            s.spotlight.value,
        )
    }
}
