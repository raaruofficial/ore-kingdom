package com.raaru.orekingdom.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.raaru.orekingdom.channel.ChannelMessageUi
import com.raaru.orekingdom.channel.ChannelScreen
import com.raaru.orekingdom.channel.ChannelStatus
import com.raaru.orekingdom.channel.ChannelUiState
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OreReaction
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.designsystem.tokens.OreDarkColors
import com.raaru.orekingdom.designsystem.tokens.OreTheme
import com.raaru.orekingdom.home.ServerHomeScreen
import com.raaru.orekingdom.identity.MemberListScreen
import com.raaru.orekingdom.identity.MemberListStatus
import com.raaru.orekingdom.identity.MemberListUiState
import com.raaru.orekingdom.identity.MemberUi
import com.raaru.orekingdom.identity.ProfileScreen
import com.raaru.orekingdom.identity.ProfileStatus
import com.raaru.orekingdom.identity.ProfileUiState
import com.raaru.orekingdom.moderation.ModQueueItemUi
import com.raaru.orekingdom.moderation.ModQueueScreen
import com.raaru.orekingdom.moderation.ModQueueStatus
import com.raaru.orekingdom.moderation.ModQueueUiState
import com.raaru.orekingdom.moderation.ModSnapshotUi
import com.raaru.orekingdom.moderation.ReportReason
import com.raaru.orekingdom.modules.nightshift.FleetSummaryLineUi
import com.raaru.orekingdom.modules.nightshift.FleetSummaryUi
import com.raaru.orekingdom.modules.nightshift.NightshiftConsoleScreen
import com.raaru.orekingdom.modules.nightshift.NightshiftConsoleUi
import com.raaru.orekingdom.modules.nightshift.NightshiftStatus
import com.raaru.orekingdom.modules.nightshift.SuggestionUi
import com.raaru.orekingdom.modules.pump.PumpConsoleScreen
import com.raaru.orekingdom.modules.pump.PumpConsoleUi
import com.raaru.orekingdom.modules.pump.PumpStatus
import com.raaru.orekingdom.modules.pump.PumpWalletUi
import com.raaru.orekingdom.passes.PassRecipientUi
import com.raaru.orekingdom.passes.PassScreen
import com.raaru.orekingdom.passes.PassScreenUi
import com.raaru.orekingdom.passes.PassStatus
import com.raaru.orekingdom.shell.Capabilities
import com.raaru.orekingdom.shell.ChannelKind
import com.raaru.orekingdom.shell.ChannelRail
import com.raaru.orekingdom.shell.Entitlements
import com.raaru.orekingdom.shell.ModuleEntry
import com.raaru.orekingdom.shell.ModuleFlag
import com.raaru.orekingdom.shell.RailCategory
import com.raaru.orekingdom.shell.RailChannel
import com.raaru.orekingdom.shell.RailState
import com.raaru.orekingdom.shell.RailStatus
import com.raaru.orekingdom.shell.SpotlightSlot
import com.raaru.orekingdom.shell.SpotlightUiState

/** Backend-free sample data built from the real state classes. */
object SampleData {
    val railState = RailState(
        status = RailStatus.Ready,
        categories = listOf(
            RailCategory(
                "THE FLOOR",
                listOf(
                    RailChannel("ch_general", "general", ChannelKind.Text, 2, ComposerState.Enabled),
                    RailChannel("ch_announce", "announcements", ChannelKind.System, 0, ComposerState.Disabled()),
                ),
            ),
            RailCategory(
                "MEMBER LOFT",
                listOf(RailChannel("ch_loft", "loft", ChannelKind.Text, 0, ComposerState.Enabled)),
            ),
        ),
        lastVisitedChannelId = "ch_general",
    )

    val spotlightVacant = SpotlightUiState.Vacant(50)

    val spotlightOccupied = SpotlightUiState.Occupied(
        authorId = "u_satoshi",
        authorName = "satoshi",
        body = "Sample spotlighted post.",
        commercial = true,
        windowEndsAt = "2099-01-01T00:00:00Z",
        floorUsd = 50,
    )

    val modules = listOf(
        ModuleEntry("pump", "THE PUMP", ModuleFlag.Preview),
        ModuleEntry("nightshift", "NIGHTSHIFT", ModuleFlag.Preview),
    )

    val channelState = ChannelUiState(
        channelName = "general",
        status = ChannelStatus.Ready,
        reactionsEnabled = true,
        messages = listOf(
            ChannelMessageUi(
                id = "m_1", seq = 1L, authorName = "ada", authorRole = OreRole.Member,
                body = "Morning, everyone.", timestamp = "2026-11-26T17:00:00Z",
                reactions = listOf(OreReaction("🔥", 2, mine = true)),
            ),
            ChannelMessageUi(
                id = "m_2", seq = 2L, authorName = "satoshi", authorRole = OreRole.Member,
                body = "Round board looks busy.", timestamp = "2026-11-26T17:01:00Z", own = true,
            ),
            ChannelMessageUi(
                id = "m_3", seq = 3L, authorName = "grace", authorRole = OreRole.Operator,
                body = "Joined early, still here.", timestamp = "2026-11-26T17:02:00Z", founding = true,
            ),
        ),
    )

