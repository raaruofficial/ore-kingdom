package com.raaru.orekingdom.designsystem.tokens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LocalOreColors = staticCompositionLocalOf { OreDarkColors }
private val LocalOreAlpha = staticCompositionLocalOf { OreAlpha() }
private val LocalOreTypography = staticCompositionLocalOf { OreTypography() }
private val LocalOreSpacing = staticCompositionLocalOf { OreSpacing() }
private val LocalOreSize = staticCompositionLocalOf { OreSize() }
private val LocalOreShapes = staticCompositionLocalOf { OreShapes() }
private val LocalOreElevation = staticCompositionLocalOf { OreElevation() }
private val LocalOreMotion = staticCompositionLocalOf { OreMotion() }

/**
 * Single access point for design tokens, e.g. `OreTheme.color.role.operator`.
 * Dark-first: [OreDarkColors] is the only scheme.
 */
object OreTheme {
    val color: OreColors
        @Composable @ReadOnlyComposable get() = LocalOreColors.current
    val alpha: OreAlpha
        @Composable @ReadOnlyComposable get() = LocalOreAlpha.current
    val type: OreTypography
        @Composable @ReadOnlyComposable get() = LocalOreTypography.current
    val space: OreSpacing
        @Composable @ReadOnlyComposable get() = LocalOreSpacing.current
    val size: OreSize
        @Composable @ReadOnlyComposable get() = LocalOreSize.current
    val shape: OreShapes
        @Composable @ReadOnlyComposable get() = LocalOreShapes.current
    val elevation: OreElevation
        @Composable @ReadOnlyComposable get() = LocalOreElevation.current
    val motion: OreMotion
        @Composable @ReadOnlyComposable get() = LocalOreMotion.current
}

@Composable
fun OreTheme(
    colors: OreColors = OreDarkColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalOreColors provides colors,
        LocalOreAlpha provides OreAlpha(),
        LocalOreTypography provides OreTypography(),
        LocalOreSpacing provides OreSpacing(),
        LocalOreSize provides OreSize(),
        LocalOreShapes provides OreShapes(),
        LocalOreElevation provides OreElevation(),
        LocalOreMotion provides OreMotion(),
        content = content,
    )
}
