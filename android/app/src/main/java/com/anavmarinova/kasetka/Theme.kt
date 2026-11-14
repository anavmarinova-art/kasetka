package com.anavmarinova.kasetka

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Light, dark, and the whimsy overlay for each. */
data class Pal(
    val dark: Boolean,
    val bg: Brush,
    val bgSolid: Color,
    val panel: Color,
    val panel2: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val deckTop: Color,
    val deckBottom: Color,
    val door: Color,
    val keyTop: Color,
    val key: Color,
    val keyEdge: Color,
    val keyInk: Color,
    val lcdBg: Color,
    val lcdFg: Color,
    val ridge1: Color,
    val ridge2: Color,
    val caseBg: Color,
    val line: Color,
    val stars: List<Color>,
    val round: Boolean,
)

private fun solid(c: Color) = Brush.verticalGradient(listOf(c, c))

fun palette(s: Settings): Pal {
    val dark = s.theme != "light"
    return when {
        dark && !s.whimsy -> Pal(true, solid(Color(0xFF121318)), Color(0xFF121318), Color(0xFF1C1E26), Color(0xFF262935),
            Color(0xFFECEEF4), Color(0xFF9097A8), Color(0xFFFF7A3D), Color(0xFF3A3D47), Color(0xFF24262E), Color(0xFF0C0D11),
            Color(0xFF5A5E6B), Color(0xFF3F424C), Color(0xFF1D1F25), Color(0xFFE8E9EE), Color(0xFF0F1A12), Color(0xFF7DFF9A),
            Color(0xFF25272D), Color(0xFF4A4D57), Color(0x12FFFFFF), Color(0x1AFFFFFF),
            listOf(Color(0xFFFFD54A), Color(0xFFFFF3B0), Color(0xFFFFB347)), false)
        !dark && !s.whimsy -> Pal(false, solid(Color(0xFFECE8E1)), Color(0xFFECE8E1), Color(0xFFF8F6F2), Color(0xFFFFFFFF),
            Color(0xFF22242B), Color(0xFF6B7080), Color(0xFFE2543A), Color(0xFFDEDBD4), Color(0xFFBDB9B0), Color(0xFF2A2A2E),
            Color(0xFFFBFAF7), Color(0xFFE3E0D9), Color(0xFFA7A399), Color(0xFF2B2D33), Color(0xFFC8D4B4), Color(0xFF26331C),
            Color(0xFF8F8B82), Color(0xFFD3CFC6), Color(0x0F000000), Color(0x1A000000),
            listOf(Color(0xFFF5B301), Color(0xFFFF8A3D), Color(0xFFFFD54A)), false)
        !dark -> Pal(false,
            Brush.linearGradient(listOf(Color(0xFFFFE0F2), Color(0xFFF1E9FF), Color(0xFFDDF5FF))), Color(0xFFF6EDFF),
            Color(0xB3FFFFFF), Color(0xFFFFFFFF), Color(0xFF3A2350), Color(0xFF8A6FA3), Color(0xFFFF4FA3),
            Color(0xFFFFF2FA), Color(0xFFE6D9FF), Color(0xFF3B2558), Color(0xFFFFFFFF), Color(0xFFFFD6EC), Color(0xFFD79ABF),
            Color(0xFF5A2D72), Color(0xFF2B1840), Color(0xFFFF9BE0), Color(0xFFE7A9CF), Color(0xFFFFF0F8), Color(0x1AFF4FA3),
            Color(0x26783CA0), whimsyStars, true)
        else -> Pal(true,
            Brush.linearGradient(listOf(Color(0xFF3B1D5E), Color(0xFF120B22), Color(0xFF0F3B55))), Color(0xFF120B22),
            Color(0xB8281846), Color(0xFF2C1B4D), Color(0xFFFBEAFF), Color(0xFFB9A2D6), Color(0xFFFF5FD2),
            Color(0xFF3A2463), Color(0xFF22153D), Color(0xFF0A0614), Color(0xFF6B4AA6), Color(0xFF4B3080), Color(0xFF24163F),
            Color(0xFFFFE9FB), Color(0xFF08121F), Color(0xFF6FF7FF), Color(0xFF2A1748), Color(0xFF7A55C0), Color(0x1AFF5FD2),
            Color(0x24FFC8FF), whimsyStars, true)
    }
}

private val whimsyStars = listOf(Color(0xFFFF8AD8), Color(0xFF8AF3FF), Color(0xFFB7A6FF), Color(0xFFFFF07A), Color(0xFF9EFFA8))
