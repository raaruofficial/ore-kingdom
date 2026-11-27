package com.raaru.orekingdom.identity

import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OreRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresenceGroupingTest {
    private fun member(userId: String, presence: OrePresence) = MemberUi(
        userId = userId,
        displayName = userId,
        role = OreRole.Member,
        presence = presence,
    )

    @Test
    fun online_idle_offline_order() {
        val members = listOf(
            member("off", OrePresence.Offline),
            member("idle", OrePresence.Idle),
            member("on", OrePresence.Online),
        )

        val groups = groupMembersByPresence(members)

        assertEquals(listOf(OrePresence.Online, OrePresence.Idle, OrePresence.Offline), groups.map { it.presence })
    }

    @Test
    fun within_group_supplied_order() {
        val members = listOf(
            member("A", OrePresence.Online),
            member("B", OrePresence.Online),
            member("C", OrePresence.Online),
        )

        val groups = groupMembersByPresence(members)

        assertEquals(1, groups.size)
        assertEquals(listOf("A", "B", "C"), groups[0].members.map { it.userId })
    }

    @Test
    fun dedupe_first_wins() {
        val members = listOf(
            member("x", OrePresence.Online),
            member("x", OrePresence.Offline),
        )

        val groups = groupMembersByPresence(members)

        assertEquals(1, groups.size)
        assertEquals(OrePresence.Online, groups[0].presence)
        assertEquals(listOf("x"), groups[0].members.map { it.userId })
    }

    @Test
    fun dedupe_cross_group_no_double_count() {
        val members = listOf(
            member("a", OrePresence.Online),
            member("b", OrePresence.Offline),
            member("a", OrePresence.Offline),
            member("c", OrePresence.Online),
        )

        val groups = groupMembersByPresence(members)

        assertEquals(3, groups.sumOf { it.members.size })
        assertEquals(setOf("a", "b", "c"), groups.flatMap { it.members }.map { it.userId }.toSet())
    }

    @Test
    fun empty_groups_omitted() {
        val members = listOf(
            member("a", OrePresence.Idle),
            member("b", OrePresence.Idle),
        )

        val groups = groupMembersByPresence(members)

        assertEquals(1, groups.size)
        assertEquals(OrePresence.Idle, groups[0].presence)
    }

    @Test
    fun empty_input_empty_output() {
        assertTrue(groupMembersByPresence(emptyList()).isEmpty())
    }

    @Test
    fun count_matches_members() {
        val members = listOf(
            member("a", OrePresence.Online),
            member("b", OrePresence.Online),
            member("a", OrePresence.Offline),
            member("c", OrePresence.Idle),
            member("d", OrePresence.Idle),
            member("e", OrePresence.Offline),
        )

        val groups = groupMembersByPresence(members)
        val expectedCounts = mapOf(
            OrePresence.Online to 2,
            OrePresence.Idle to 2,
            OrePresence.Offline to 1,
        )

        assertEquals(3, groups.size)
        groups.forEach { group ->
            assertEquals(expectedCounts.getValue(group.presence), group.members.size)
        }
    }

    @Test
    fun input_not_mutated() {
        val input = listOf(
            member("a", OrePresence.Online),
            member("b", OrePresence.Offline),
            member("a", OrePresence.Idle),
        )
        val before = input.toList()

        val first = groupMembersByPresence(input)
        val second = groupMembersByPresence(input)

        assertEquals(before, input)
        assertEquals(first, second)
    }
}
