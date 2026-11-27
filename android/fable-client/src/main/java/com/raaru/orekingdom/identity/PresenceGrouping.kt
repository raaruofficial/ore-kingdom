package com.raaru.orekingdom.identity

import com.raaru.orekingdom.designsystem.components.OrePresence

/**
 * Groups members into fixed presence order: Online, Idle, Offline.
 * Dedupes by userId first, first occurrence wins; within a group, supplied order is kept.
 * Presences with no members are omitted.
 */
internal data class PresenceGroup(
    val presence: OrePresence,
    val members: List<MemberUi>,
)

internal fun groupMembersByPresence(members: List<MemberUi>): List<PresenceGroup> {
    val deduped = members.distinctBy { it.userId }
    val grouped = deduped.groupBy { it.presence }
    return listOf(OrePresence.Online, OrePresence.Idle, OrePresence.Offline).mapNotNull { presence ->
        grouped[presence]
            ?.takeIf { it.isNotEmpty() }
            ?.let { PresenceGroup(presence, it) }
    }
}
