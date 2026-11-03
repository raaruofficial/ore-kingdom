package com.raaru.orekingdom.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ore Kingdom theme — "Obsidian & Ore" (FLAGSHIP_DESIGN §2–§4):
 * deep warm-black mineral surfaces lit by the concept-4 crown's
 * amber; the gem cyan appears only where prestige, rarity, or the
 * brand mark itself is present. System font stack only — the feel
 * comes from scale, weight, and tracking, not a novelty face. No
 * Solana marks or logos anywhere (estate non-endorsement posture).
 */

// --- Colour tokens (FLAGSHIP_DESIGN §2) -----------------------------

val OreBackground = Color(0xFF0B0A08)
val OreSurface = Color(0xFF161310)
val OreSurfaceRaised = Color(0xFF1E1913)
val OreSurfaceVariant = Color(0xFF221D16)
val OreVein = Color(0xFF2A2115)
val OreEdge = Color(0xFF2E2820)
val OreAmber = Color(0xFFF0A832)
val OreAmberBright = Color(0xFFFFB84D)
val OreBronze = Color(0xFF8A5A1B)
val OreGem = Color(0xFF37E0D8)
val OreGemDim = Color(0xFF1B4A47)
val OnOre = Color(0xFFF2EDE4)
val OnOreMuted = Color(0xFFC4BBA9)
val OnOreFaint = Color(0xFF8E8471)
val OnAccent = Color(0xFF1A1206)
val OreError = Color(0xFFFF7A72)
val OreConfirmed = Color(0xFF3ECF8E)

/** Kept name for the gem accent (pre-overhaul call sites). */
val OreCyan = OreGem

private val OreColors = darkColorScheme(
    primary = OreAmber,
    onPrimary = OnAccent,
    primaryContainer = OreVein,
    onPrimaryContainer = OnOre,
    secondary = OreGem,
    onSecondary = OnAccent,
    secondaryContainer = OreGemDim,
    onSecondaryContainer = OnOre,
    tertiary = OreGem,
    background = OreBackground,
    onBackground = OnOre,
    surface = OreSurface,
    onSurface = OnOre,
    surfaceVariant = OreSurfaceVariant,
    onSurfaceVariant = OnOreMuted,
    surfaceContainerLowest = OreBackground,
    surfaceContainerLow = OreSurface,
    surfaceContainer = OreSurface,
    surfaceContainerHigh = OreSurfaceRaised,
    surfaceContainerHighest = OreSurfaceVariant,
    outline = OreEdge,
    outlineVariant = OreEdge,
    error = OreError
)

// --- Typography (FLAGSHIP_DESIGN §3) --------------------------------

private val OreTypography = Typography(
    displaySmall = TextStyle(
        fontSize = 32.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
)

/** The house label voice (FLAGSHIP_DESIGN §3): caps eyebrows —
 *  PINNED, COMMERCIAL, board names, type labels. Callers pass the
 *  label already uppercased. */
val Typography.labelCaps: TextStyle
    get() = TextStyle(
        fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
    )

/** Money is tabular (FLAGSHIP_DESIGN §3): every amount, price, and
 *  count renders with tabular figures so quote columns align like a
 *  ledger. */
fun TextStyle.tnum(): TextStyle = copy(fontFeatureSettings = "tnum")

// --- Shapes (FLAGSHIP_DESIGN §4) -------------------------------------
// Cards 18dp, hero/quote plaques 20dp, sheets and dialogs 24dp,
// fields 16dp; chips, buttons and marks are full-round (set at the
// call site). Elevation: none — tonal rung + hairline only.

private val OreShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun OreKingdomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OreColors, typography = OreTypography, shapes = OreShapes) {
        content()
    }
}
