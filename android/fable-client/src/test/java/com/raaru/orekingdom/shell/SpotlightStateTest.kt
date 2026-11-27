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

class SpotlightStateTest {

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
        val pushes = MutableSharedFlow<EntitlementPush>(extraBufferCapacity = 8)
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
    private fun channels() = listOf(ch("ch_general", "THE FLOOR"))

    private fun started(t: FakeTransport): EntitlementStore {
        val s = EntitlementStore(t, scope, InMemoryLastVisitedStore())
        s.start()
        runBlocking { withTimeout(5_000) { s.rail.first { it.status == RailStatus.Ready } } }
        return s
    }

    @Test
    fun firstSpotlightReadFailureYieldsUnavailable() {
        val s = started(FakeTransport(ent(), channels(), vacant, spotlightFails = true))
        assertEquals(SpotlightUiState.Unavailable, s.spotlight.value)
    }

    @Test
    fun laterSpotlightFailureKeepsLastGoodState() {
        val t = FakeTransport(ent(), channels(), vacant)
        val s = started(t)
        assertEquals(SpotlightUiState.Vacant(50), s.spotlight.value)
        t.spotlightFails = true
        s.refresh()
        assertEquals(SpotlightUiState.Vacant(50), s.spotlight.value)
    }

    @Test
    fun vacantFloorPassesThroughVerbatim() {
        val s = started(FakeTransport(ent(), channels(), SpotlightDto(false, null, null, 75)))
        assertEquals(SpotlightUiState.Vacant(75), s.spotlight.value)
    }

    @Test
    fun occupiedWithPostButNoWindowFallsBackToVacant() {
        val dto = SpotlightDto(true, SpotlightPostDto("u_1", "hi", false), null, 50)
        val s = started(FakeTransport(ent(), channels(), dto))
        assertTrue(s.spotlight.value is SpotlightUiState.Vacant)
        assertEquals(SpotlightUiState.Vacant(50), s.spotlight.value)
    }

    @Test
    fun occupiedWithWindowButNoPostFallsBackToVacant() {
        val dto = SpotlightDto(true, null, "2026-12-01T00:00:00Z", 50)
        val s = started(FakeTransport(ent(), channels(), dto))
        assertEquals(SpotlightUiState.Vacant(50), s.spotlight.value)
    }
}
