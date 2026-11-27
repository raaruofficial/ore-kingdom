package com.raaru.orekingdom.designsystem.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.raaru.orekingdom.designsystem.components.AvailabilityCard
import com.raaru.orekingdom.designsystem.components.CategoryHeader
import com.raaru.orekingdom.designsystem.components.ChannelRow
import com.raaru.orekingdom.designsystem.components.CommercialLabel
import com.raaru.orekingdom.designsystem.components.Composer
import com.raaru.orekingdom.designsystem.components.ComposerState
import com.raaru.orekingdom.designsystem.components.CrownBadge
import com.raaru.orekingdom.designsystem.components.FoundingBadge
import com.raaru.orekingdom.designsystem.components.IdentityBadges
import com.raaru.orekingdom.designsystem.components.InfoCard
import com.raaru.orekingdom.designsystem.components.MemberRow
import com.raaru.orekingdom.designsystem.components.MessageBubble
import com.raaru.orekingdom.designsystem.components.ModeratorBadge
import com.raaru.orekingdom.designsystem.components.OreAvailability
import com.raaru.orekingdom.designsystem.components.OreGhostButton
import com.raaru.orekingdom.designsystem.components.OreMessageDelivery
import com.raaru.orekingdom.designsystem.components.OrePresence
import com.raaru.orekingdom.designsystem.components.OrePrimaryButton
import com.raaru.orekingdom.designsystem.components.OreReaction
import com.raaru.orekingdom.designsystem.components.OreRole
import com.raaru.orekingdom.designsystem.components.OreText
import com.raaru.orekingdom.designsystem.components.OreTombstoneKind
import com.raaru.orekingdom.designsystem.components.PinnedMarker
import com.raaru.orekingdom.designsystem.components.PresenceDot
import com.raaru.orekingdom.designsystem.components.PresenceRow
import com.raaru.orekingdom.designsystem.components.PresenceSectionHeader
import com.raaru.orekingdom.designsystem.components.QueuedMessage
import com.raaru.orekingdom.designsystem.components.ReactionRow
import com.raaru.orekingdom.designsystem.components.RoleBadge
import com.raaru.orekingdom.designsystem.components.SendingMessage
import com.raaru.orekingdom.designsystem.components.SponsoredCard
import com.raaru.orekingdom.designsystem.components.SponsoredLabel
import com.raaru.orekingdom.designsystem.components.StaleBadge
import com.raaru.orekingdom.designsystem.components.TombstoneRow
import com.raaru.orekingdom.designsystem.components.UnreadBadge
import com.raaru.orekingdom.designsystem.components.presenceLabel
import com.raaru.orekingdom.designsystem.components.roleColor
import com.raaru.orekingdom.designsystem.components.roleLabel
import com.raaru.orekingdom.designsystem.tokens.OreTheme

/*
 * Design-review preview of the landed F1 component set. Pressed states are interaction-driven
 * and need an interactive preview. Not previewed (no signature of record): OreChip, TintedBadge,
 * ChannelRow glyph/note, MemberRow avatar/onClick, card action slots.
 */

/** Placeholder strings for design review only. Not product copy. */
private object PreviewSample {
    const val Action = "Sample action"
    const val Note = "Sample note explaining why this control is disabled"
    const val Name = "sample_name"
    const val Channel = "sample-channel"
    const val Body = "Sample message body for design review."
    const val Time = "12:00"
    const val Title = "Sample title"
    const val Activity = "in a round"
    const val UpsellAction = "Sample upsell action"
    val Reactions = listOf(OreReaction("🔥", 3, true), OreReaction("👍", 1, false))
}

@Preview(name = "Ore design system", widthDp = 360, heightDp = 5200)
@Composable
private fun DesignSystemPreview() {
    OreTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(OreTheme.color.surface.base)
                .verticalScroll(rememberScrollState())
                .padding(OreTheme.space.lg),
            verticalArrangement = Arrangement.spacedBy(OreTheme.space.xl),
        ) {
            ColorTokensSection()
            TypeTokensSection()
            SpacingTokensSection()
            ButtonsSection()
            RoleBadgesSection()
            MarksSection()
            LabelsSection()
            PinVersusCommercialSection()
            IdentityBadgesSection()
            ChannelListSection()
            MessageSection()
            MessageStatesSection()
            ComposerSection()
            MemberRowSection()
            PresenceSection()
            CardsSection()
        }
    }
}

