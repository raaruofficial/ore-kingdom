package com.raaru.orekingdom.kingdom

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
}
