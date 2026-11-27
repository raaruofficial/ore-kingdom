package com.raaru.orekingdom.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** Raw palette. Private: never referenced outside this file. */
private object Palette {
    val ink975 = Color(0xFF090B0D)
    val ink950 = Color(0xFF0E1013)
    val ink900 = Color(0xFF15181D)
    val ink850 = Color(0xFF1C2026)
    val ink800 = Color(0xFF232830)
    val ink750 = Color(0xFF2E343D)
    val ink700 = Color(0xFF1F242B)
    val scrim = Color(0xB3050607)

    val fog100 = Color(0xFFE8EBEF)
    val fog300 = Color(0xFFA9B1BC)
    val fog500 = Color(0xFF7A838F)
    val fog700 = Color(0xFF555D68)

    val amber400 = Color(0xFFF2A03D)
    val amber500 = Color(0xFFD98A2B)
    val amber200 = Color(0xFFF5BE78)
    val amber900 = Color(0xFF2A1F10)
    val amberInk = Color(0xFF1A1206)

    val slate400 = Color(0xFF8E97A3)
    val blue400 = Color(0xFF5FA8F5)
    val teal400 = Color(0xFF2FCFA6)
    val violet400 = Color(0xFFA586FF)
    val platinum200 = Color(0xFFF4E3B2)
    val rose400 = Color(0xFFF26FA8)
    val copper400 = Color(0xFFC98A5E)

    val crown400 = Color(0xFFFFD23F)
    val crown900 = Color(0xFF2B2408)

    val ice300 = Color(0xFF7FE3F0)
    val ice100 = Color(0xFFD9F6FA)
    val ice900 = Color(0xFF0F2227)
    val iceGlow = Color(0x337FE3F0)

    val lime300 = Color(0xFFC5D86D)
    val lime900 = Color(0xFF232A12)

    val steel300 = Color(0xFF9FB2C8)
    val steel900 = Color(0xFF1A2028)

    val neutral200 = Color(0xFFC9CED6)
    val neutral800 = Color(0xFF2A2F38)

    val green400 = Color(0xFF3DD68C)
    val yellow400 = Color(0xFFE6B450)
    val yellow900 = Color(0xFF2A2210)
    val red400 = Color(0xFFF26D6D)
    val red900 = Color(0xFF2B1517)
    val sky400 = Color(0xFF6FB1F5)
    val sky900 = Color(0xFF12202E)

    val ownBubble = Color(0xFF1A2029)
}

/** Content + container pair for labels, badges and status chips. */
@Immutable
data class OreTone(
    val content: Color,
    val container: Color,
)

@Immutable
data class OreSurfaceColors(
    val base: Color,
    val sunken: Color,
    val raised: Color,
    val overlay: Color,
    val highlight: Color,
    val scrim: Color,
)

@Immutable
data class OreTextColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val disabled: Color,
    val onAccent: Color,
)

@Immutable
data class OreAccentColors(
    val primary: Color,
    val primaryPressed: Color,
    val onPrimary: Color,
    val subtle: Color,
    val onSubtle: Color,
)

@Immutable
data class OreBorderColors(
    val subtle: Color,
    val strong: Color,
    val focus: Color,
)

@Immutable
data class OreRoleColors(
    val free: Color,
    val member: Color,
    val operator: Color,
    val syndicate: Color,
    val prestige: Color,
    val moderator: Color,
    val founding: Color,
)

@Immutable
data class OrePresenceColors(
    val online: Color,
    val idle: Color,
    val offline: Color,
)

@Immutable
data class OreStatusColors(
    val error: OreTone,
    val warning: OreTone,
    val info: OreTone,
)

@Immutable
data class OreMessageColors(
    val bubble: Color,
    val bubbleOwn: Color,
    val tombstone: Color,
    val tombstoneContent: Color,
    val reactionChip: Color,
    val reactionChipMine: Color,
    val reactionChipMineBorder: Color,
    val sending: Color,
    val queued: OreTone,
    val stale: OreTone,
)