    val memberListState = MemberListUiState(
        status = MemberListStatus.Ready,
        members = listOf(
            MemberUi("u_1", "ada", OreRole.Member, OrePresence.Online, activity = "in a round"),
            MemberUi("u_2", "grace", OreRole.Operator, OrePresence.Idle, founding = true),
            MemberUi("u_3", "linus", OreRole.Member, OrePresence.Offline),
            MemberUi("u_4", "mod_sam", OreRole.Member, OrePresence.Online, moderator = true),
        ),
    )

    val profileState = ProfileUiState(
        status = ProfileStatus.Ready,
        userId = "u_satoshi",
        displayName = "satoshi",
        memberRole = "member",
        founding = true,
        presence = OrePresence.Online,
        activity = "Mining round open",
    )

    val entitlements = Entitlements(
        "member",
        Capabilities(
            post = listOf("ch_general", "ch_loft"),
            react = listOf("ch_general", "ch_loft"),
            tip = true,
            gift = true,
            commercialPost = false,
            spotlightBid = true,
            pumpModule = "preview",
            pumpDayIncluded = false,
            atmUnlockIncluded = false,
            nightshiftConsole = true,
            autopilotSurface = false,
            modLoft = true,
        ),
        "2026-12-24T00:00:00Z",
        true,
        emptyMap(),
    )

    val modQueueState = ModQueueUiState(
        viewerId = "u_mod",
        modLoft = true,
        status = ModQueueStatus.Ready,
        items = listOf(
            ModQueueItemUi(
                "r_1", "open", ReportReason.Scam.wire, 3,
                ModSnapshotUi(
                    body = "sample reported message",
                    authorId = "u_spam",
                    channelId = "ch_general",
                    createdAt = "2026-11-26T17:00:00Z",
                ),
            ),
        ),
    )

    val pumpState = PumpConsoleUi(
        status = PumpStatus.Ready,
        wallets = listOf(
            PumpWalletUi("7xKZ…9dFq", "Balance shown by wallet", "Tank healthy"),
            PumpWalletUi("3mQp…2aLs", "Balance shown by wallet", "Tank low"),
        ),
    )

    val nightshiftState = NightshiftConsoleUi(
        status = NightshiftStatus.Ready,
        fleet = FleetSummaryUi(
            headline = "Fleet nominal",
            lines = listOf(FleetSummaryLineUi("Miners", "4 active")),
        ),
        suggestions = listOf(
            SuggestionUi("s_1", "Shift a miner", "Round difficulty eased on the east pool."),
        ),
    )

    val passState = PassScreenUi(
        status = PassStatus.Ready,
        lifetimeHolder = false,
        prestigeSeatsRemaining = 42,
        recipients = listOf(PassRecipientUi("u_satoshi", "satoshi")),
    )
}

@Composable
private fun GalleryFrame(content: @Composable () -> Unit) {
    OreTheme(colors = OreDarkColors) {
        Box(modifier = Modifier.fillMaxSize().background(OreTheme.color.surface.base)) {
            content()
        }
    }
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun RailGalleryPreview() = GalleryFrame {
    ChannelRail(
        rail = SampleData.railState,
        onChannelSelected = {},
        modifier = Modifier,
        spotlight = SampleData.spotlightVacant,
        modules = SampleData.modules,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun HomeGalleryPreview() = GalleryFrame {
    ServerHomeScreen(
        rail = SampleData.railState,
        spotlight = SampleData.spotlightVacant,
        modules = SampleData.modules,
        onlineCount = 128,
        activityLines = listOf("Round board is active", "Intel feed updated"),
        onChannelSelected = {},
        onModuleSelected = {},
        onProfileSelected = {},
        modifier = Modifier,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ChannelGalleryPreview() = GalleryFrame {
    ChannelScreen(
        state = SampleData.channelState,
        onReactionToggle = { _, _ -> },
        onMediaOpen = { _, _ -> },
        modifier = Modifier,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun MembersGalleryPreview() = GalleryFrame {
    MemberListScreen(state = SampleData.memberListState, modifier = Modifier)
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ProfileGalleryPreview() = GalleryFrame {
    ProfileScreen(
        state = SampleData.profileState,
        entitlements = SampleData.entitlements,
        onReport = {},
        modifier = Modifier,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun ModQueueGalleryPreview() = GalleryFrame {
    ModQueueScreen(
        state = SampleData.modQueueState,
        onClaim = {},
        onAction = {},
        onDecideAppeal = {},
        modifier = Modifier,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PumpGalleryPreview() = GalleryFrame {
    PumpConsoleScreen(
        state = SampleData.pumpState,
        flag = ModuleFlag.Preview,
        modifier = Modifier,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun NightshiftGalleryPreview() = GalleryFrame {
    NightshiftConsoleScreen(
        state = SampleData.nightshiftState,
        flag = ModuleFlag.Preview,
        modifier = Modifier,
    )
}

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun PassGalleryPreview() = GalleryFrame {
    PassScreen(
        state = SampleData.passState,
        entitlements = SampleData.entitlements,
        modifier = Modifier,
    )
}

@Preview(showBackground = true)
@Composable
private fun SpotlightGalleryPreview() = GalleryFrame {
    SpotlightSlot(state = SampleData.spotlightOccupied, modifier = Modifier)
}
