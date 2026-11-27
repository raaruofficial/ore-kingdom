package com.raaru.orekingdom.designsystem.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Role label pins (C-37), the sibling of PresenceLabelTest: the
 * role → label map shown on every member row. Skeleton
 * supervisor-authored; the test methods are dictated units (see
 * the T4 run artefacts).
 */
class RoleLabelTest {

    @Test
    fun roleLabelsMapToStandingCopy() {
        assertEquals(OreComponentCopy.RoleFree, roleLabel(OreRole.Free))
        assertEquals(OreComponentCopy.RoleMember, roleLabel(OreRole.Member))
        assertEquals(OreComponentCopy.RoleOperator, roleLabel(OreRole.Operator))
        assertEquals(OreComponentCopy.RoleSyndicate, roleLabel(OreRole.Syndicate))
        assertEquals(OreComponentCopy.RolePrestige, roleLabel(OreRole.Prestige))
    }

    @Test
    fun roleLabelsAreDistinctAndNotBlank() {
        val labels = OreRole.values().map { roleLabel(it) }
        assertEquals(labels.size, labels.distinct().size)
        assertTrue(labels.none { it.isBlank() })
    }
}
