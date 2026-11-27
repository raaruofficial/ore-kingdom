package com.raaru.orekingdom.designsystem.tokens

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Corner scale. */
@Immutable
data class OreShapes(
    /** Badges, label chips. */
    val xs: Shape = RoundedCornerShape(4.dp),
    /** Channel rows, buttons, reaction chips. */
    val sm: Shape = RoundedCornerShape(8.dp),
    /** Message bubbles, composer field. */
    val md: Shape = RoundedCornerShape(12.dp),
    /** Cards, spotlight frame. */
    val lg: Shape = RoundedCornerShape(16.dp),
    /** Pills, avatars, presence dots. */
    val pill: Shape = CircleShape,
)

/** Elevation scale. Dark-first: depth is carried mainly by surface tone; shadows stay low. */
@Immutable
data class OreElevation(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 3.dp,
    val level3: Dp = 6.dp,
)
