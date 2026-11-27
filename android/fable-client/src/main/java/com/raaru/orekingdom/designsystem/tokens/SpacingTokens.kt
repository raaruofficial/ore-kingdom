package com.raaru.orekingdom.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 4dp-grid spacing scale. */
@Immutable
data class OreSpacing(
    val none: Dp = 0.dp,
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
)

/** Fixed component dimensions. */
@Immutable
data class OreSize(
    val touchTarget: Dp = 48.dp,
    val buttonHeight: Dp = 44.dp,
    val channelRowHeight: Dp = 40.dp,
    val categoryHeaderHeight: Dp = 32.dp,
    val memberRowHeight: Dp = 48.dp,
    val composerMinHeight: Dp = 52.dp,
    val avatarSmall: Dp = 24.dp,
    val avatarMedium: Dp = 36.dp,
    val iconSmall: Dp = 16.dp,
    val iconMedium: Dp = 20.dp,
    val presenceDot: Dp = 10.dp,
    val badgeHeight: Dp = 18.dp,
    val reactionChipHeight: Dp = 28.dp,
    val strokeHairline: Dp = 1.dp,
    val strokeEmphasis: Dp = 2.dp,
)
