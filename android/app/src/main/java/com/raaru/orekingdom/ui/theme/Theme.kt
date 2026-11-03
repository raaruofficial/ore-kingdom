package com.raaru.orekingdom.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Ore Kingdom theme: near-black surfaces with the concept-4 mark's
 * amber ore and cyan gem accents. Colours only — no Solana marks or
 * logos anywhere (estate non-endorsement posture).
 */
val OreAmber = Color(0xFFF0A832)
val OreCyan = Color(0xFF37E0D8)
val OreBackground = Color(0xFF0B0A08)
val OreSurface = Color(0xFF161310)
val OreSurfaceVariant = Color(0xFF221D16)

private val OreColors = darkColorScheme(
    primary = OreAmber,
    secondary = OreCyan,
    tertiary = OreCyan,
    background = OreBackground,
    surface = OreSurface,
    surfaceVariant = OreSurfaceVariant,
    onBackground = Color(0xFFF2EDE4),
    onSurface = Color(0xFFF2EDE4),
    onSurfaceVariant = Color(0xFFC4BBA9),
    error = Color(0xFFFF6B6B)
)

@Composable
fun OreKingdomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OreColors, content = content)
}
