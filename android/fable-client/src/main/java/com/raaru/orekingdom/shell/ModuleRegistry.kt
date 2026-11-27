package com.raaru.orekingdom.shell

/**
 * One registration per module area. A module owns only its entry + route; identity/session,
 * entitlements, transport and navigation chrome stay in the shell. Deleting a line here
 * removes the module from navigation and from AppShell's route table (both derive from
 * [ModuleRegistry.entries]).
 */
private data class ModuleRegistration(
    /** Key in the entitlement read's `modules` object (CONTRACT.md §6). */
    val key: String,
    val route: String,
    val label: String,
)

private val Registrations = listOf(
    ModuleRegistration(key = "pump", route = "module/pump", label = "THE PUMP"),
    ModuleRegistration(key = "nightshift", route = "module/nightshift", label = "NIGHTSHIFT"),
)

private const val FlagPreview = "preview"
private const val FlagOn = "on"

object ModuleRegistry {

    /**
     * Entries for the modules the entitlement read flags `preview` or `on`. `off`, a missing
     * key, an unknown value, or no entitlement read yet all yield no entry.
     *
     * TODO-CONTRACT: BRIEF §5 names a per-module minimum client version, but the pinned
     * Entitlements shape carries none; no version gate is applied here.
     */
    fun entries(entitlements: Entitlements?): List<ModuleEntry> {
        val flags = entitlements?.modules ?: return emptyList()
        return Registrations.mapNotNull { registration ->
            when (flagOf(flags[registration.key])) {
                ModuleFlag.Off -> null
                ModuleFlag.Preview -> registration.toEntry(ModuleFlag.Preview)
                ModuleFlag.On -> registration.toEntry(ModuleFlag.On)
            }
        }
    }

    private fun flagOf(value: String?): ModuleFlag = when (value) {
        FlagPreview -> ModuleFlag.Preview
        FlagOn -> ModuleFlag.On
        else -> ModuleFlag.Off
    }

    private fun ModuleRegistration.toEntry(state: ModuleFlag) =
        ModuleEntry(route = route, label = label, state = state)
}
