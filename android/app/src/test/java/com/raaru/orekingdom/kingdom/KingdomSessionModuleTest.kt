package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.passes.CrownStatus
import com.raaru.orekingdom.passes.PassStatus
import com.raaru.orekingdom.passes.PassRecipientUi
import com.raaru.orekingdom.moderation.AppealState
import com.raaru.orekingdom.moderation.ModQueueStatus
import com.raaru.orekingdom.modules.pump.PumpAtmState
import com.raaru.orekingdom.modules.pump.PumpStatus
import com.raaru.orekingdom.modules.pump.PumpWalletUi
import com.raaru.orekingdom.shell.InMemoryLastVisitedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Module + moderation derivation pins for KingdomSession (DeepSeek
 * full-spend programme tranche 4, cluster A module half): loadPump /
 * loadPasses / loadModQueue / loadModLog / loadSanctions against the
 * configurable FakeSessionTransport (tranche-4 harness extension in
 * KingdomSessionTestSupport.kt; every default reproduces the
 * pre-extension fake).
 */
class KingdomSessionModuleTest {

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)
    private lateinit var transport: FakeSessionTransport
    private lateinit var session: KingdomSession

    @After
    fun tearDown() {
        job.cancel()
    }

    private fun fakeTransport(): FakeSessionTransport = FakeSessionTransport(
        me = "u_self" to sessionMember("u_self", "Self"),
        members = listOf(sessionMember("u_self", "Self"), sessionMember("u_other", "Other")),
        channels = listOf(sessionChannel("ch_general")),
    )

    private fun buildSession(): KingdomSession {
        session = KingdomSession(transport, scope, InMemoryLastVisitedStore(), InMemoryOutboxPersistence())
        return session
    }

    @Test
    fun loadPumpMapsWalletsAndDayBankVerbatim() {
        transport = fakeTransport()
        transport.pumpRead = PumpRead(
            wallets = listOf(
                PumpWalletRead("7xKz9dF", "1.25 SOL", "half"),
                PumpWalletRead("9aBc3eG", "0.00 SOL", "empty"),
            ),
            bankedDays = 2,
            activeRemainingSeconds = 3600L,
            firstDayFree = true,
            atmState = "locked",
        )
        buildSession()

        val load = runBlocking { session.loadPump(sessionEntitlements()) }

        assertEquals(PumpStatus.Ready, load.console.status)
        assertEquals(
            listOf(
                PumpWalletUi("7xKz9dF", "1.25 SOL", "half"),
                PumpWalletUi("9aBc3eG", "0.00 SOL", "empty"),
            ),
            load.console.wallets,
        )
        assertEquals(PumpStatus.Ready, load.dayBank.status)
        assertEquals(2, load.dayBank.bankedDays)
        assertEquals(3600L, load.dayBank.activeRemainingSeconds)
        assertTrue(load.dayBank.firstDayFree)
        assertFalse(load.dayBank.unlimited)
    }

    @Test
    fun loadPumpUnlimitedFollowsPumpDayIncludedCapability() {
        transport = fakeTransport()
        buildSession()
        val included = sessionEntitlements().copy(
            capabilities = sessionEntitlements().capabilities.copy(pumpDayIncluded = true),
        )

        val load = runBlocking { session.loadPump(included) }

        assertTrue(load.dayBank.unlimited)
    }

    @Test
    fun loadPumpAtmCapabilityBeatsTheRead() {
        transport = fakeTransport()
        transport.pumpRead = PumpRead(emptyList(), 0, null, false, "locked")
        buildSession()
        val included = sessionEntitlements().copy(
            capabilities = sessionEntitlements().capabilities.copy(atmUnlockIncluded = true),
        )

        val lockedRead = runBlocking { session.loadPump(included) }
        assertEquals(PumpAtmState.Included, lockedRead.atm.state)

        transport.pumpRead = PumpRead(emptyList(), 0, null, false, "unlocked")
        val unlockedRead = runBlocking { session.loadPump(included) }
        assertEquals(PumpAtmState.Included, unlockedRead.atm.state)
    }

    @Test
    fun loadPumpAtmReadArmsWhenCapabilityAbsent() {
        transport = fakeTransport()
        buildSession()

        transport.pumpRead = PumpRead(emptyList(), 0, null, false, "unlocked")
        assertEquals(PumpAtmState.Unlocked, runBlocking { session.loadPump(sessionEntitlements()) }.atm.state)

        transport.pumpRead = PumpRead(emptyList(), 0, null, false, "included")
        assertEquals(PumpAtmState.Included, runBlocking { session.loadPump(sessionEntitlements()) }.atm.state)

        transport.pumpRead = PumpRead(emptyList(), 0, null, false, "locked")
        assertEquals(PumpAtmState.Locked, runBlocking { session.loadPump(sessionEntitlements()) }.atm.state)

        transport.pumpRead = PumpRead(emptyList(), 0, null, false, "anything-else")
        assertEquals(PumpAtmState.Locked, runBlocking { session.loadPump(sessionEntitlements()) }.atm.state)
    }

    @Test
    fun loadPumpTransportFailureErrorsAllThreeSurfaces() {
        transport = fakeTransport()
        transport.pumpError = RuntimeException("boom")
        buildSession()

        val load = runBlocking { session.loadPump(sessionEntitlements()) }

        assertEquals(PumpStatus.Error, load.console.status)
        assertEquals(PumpStatus.Error, load.dayBank.status)
        assertEquals(PumpStatus.Error, load.atm.status)
    }

    @Test
    fun loadPassesRecipientsAreTheDirectoryMinusSelf() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(
                sessionMember("u_self", "Self"),
                sessionMember("u_other", "Other"),
                sessionMember("u_third", "Third"),
            ),
            channels = listOf(sessionChannel("ch_general")),
        )
        transport.passesRead = PassesRead(lifetimeHolder = true, prestigeSeatsRemaining = 17)
        buildSession()
        session.start()

        val load = runBlocking { session.loadPasses() }

        assertEquals(PassStatus.Ready, load.passes.status)
        assertEquals(
            listOf(PassRecipientUi("u_other", "Other"), PassRecipientUi("u_third", "Third")),
            load.passes.recipients,
        )
        assertTrue(load.passes.lifetimeHolder)
        assertEquals(17, load.passes.prestigeSeatsRemaining)
    }

    @Test
    fun loadPassesNullSeatsPassThroughVerbatim() {
        transport = fakeTransport()
        transport.passesRead = PassesRead(lifetimeHolder = false, prestigeSeatsRemaining = null)
        transport.crownRead = CrownRead(holderName = "Other", month = "2026-11")
        buildSession()
        session.start()

        val load = runBlocking { session.loadPasses() }

        assertEquals(null, load.passes.prestigeSeatsRemaining)
        assertEquals(CrownStatus.Ready, load.crown.status)
        assertEquals("Other", load.crown.holderName)
        assertEquals("2026-11", load.crown.month)
    }

    @Test
    fun loadPassesCrownFailureLeavesPassesReady() {
        transport = fakeTransport()
        transport.passesRead = PassesRead(lifetimeHolder = false, prestigeSeatsRemaining = 3)
        transport.crownError = RuntimeException("boom")
        buildSession()
        session.start()

        val load = runBlocking { session.loadPasses() }

        assertEquals(PassStatus.Ready, load.passes.status)
        assertEquals(3, load.passes.prestigeSeatsRemaining)
        assertEquals(CrownStatus.Error, load.crown.status)
    }

    @Test
    fun loadPassesPassesFailureStillAttemptsCrown() {
        transport = fakeTransport()
        transport.passesError = RuntimeException("boom")
        transport.crownRead = CrownRead(holderName = null, month = null)
        buildSession()
        session.start()

        val load = runBlocking { session.loadPasses() }

        assertEquals(PassStatus.Error, load.passes.status)
        assertEquals(CrownStatus.Ready, load.crown.status)
        assertEquals(null, load.crown.holderName)
    }

    @Test
    fun loadModQueueWithoutModLoftNeverCallsTheRead() {
        transport = fakeTransport()
        buildSession()
        session.start()

        val state = runBlocking { session.loadModQueue(sessionEntitlements()) }

        assertEquals(ModQueueStatus.Ready, state.status)
        assertFalse(state.modLoft)
        assertEquals("u_self", state.viewerId)
        assertTrue(state.items.isEmpty())
        assertEquals(0, transport.modQueueCalls)

        val nullCaps = runBlocking { session.loadModQueue(null) }
        assertFalse(nullCaps.modLoft)
        assertEquals(0, transport.modQueueCalls)
    }

    @Test
    fun loadModQueueContextNamesResolveWithIdFallback() {
        transport = fakeTransport()
        transport.modQueueRead = listOf(
            ModQueueItemRead(
                reportId = "rep_1",
                state = "open",
                claimedBy = null,
                reason = "scam",
                reporterCount = 2,
                snapshot = ModSnapshotRead(
                    body = "flagged body",
                    authorId = "u_other",
                    channelId = "ch_general",
                    createdAt = "2026-11-27T01:00:00Z",
                    context = listOf(
                        sessionMessage("c_1", "ch_general", 1, "u_other"),
                        sessionMessage("c_2", "ch_general", 2, "u_ghost"),
                    ),
                ),
                authorHistory = emptyList(),
                appeal = null,
            ),
        )
        buildSession()
        session.start()
        val mod = sessionEntitlements().copy(
            capabilities = sessionEntitlements().capabilities.copy(modLoft = true),
        )

        val state = runBlocking { session.loadModQueue(mod) }

        assertTrue(state.modLoft)
        assertEquals(1, transport.modQueueCalls)
        val item = state.items.single()
        assertEquals("Other", item.snapshot.authorName)
        assertEquals(listOf("Other", "u_ghost"), item.snapshot.context.map { it.authorName })
        assertEquals("flagged body", item.snapshot.body)
        assertEquals(2, item.reporterCount)
    }

    @Test
    fun loadModQueueSnapshotAuthorNameIsNullWhenAuthorNotInDirectory() {
        transport = fakeTransport()
        transport.modQueueRead = listOf(
            ModQueueItemRead(
                reportId = "rep_2",
                state = "open",
                claimedBy = null,
                reason = "spam",
                reporterCount = 1,
                snapshot = ModSnapshotRead(
                    body = "ghost body",
                    authorId = "u_ghost",
                    channelId = "ch_general",
                    createdAt = "2026-11-27T01:00:00Z",
                    context = emptyList(),
                ),
                authorHistory = emptyList(),
                appeal = null,
            ),
        )
        buildSession()
        session.start()
        val mod = sessionEntitlements().copy(
            capabilities = sessionEntitlements().capabilities.copy(modLoft = true),
        )

        val state = runBlocking { session.loadModQueue(mod) }

        assertEquals(null, state.items.single().snapshot.authorName)
    }

    @Test
    fun loadModQueueClaimedByNameAndAppealStateMapping() {
        transport = fakeTransport()
        transport.modQueueRead = listOf(
            ModQueueItemRead(
                reportId = "rep_3",
                state = "claimed",
                claimedBy = "u_other",
                reason = "harassment",
                reporterCount = 1,
                snapshot = ModSnapshotRead("body", "u_self", "ch_general", "2026-11-27T01:00:00Z", emptyList()),
                authorHistory = listOf(ModHistoryRead("mute", "harassment", "2026-11-26T01:00:00Z")),
                appeal = ModAppealRead("please", "u_mod", "pending"),
            ),
            ModQueueItemRead(
                reportId = "rep_4",
                state = "open",
                claimedBy = null,
                reason = "spam",
                reporterCount = 3,
                snapshot = ModSnapshotRead("body2", "u_self", "ch_general", "2026-11-27T01:30:00Z", emptyList()),
                authorHistory = emptyList(),
                appeal = ModAppealRead("why", "u_mod", "bogus-wire"),
            ),
        )
        buildSession()
        session.start()
        val mod = sessionEntitlements().copy(
            capabilities = sessionEntitlements().capabilities.copy(modLoft = true),
        )

        val state = runBlocking { session.loadModQueue(mod) }

        val claimed = state.items.first { it.reportId == "rep_3" }
        assertEquals("Other", claimed.claimedByName)
        assertEquals(AppealState.Pending, claimed.appeal?.state)
        assertEquals("mute", claimed.authorHistory.single().rung)
        val open = state.items.first { it.reportId == "rep_4" }
        assertEquals(null, open.claimedByName)
        assertEquals(AppealState.None, open.appeal?.state)
    }
}
