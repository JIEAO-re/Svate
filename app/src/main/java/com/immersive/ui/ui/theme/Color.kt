package com.immersive.ui.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Svate design tokens — the single source of truth for color.
 *
 * ChatGPT-style black-and-white: a neutral light canvas, near-black ink, and BLACK used as
 * the single point of emphasis (avatar, send, selected states) — no green, no chromatic
 * accents. Semantic states (danger/warning) are expressed in grayscale (inverted dark or
 * mid-gray), not color. Light-only by product decision; no dynamic color, no dark variant.
 */
object SvateColors {
    // Backgrounds / surfaces — neutral
    val Canvas = Color(0xFFF7F7F8)        // app background
    val Surface = Color(0xFFFFFFFF)       // cards, composer, assistant-area surfaces
    val SurfaceMuted = Color(0xFFF2F2F3)  // drawer / secondary panels

    // Text — neutral near-black ramp
    val TextPrimary = Color(0xFF171717)
    val TextSecondary = Color(0xFF6E6E78)
    val TextTertiary = Color(0xFF9A9AA2)
    val TextOnDark = Color(0xFFF7F7F7)    // on the dark user bubble / black buttons
    val TextOnAccent = Color(0xFFFFFFFF)  // on the black accent

    // Bold dark user bubble (strong contrast against the light canvas)
    val UserBubble = Color(0xFF171717)

    // Accent — BLACK, emphasis only (avatar, send, selected). No green.
    val Accent = Color(0xFF171717)
    val AccentDeep = Color(0xFF000000)    // text/stroke on light, deep/pressed states
    val AccentSoft = Color(0xFFEDEDED)    // soft gray fill: selected row, icon chips

    // Borders / dividers — hairlines
    val Border = Color(0xFFE5E5E5)
    val BorderStrong = Color(0xFFD4D4D4)
    val Divider = Color(0xFFECECEC)

    // Semantic — monochrome (no color): danger = inverted near-black, warning = mid-gray
    val Danger = Color(0xFF171717)
    val DangerSoft = Color(0xFFEDEDED)
    val DangerBorder = Color(0xFFD4D4D4)
    val Warning = Color(0xFF6E6E78)
    val WarningSoft = Color(0xFFEDEDED)

    // Loading / progress — black & white
    val LoadingInk = Color(0xFF171717)         // typing dots / pulse dot
    val TimelineDone = Color(0xFF171717)       // solid node  = completed step
    val TimelineActive = Color(0xFF171717)     // pulsing ring = current step
    val TimelinePending = Color(0xFFC9C9C9)    // hollow node = upcoming step
    val TimelineLine = Color(0xFFE3E3E3)       // vertical connector

    // Glass — translucent bar base; alpha applied at call sites in vertical gradients.
    val GlassTint = Color(0xFFF7F7F8)
    val GlassHighlight = Color(0xFFFFFFFF)
}

/** Corner-radius scale, kept in one place so component shapes stay consistent. */
object SvateShape {
    val Pill = RoundedCornerShape(26.dp)     // composer
    val Card = RoundedCornerShape(16.dp)     // cards, permission prompt, sheets
    val Bubble = RoundedCornerShape(20.dp)   // user message bubble
    val Field = RoundedCornerShape(12.dp)    // inputs, command card, list rows
    val Chip = RoundedCornerShape(14.dp)     // suggestion / attachment chips
    val Small = RoundedCornerShape(10.dp)    // badges, small buttons
}