/** Paid spotlight treatment. Deliberately shares no hue with [OreLabelColors.nativePin]. */
@Immutable
data class OreSpotlightColors(
    val container: Color,
    val border: Color,
    val glow: Color,
    val content: Color,
    val label: Color,
)

@Immutable
data class OreLabelColors(
    val commercial: OreTone,
    val sponsored: OreTone,
    val crown: OreTone,
    val nativePin: OreTone,
)

@Immutable
data class OreColors(
    val surface: OreSurfaceColors,
    val text: OreTextColors,
    val accent: OreAccentColors,
    val border: OreBorderColors,
    val role: OreRoleColors,
    val presence: OrePresenceColors,
    val status: OreStatusColors,
    val message: OreMessageColors,
    val spotlight: OreSpotlightColors,
    val label: OreLabelColors,
)

/** Opacity tokens applied to semantic colours (never to raw values). */
@Immutable
data class OreAlpha(
    val disabled: Float = 0.38f,
    val sending: Float = 0.60f,
    val pressed: Float = 0.12f,
    val tintContainer: Float = 0.16f,
)

val OreDarkColors = OreColors(
    surface = OreSurfaceColors(
        base = Palette.ink950,
        sunken = Palette.ink975,
        raised = Palette.ink900,
        overlay = Palette.ink850,
        highlight = Palette.ink800,
        scrim = Palette.scrim,
    ),
    text = OreTextColors(
        primary = Palette.fog100,
        secondary = Palette.fog300,
        tertiary = Palette.fog500,
        disabled = Palette.fog700,
        onAccent = Palette.amberInk,
    ),
    accent = OreAccentColors(
        primary = Palette.amber400,
        primaryPressed = Palette.amber500,
        onPrimary = Palette.amberInk,
        subtle = Palette.amber900,
        onSubtle = Palette.amber200,
    ),
    border = OreBorderColors(
        subtle = Palette.ink700,
        strong = Palette.ink750,
        focus = Palette.amber400,
    ),
    role = OreRoleColors(
        free = Palette.slate400,
        member = Palette.blue400,
        operator = Palette.teal400,
        syndicate = Palette.violet400,
        prestige = Palette.platinum200,
        moderator = Palette.rose400,
        founding = Palette.copper400,
    ),
    presence = OrePresenceColors(
        online = Palette.green400,
        idle = Palette.yellow400,
        offline = Palette.fog700,
    ),
    status = OreStatusColors(
        error = OreTone(content = Palette.red400, container = Palette.red900),
        warning = OreTone(content = Palette.yellow400, container = Palette.yellow900),
        info = OreTone(content = Palette.sky400, container = Palette.sky900),
    ),
    message = OreMessageColors(
        bubble = Palette.ink900,
        bubbleOwn = Palette.ownBubble,
        tombstone = Palette.ink975,
        tombstoneContent = Palette.fog700,
        reactionChip = Palette.ink850,
        reactionChipMine = Palette.amber900,
        reactionChipMineBorder = Palette.amber500,
        sending = Palette.fog500,
        queued = OreTone(content = Palette.yellow400, container = Palette.yellow900),
        stale = OreTone(content = Palette.fog300, container = Palette.neutral800),
    ),
    spotlight = OreSpotlightColors(
        container = Palette.ice900,
        border = Palette.ice300,
        glow = Palette.iceGlow,
        content = Palette.ice100,
        label = Palette.ice300,
    ),
    label = OreLabelColors(
        commercial = OreTone(content = Palette.lime300, container = Palette.lime900),
        sponsored = OreTone(content = Palette.neutral200, container = Palette.neutral800),
        crown = OreTone(content = Palette.crown400, container = Palette.crown900),
        nativePin = OreTone(content = Palette.steel300, container = Palette.steel900),
    ),
)
