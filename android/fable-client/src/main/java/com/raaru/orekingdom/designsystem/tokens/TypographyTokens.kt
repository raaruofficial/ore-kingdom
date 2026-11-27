package com.raaru.orekingdom.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val OreFontFamily = FontFamily.Default

@Immutable
data class OreTypography(
    /** Card titles, panel headings. */
    val titleMedium: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    /** Author names, member names, compact headings. */
    val titleSmall: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    /** Message body, composer input, channel names. */
    val body: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    /** Emphasised body (e.g. unread channel name). */
    val bodyStrong: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    /** Buttons and interactive labels. */
    val label: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
    ),
    /** Timestamps, notes, activity strings, state captions. */
    val caption: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    /** Category headers, presence group headers. */
    val overline: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.8.sp,
    ),
    /** Badge and label chips. */
    val badge: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.4.sp,
    ),
    /** Countdowns and counts; tabular figures to prevent jitter. */
    val numeric: TextStyle = TextStyle(
        fontFamily = OreFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    ),
)
