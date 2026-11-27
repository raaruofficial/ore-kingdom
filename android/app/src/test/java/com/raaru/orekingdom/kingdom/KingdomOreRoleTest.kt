package com.raaru.orekingdom.kingdom

import com.raaru.orekingdom.designsystem.components.OreRole
import org.junit.Assert.assertEquals
import org.junit.Test

/** Role-string mapping pins for kingdomOreRoleOf (DeepSeek full-spend programme Q-18). */
class KingdomOreRoleTest {

    @Test
    fun namedRolesMapExactly() {
        assertEquals(OreRole.Member, kingdomOreRoleOf("member"))
        assertEquals(OreRole.Operator, kingdomOreRoleOf("operator"))
        assertEquals(OreRole.Syndicate, kingdomOreRoleOf("syndicate"))
        assertEquals(OreRole.Prestige, kingdomOreRoleOf("prestige"))
    }

    @Test
    fun everythingElseMapsToFree() {
        assertEquals(OreRole.Free, kingdomOreRoleOf("free"))
        assertEquals(OreRole.Free, kingdomOreRoleOf("gold"))
        assertEquals(OreRole.Free, kingdomOreRoleOf(""))
    }
}
