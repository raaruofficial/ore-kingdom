package com.raaru.orekingdom.designsystem.tokens

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable

/** Durations in milliseconds. */
@Immutable
data class OreDurations(
    /** Press feedback, reaction toggles. */
    val fast: Int = 120,
    /** State changes: enable/disable, badge appear, row selection. */
    val standard: Int = 200,
    /** Card and panel entrances. */
    val emphasized: Int = 320,
    /** One cycle of the sending-state pulse. */
    val pulse: Int = 1200,
    /** Slowmode countdown tick. */
    val countdownTick: Int = 1000,
)

@Immutable
data class OreEasings(
    /** Default for on-screen state changes. */
    val standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
    /** Entering elements. */
    val decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f),
    /** Exiting elements. */
    val accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f),
    /** Countdowns and continuous progress. */
    val linear: Easing = LinearEasing,
)

@Immutable
data class OreMotion(
    val duration: OreDurations = OreDurations(),
    val easing: OreEasings = OreEasings(),
)