@Composable
private fun ColorTokensSection() {
    val c = OreTheme.color
    Section(title = "TOKENS / COLOUR") {
        Swatches("surface", listOf(c.surface.base, c.surface.sunken, c.surface.raised, c.surface.overlay, c.surface.highlight, c.surface.scrim))
        Swatches("text", listOf(c.text.primary, c.text.secondary, c.text.tertiary, c.text.disabled, c.text.onAccent))
        Swatches("accent", listOf(c.accent.primary, c.accent.primaryPressed, c.accent.onPrimary, c.accent.subtle, c.accent.onSubtle))
        Swatches("border", listOf(c.border.subtle, c.border.strong, c.border.focus))
        Swatches("role", listOf(c.role.free, c.role.member, c.role.operator, c.role.syndicate, c.role.prestige, c.role.moderator, c.role.founding))
        Swatches("presence", listOf(c.presence.online, c.presence.idle, c.presence.offline))
        Swatches(
            "status (content / container)",
            listOf(c.status.error.content, c.status.error.container, c.status.warning.content, c.status.warning.container, c.status.info.content, c.status.info.container),
        )
        Swatches(
            "message",
            listOf(c.message.bubble, c.message.bubbleOwn, c.message.tombstone, c.message.tombstoneContent, c.message.reactionChip, c.message.reactionChipMine, c.message.reactionChipMineBorder, c.message.sending),
        )
        Swatches(
            "message queued / stale",
            listOf(c.message.queued.content, c.message.queued.container, c.message.stale.content, c.message.stale.container),
        )
        Swatches("spotlight", listOf(c.spotlight.container, c.spotlight.border, c.spotlight.glow, c.spotlight.content, c.spotlight.label))
        Swatches(
            "label (content / container)",
            listOf(
                c.label.commercial.content, c.label.commercial.container, c.label.sponsored.content, c.label.sponsored.container,
                c.label.crown.content, c.label.crown.container, c.label.nativePin.content, c.label.nativePin.container,
            ),
        )
    }
}

@Composable
private fun TypeTokensSection() {
    val t = OreTheme.type
    Section(title = "TOKENS / TYPE") {
        listOf(
            "titleMedium" to t.titleMedium, "titleSmall" to t.titleSmall, "body" to t.body,
            "bodyStrong" to t.bodyStrong, "label" to t.label, "caption" to t.caption,
            "overline" to t.overline, "badge" to t.badge, "numeric" to t.numeric,
        ).forEach { (name, style) ->
            OreText(text = name, style = style, color = OreTheme.color.text.primary, maxLines = 1)
        }
    }
}

@Composable
private fun SpacingTokensSection() {
    val s = OreTheme.space
    Section(title = "TOKENS / SPACING") {
        listOf("xxs" to s.xxs, "xs" to s.xs, "sm" to s.sm, "md" to s.md, "lg" to s.lg, "xl" to s.xl, "xxl" to s.xxl).forEach { (name, dp) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(s.sm)) {
                OreText(text = name, style = OreTheme.type.caption, color = OreTheme.color.text.secondary, modifier = Modifier.width(s.xxl), maxLines = 1)
                Box(Modifier.width(dp).height(s.sm).background(OreTheme.color.accent.primary))
            }
        }
    }
}

@Composable
private fun ButtonsSection() {
    Section(title = "BUTTONS / PRIMARY") {
        State(name = "enabled") { OrePrimaryButton(text = PreviewSample.Action, onClick = {}) }
        State(name = "disabled") { OrePrimaryButton(text = PreviewSample.Action, onClick = {}, enabled = false) }
        State(name = "disabled with note") {
            OrePrimaryButton(text = PreviewSample.Action, onClick = {}, enabled = false, note = PreviewSample.Note)
        }
    }
    Section(title = "BUTTONS / GHOST") {
        State(name = "enabled") { OreGhostButton(text = PreviewSample.Action, onClick = {}) }
        State(name = "disabled") { OreGhostButton(text = PreviewSample.Action, onClick = {}, enabled = false) }
    }
}

@Composable
private fun RoleBadgesSection() {
    Section(title = "ROLE COLOUR + ROLE BADGE") {
        OreRole.values().forEach { role ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OreText(text = roleLabel(role), style = OreTheme.type.titleSmall, color = roleColor(role), maxLines = 1)
                RoleBadge(role = role)
            }
        }
    }
}

@Composable
private fun MarksSection() {
    Section(title = "MARKS") {
        State(name = "founding") { FoundingBadge() }
        State(name = "moderator") { ModeratorBadge() }
        State(name = "crown") { CrownBadge() }
    }
}

@Composable
private fun LabelsSection() {
    Section(title = "LABELS") {
        State(name = "commercial") { CommercialLabel() }
        State(name = "sponsored") { SponsoredLabel() }
        State(name = "native pin") { PinnedMarker() }
        State(name = "stale") { StaleBadge() }
    }
}

