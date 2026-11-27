package com.raaru.orekingdom.states

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

internal object IdentityMissingStatesCopy {
    const val PROFILE_UNAVAILABLE_TITLE = "Profile not available"
    const val PROFILE_UNAVAILABLE_BODY = "There is nothing to show for this member."
    const val BACK = "Go back"
    const val PRESENCE_OFFLINE = "You're offline. Presence shown here may be out of date."
    const val PROFILE_OFFLINE = "You're offline. Showing the last loaded profile."
}

/**
 * Identity audit: member-list skeleton / empty / error and profile skeleton / error are already
 * delivered (IdentityStates + inline), and the lapse upsell is in ProfileScreen. Missing: the
 * profile empty state and the offline notices.
 */

/** Profile empty state: the member could not be resolved. Fixed copy, no raw error. */
@Composable
fun ProfileUnavailable(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    StateMessage(
        title = IdentityMissingStatesCopy.PROFILE_UNAVAILABLE_TITLE,
        body = IdentityMissingStatesCopy.PROFILE_UNAVAILABLE_BODY,
        modifier = modifier,
        actionLabel = if (onBack != null) IdentityMissingStatesCopy.BACK else null,
        onAction = onBack,
    )
}

/** Offline notice for the member list / presence panel: presence is ephemeral and rebuilt on reconnect. */
@Composable
fun PresenceOfflineNotice(modifier: Modifier = Modifier) {
    StateBanner(text = IdentityMissingStatesCopy.PRESENCE_OFFLINE, modifier = modifier, stale = true)
}

/** Offline notice for a cached profile. */
@Composable
fun ProfileOfflineNotice(modifier: Modifier = Modifier) {
    StateBanner(text = IdentityMissingStatesCopy.PROFILE_OFFLINE, modifier = modifier, stale = true)
}
