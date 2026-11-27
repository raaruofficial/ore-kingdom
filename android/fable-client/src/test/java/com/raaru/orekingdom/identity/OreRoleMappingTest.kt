package com.raaru.orekingdom.identity

import com.raaru.orekingdom.designsystem.components.OreRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** Role-string mapping pins for oreRoleOf (DeepSeek full-spend programme Q-18). */
class OreRoleMappingTest {

    @Test
    fun namedRolesMapExactly() {
        assertEquals(OreRole.Member, oreRoleOf("member"))
        assertEquals(OreRole.Operator, oreRoleOf("operator"))
        assertEquals(OreRole.Syndicate, oreRoleOf("syndicate"))
        assertEquals(OreRole.Prestige, oreRoleOf("prestige"))
    }

    @Test
    fun everythingElseMapsToFree() {
        assertEquals(OreRole.Free, oreRoleOf("free"))
        assertEquals(OreRole.Free, oreRoleOf("gold"))
        assertEquals(OreRole.Free, oreRoleOf(""))
    }
}
