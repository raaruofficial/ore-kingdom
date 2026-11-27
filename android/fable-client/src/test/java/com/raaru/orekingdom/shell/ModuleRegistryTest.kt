package com.raaru.orekingdom.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModuleRegistryTest {

    private val caps = Capabilities(
        post = emptyList(),
        react = emptyList(),
        tip = false,
        gift = false,
        commercialPost = false,
        spotlightBid = false,
        pumpModule = "none",
        pumpDayIncluded = false,
        atmUnlockIncluded = false,
        nightshiftConsole = false,
        autopilotSurface = false,
        modLoft = false,
    )

    private fun read(modules: Map<String, String>) =
        Entitlements("free", caps, null, false, modules)

    private val pumpOn = ModuleEntry("module/pump", "THE PUMP", ModuleFlag.On)
    private val pumpPreview = ModuleEntry("module/pump", "THE PUMP", ModuleFlag.Preview)
    private val nightshiftOn = ModuleEntry("module/nightshift", "NIGHTSHIFT", ModuleFlag.On)

    @Test
    fun nullReadYieldsEmpty() {
        assertTrue(ModuleRegistry.entries(null).isEmpty())
    }

    @Test
    fun emptyModulesMapYieldsEmpty() {
        assertTrue(ModuleRegistry.entries(read(emptyMap())).isEmpty())
    }

    @Test
    fun onFlagYieldsOnEntryWithExactRouteAndLabel() {
        val entries = ModuleRegistry.entries(read(mapOf("pump" to "on")))
        assertEquals(listOf(pumpOn), entries)
        assertEquals("module/pump", entries[0].route)
        assertEquals("THE PUMP", entries[0].label)
        assertEquals(ModuleFlag.On, entries[0].state)
    }

    @Test
    fun previewFlagYieldsPreviewEntry() {
        val entries = ModuleRegistry.entries(read(mapOf("pump" to "preview")))
        assertEquals(listOf(pumpPreview), entries)
    }

    @Test
    fun offFlagYieldsNothing() {
        assertTrue(ModuleRegistry.entries(read(mapOf("pump" to "off", "nightshift" to "off"))).isEmpty())
    }

    @Test
    fun unknownValueYieldsNothing() {
        assertTrue(ModuleRegistry.entries(read(mapOf("pump" to "beta"))).isEmpty())
    }

    @Test
    fun valueIsCaseSensitive() {
        assertTrue(ModuleRegistry.entries(read(mapOf("pump" to "ON", "nightshift" to "Preview"))).isEmpty())
    }

    @Test
    fun bothOnKeepsRegistrationOrderPumpFirst() {
        val entries = ModuleRegistry.entries(read(mapOf("nightshift" to "on", "pump" to "on")))
        assertEquals(listOf(pumpOn, nightshiftOn), entries)
    }

    @Test
    fun unrelatedKeyIsIgnored() {
        val entries = ModuleRegistry.entries(read(mapOf("other" to "on", "nightshift" to "on")))
        assertEquals(listOf(nightshiftOn), entries)
    }
}