@Composable
private fun PinVersusCommercialSection() {
    Section(title = "DISTINCTION CHECK / NATIVE PIN VS COMMERCIAL VS SPONSORED") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PinnedMarker()
            CommercialLabel()
            SponsoredLabel()
        }
    }
}

@Composable
private fun IdentityBadgesSection() {
    Section(title = "IDENTITY BADGES / COMBINATIONS") {
        State(name = "role only") { IdentityBadges(role = OreRole.Member) }
        State(name = "role + founding") { IdentityBadges(role = OreRole.Member, founding = true) }
        State(name = "role + moderator") { IdentityBadges(role = OreRole.Operator, moderator = true) }
        State(name = "role + crown") { IdentityBadges(role = OreRole.Syndicate, crown = true) }
        State(name = "lapsed, founding kept") { IdentityBadges(role = OreRole.Free, founding = true) }
        State(name = "all marks") {
            IdentityBadges(role = OreRole.Prestige, founding = true, moderator = true, crown = true)
        }
    }
}

@Composable
private fun ChannelListSection() {
    Section(title = "CHANNEL LIST") {
        State(name = "category header") { CategoryHeader(title = "SAMPLE CATEGORY") }
        State(name = "row") { ChannelRow(name = PreviewSample.Channel, onClick = {}) }
        State(name = "row, selected") { ChannelRow(name = PreviewSample.Channel, onClick = {}, selected = true) }
        State(name = "row, unread") { ChannelRow(name = PreviewSample.Channel, onClick = {}, unread = true) }
        State(name = "row, unread with count") {
            ChannelRow(name = PreviewSample.Channel, onClick = {}, unread = true, unreadCount = 12)
        }
        State(name = "unread badge") {
            Row(horizontalArrangement = Arrangement.spacedBy(OreTheme.space.sm), verticalAlignment = Alignment.CenterVertically) {
                UnreadBadge(count = 1)
                UnreadBadge(count = 12)
                UnreadBadge(count = 120)
            }
        }
    }
}

@Composable
private fun SampleBubble(
    role: OreRole = OreRole.Member,
    own: Boolean = false,
    edited: Boolean = false,
    deleted: Boolean = false,
    commercial: Boolean = false,
    stale: Boolean = false,
    founding: Boolean = false,
    moderator: Boolean = false,
    crown: Boolean = false,
    reactions: List<OreReaction> = emptyList(),
    reactionsEnabled: Boolean = false,
) {
    MessageBubble(
        authorName = PreviewSample.Name,
        authorRole = role,
        body = PreviewSample.Body,
        timestamp = PreviewSample.Time,
        modifier = Modifier.fillMaxWidth(),
        own = own,
        delivery = OreMessageDelivery.Delivered,
        edited = edited,
        deleted = deleted,
        commercial = commercial,
        stale = stale,
        founding = founding,
        moderator = moderator,
        crown = crown,
        reactions = reactions,
        reactionsEnabled = reactionsEnabled,
        onReactionToggle = {},
    )
}

@Composable
private fun MessageSection() {
    Section(title = "MESSAGE BUBBLE") {
        State(name = "other") { SampleBubble() }
        State(name = "own") { SampleBubble(own = true) }
        State(name = "edited") { SampleBubble(edited = true) }
        State(name = "commercial") { SampleBubble(commercial = true) }
        State(name = "stale") { SampleBubble(stale = true) }
        State(name = "all marks") { SampleBubble(role = OreRole.Prestige, founding = true, moderator = true, crown = true) }
        State(name = "reactions, enabled") { SampleBubble(reactions = PreviewSample.Reactions, reactionsEnabled = true) }
        State(name = "reactions, read-only") { SampleBubble(reactions = PreviewSample.Reactions) }
        State(name = "deleted flag") { SampleBubble(deleted = true) }
    }
    Section(title = "REACTION ROW") {
        State(name = "enabled") { ReactionRow(reactions = PreviewSample.Reactions, enabled = true, onToggle = {}) }
        State(name = "disabled") { ReactionRow(reactions = PreviewSample.Reactions, enabled = false, onToggle = {}) }
    }
    Section(title = "TOMBSTONE ROW") {
        State(name = "deleted") { TombstoneRow(kind = OreTombstoneKind.Deleted) }
        State(name = "removed") { TombstoneRow(kind = OreTombstoneKind.Removed) }
    }
}

