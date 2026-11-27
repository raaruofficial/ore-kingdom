package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.channel.ChannelStatus
import com.raaru.orekingdom.channel.SearchStatus
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.identity.MemberListStatus
import com.raaru.orekingdom.shell.InMemoryLastVisitedStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-state edge pins for KingdomSession (DeepSeek full-spend
 * programme Q-25): failed reads land in Error states with no partial
 * data, and the member list is Loading until the directory arrives.
 * Harness in KingdomSessionTestSupport.kt (S-SEAM-2).
 */
class KingdomSessionReadStateTest {

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
    fun searchFailureYieldsErrorStateWithNoHits() {
        transport = fakeTransport()
        transport.searchError = RuntimeException("boom")
        buildSession()
        val state = runBlocking { session.loadSearch("anything") }
        assertEquals(SearchStatus.Error, state.status)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.threads.isEmpty())
    }

    @Test
    fun threadListFailureYieldsErrorState() {
        transport = fakeTransport()
        transport.threadsError = KingdomHttpException(404, "missing")
        buildSession()
        val state = runBlocking { session.loadThreadList("ch_general") }
        assertEquals(ChannelStatus.Error, state.status)
        assertTrue(state.threads.isEmpty())
    }

    @Test
    fun membersStateIsLoadingBeforeDirectoryLoadsAndReadyAfter() {
        transport = fakeTransport()
        buildSession()
        assertEquals(MemberListStatus.Loading, session.membersState().status)
        session.start()
        val state = session.membersState()
        assertEquals(MemberListStatus.Ready, state.status)
        assertEquals(listOf("u_self", "u_other"), state.members.map { it.userId })
        assertEquals("Other", state.members[1].displayName)
        assertEquals(OrePresence.Offline, state.members[1].presence)
    }
}
