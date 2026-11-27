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
import org.junit.Assert.assertFalse
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

    @Test
    fun threadListSortsByLastActivityDescendingAndMapsFields() {
        transport = fakeTransport()
        transport.threads = listOf(
            ContractThread("t_old", "ch_general", null, "Old thread", "archived", 3, "2026-11-27T01:00:00Z"),
            ContractThread("t_new", "ch_general", null, "New thread", "open", 7, "2026-11-27T03:00:00Z"),
        )
        buildSession()

        val state = runBlocking { session.loadThreadList("ch_general") }

        assertEquals(ChannelStatus.Ready, state.status)
        assertEquals(listOf("t_new", "t_old"), state.threads.map { it.id })
        assertEquals("New thread", state.threads[0].title)
        assertEquals(7, state.threads[0].replyCount)
        assertFalse(state.threads[0].archived)
        assertTrue(state.threads[1].archived)
    }

    @Test
    fun threadListAnchorAuthorNameResolvesThroughCachedAnchor() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self"), sessionMember("u_other", "Other")),
            channels = listOf(sessionChannel("ch_general")),
            history = listOf(sessionMessage("m_anchor", "ch_general", 1, "u_other")),
        )
        transport.threads = listOf(
            ContractThread("t_1", "ch_general", "m_anchor", "Anchored", "open", 2, "2026-11-27T02:00:00Z"),
        )
        buildSession()
        session.start()
        session.openChannel("ch_general")

        val state = runBlocking { session.loadThreadList("ch_general") }

        assertEquals("Other", state.threads.single().anchorAuthorName)
    }

    @Test
    fun threadListAnchorAuthorNameFallsBackToAuthorIdWhenDirectoryMisses() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self"), sessionMember("u_other", "Other")),
            channels = listOf(sessionChannel("ch_general")),
            history = listOf(sessionMessage("m_anchor", "ch_general", 1, "u_ghost")),
        )
        transport.threads = listOf(
            ContractThread("t_1", "ch_general", "m_anchor", "Ghost anchored", "open", 2, "2026-11-27T02:00:00Z"),
        )
        buildSession()
        session.start()
        session.openChannel("ch_general")

        val state = runBlocking { session.loadThreadList("ch_general") }

        assertEquals("u_ghost", state.threads.single().anchorAuthorName)
    }

    @Test
    fun threadListAnchorAuthorNameIsUnknownWithoutCachedAnchor() {
        transport = fakeTransport()
        transport.threads = listOf(
            ContractThread("t_none", "ch_general", null, "No anchor", "open", 0, "2026-11-27T02:00:00Z"),
            ContractThread("t_missing", "ch_general", "m_missing", "Missing anchor", "open", 1, "2026-11-27T01:00:00Z"),
        )
        buildSession()
        session.start()

        val state = runBlocking { session.loadThreadList("ch_general") }

        assertEquals("Unknown", state.threads.first { it.id == "t_none" }.anchorAuthorName)
        assertEquals("Unknown", state.threads.first { it.id == "t_missing" }.anchorAuthorName)
    }

    @Test
    fun loadThreadUnknownIdIsErrorWithEmptyTitle() {
        transport = fakeTransport()
        transport.threads = listOf(
            ContractThread("t_1", "ch_general", null, "Known", "open", 1, "2026-11-27T02:00:00Z"),
        )
        buildSession()

        val state = runBlocking { session.loadThread("ch_general", "t_nope") }

        assertEquals(ChannelStatus.Error, state.status)
        assertEquals("", state.title)
        assertEquals("t_nope", state.threadId)
    }

    @Test
    fun loadThreadKnownThreadMapsTitleArchivedAndNeverUnarchive() {
        transport = fakeTransport()
        transport.threads = listOf(
            ContractThread("t_1", "ch_general", null, "Archived thread", "archived", 4, "2026-11-27T02:00:00Z"),
        )
        buildSession()
        session.start()

        val state = runBlocking { session.loadThread("ch_general", "t_1") }

        assertEquals(ChannelStatus.Ready, state.status)
        assertEquals("Archived thread", state.title)
        assertTrue(state.archived)
        assertFalse(state.canUnarchive)
        assertEquals(null, state.anchor)
        assertTrue(state.replies.isEmpty())
    }

    @Test
    fun loadThreadAnchorResolvesFromCacheAndRepliesMapThroughMessageUi() {
        transport = FakeSessionTransport(
            me = "u_self" to sessionMember("u_self", "Self"),
            members = listOf(sessionMember("u_self", "Self"), sessionMember("u_other", "Other")),
            channels = listOf(sessionChannel("ch_general")),
            history = listOf(sessionMessage("m_anchor", "ch_general", 1, "u_other")),
        )
        transport.threads = listOf(
            ContractThread("t_1", "ch_general", "m_anchor", "Anchored", "open", 2, "2026-11-27T02:00:00Z"),
        )
        transport.threadHistory = listOf(
            sessionMessage("m_r1", "ch_general", 2, "u_other").copy(threadId = "t_1"),
            sessionMessage("m_r2", "ch_general", 3, "u_self").copy(threadId = "t_1"),
        )
        buildSession()
        session.start()
        session.openChannel("ch_general")

        val state = runBlocking { session.loadThread("ch_general", "t_1") }

        assertEquals(ChannelStatus.Ready, state.status)
        assertEquals("m_anchor", state.anchor?.id)
        assertEquals(listOf("m_r1", "m_r2"), state.replies.map { it.id })
        assertEquals("Other", state.replies[0].authorName)
    }

    @Test
    fun loadThreadReactionsEnabledFollowsReactCapability() {
        transport = fakeTransport()
        transport.threads = listOf(
            ContractThread("t_1", "ch_general", null, "Plain", "open", 0, "2026-11-27T02:00:00Z"),
        )
        buildSession()
        session.start()

        val without = runBlocking { session.loadThread("ch_general", "t_1") }
        assertFalse(without.reactionsEnabled)

        transport = fakeTransport()
        transport.entitlements = sessionEntitlements().copy(
            capabilities = sessionEntitlements().capabilities.copy(react = listOf("ch_general")),
        )
        transport.threads = listOf(
            ContractThread("t_1", "ch_general", null, "Plain", "open", 0, "2026-11-27T02:00:00Z"),
        )
        buildSession()
        session.start()

        val with = runBlocking { session.loadThread("ch_general", "t_1") }
        assertTrue(with.reactionsEnabled)
    }
}