@Composable
private fun MessageStatesSection() {
    Section(title = "MESSAGE STATES") {
        State(name = "sending") {
            SendingMessage(
                authorName = PreviewSample.Name,
                authorRole = OreRole.Member,
                body = PreviewSample.Body,
                timestamp = PreviewSample.Time,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        State(name = "queued, not sent") {
            QueuedMessage(
                authorName = PreviewSample.Name,
                authorRole = OreRole.Member,
                body = PreviewSample.Body,
                timestamp = PreviewSample.Time,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ComposerSection() {
    Section(title = "COMPOSER") {
        State(name = "enabled, empty") {
            Composer(value = "", onValueChange = {}, onSend = {}, state = ComposerState.Enabled)
        }
        State(name = "enabled, with text") {
            Composer(value = PreviewSample.Body, onValueChange = {}, onSend = {}, state = ComposerState.Enabled)
        }
        State(name = "disabled") {
            Composer(value = "", onValueChange = {}, onSend = {}, state = ComposerState.Disabled(PreviewSample.Note))
        }
        State(name = "upsell") {
            Composer(
                value = "",
                onValueChange = {},
                onSend = {},
                state = ComposerState.Upsell(PreviewSample.Note, PreviewSample.UpsellAction),
                onUpsellClick = {},
            )
        }
        State(name = "slowmode countdown") {
            Composer(value = "", onValueChange = {}, onSend = {}, state = ComposerState.Slowmode(42), onSlowmodeElapsed = {})
        }
    }
}

@Composable
private fun MemberRowSection() {
    Section(title = "MEMBER ROW") {
        State(name = "online") {
            MemberRow(displayName = PreviewSample.Name, role = OreRole.Member, presence = OrePresence.Online)
        }
        State(name = "online with activity") {
            MemberRow(displayName = PreviewSample.Name, role = OreRole.Operator, presence = OrePresence.Online, activity = PreviewSample.Activity)
        }
        State(name = "idle, founding") {
            MemberRow(displayName = PreviewSample.Name, role = OreRole.Syndicate, presence = OrePresence.Idle, founding = true)
        }
        State(name = "offline, free") {
            MemberRow(displayName = PreviewSample.Name, role = OreRole.Free, presence = OrePresence.Offline)
        }
        State(name = "all marks") {
            MemberRow(
                displayName = PreviewSample.Name,
                role = OreRole.Prestige,
                presence = OrePresence.Online,
                founding = true,
                moderator = true,
                crown = true,
            )
        }
    }
}

@Composable
private fun PresenceSection() {
    Section(title = "PRESENCE PANEL") {
        State(name = "dots + labels") {
            Row(horizontalArrangement = Arrangement.spacedBy(OreTheme.space.md), verticalAlignment = Alignment.CenterVertically) {
                OrePresence.values().forEach { presence ->
                    Row(horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs), verticalAlignment = Alignment.CenterVertically) {
                        PresenceDot(presence = presence)
                        OreText(
                            text = presenceLabel(presence),
                            style = OreTheme.type.caption,
                            color = OreTheme.color.text.secondary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
        OrePresence.values().forEach { presence ->
            PresenceSectionHeader(presence = presence, count = 3)
            PresenceRow(
                displayName = PreviewSample.Name,
                role = OreRole.Operator,
                presence = presence,
                activity = if (presence == OrePresence.Online) PreviewSample.Activity else null,
                founding = presence == OrePresence.Idle,
                crown = presence == OrePresence.Online,
            )
            PresenceRow(displayName = PreviewSample.Name, role = OreRole.Member, presence = presence, moderator = true)
        }
    }
}

@Composable
private fun CardsSection() {
    Section(title = "CARDS") {
        State(name = "info") { InfoCard(title = PreviewSample.Title, body = PreviewSample.Body) }
        OreAvailability.values().forEach { availability ->
            State(name = "availability / ${availability.name.lowercase()}") {
                AvailabilityCard(title = PreviewSample.Title, body = PreviewSample.Body, availability = availability)
            }
        }
        State(name = "sponsored") { SponsoredCard(title = PreviewSample.Title, body = PreviewSample.Body) }
    }
}

@Composable
private fun Swatches(name: String, colors: List<Color>) {
    Column(verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs)) {
        OreText(text = name, style = OreTheme.type.caption, color = OreTheme.color.text.secondary, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(OreTheme.space.xs)) {
            colors.forEach { color ->
                Box(
                    Modifier
                        .size(OreTheme.size.iconMedium)
                        .background(OreTheme.color.border.subtle)
                        .padding(OreTheme.size.strokeHairline)
                        .background(color),
                )
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OreTheme.space.md)) {
        OreText(text = title, style = OreTheme.type.overline, color = OreTheme.color.text.tertiary, maxLines = 1)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OreTheme.size.strokeHairline)
                .background(OreTheme.color.border.subtle),
        )
        content()
    }
}

@Composable
private fun State(
    name: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(OreTheme.space.xs)) {
        OreText(text = name, style = OreTheme.type.caption, color = OreTheme.color.text.secondary, maxLines = 1)
        content()
    }
}
