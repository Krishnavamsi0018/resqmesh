package com.resqmesh.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Colour tokens from the design spec (3.2). Dynamic colour stays off so emergency colours never change. */
object Tokens {
    val CatGeneral = Color(0xFFB3261E)
    val CatMedical = Color(0xFFC2185B)
    val CatFlood = Color(0xFF1565C0)
    val CatFire = Color(0xFFE65100)
    val CatTrapped = Color(0xFF6A1B9A)
    val CatWater = Color(0xFF00838F)

    val StatusWaiting = Color(0xFFB26A00)
    val StatusRelayed = Color(0xFF3949AB)
    val StatusNotified = Color(0xFF2E7D32)
    val StatusAcked = Color(0xFF1B5E20)
    val StatusClosed = Color(0xFF5F6368)
    val StatusRejected = Color(0xFFB3261E)
}

private val Light = lightColorScheme(
    primary = Tokens.CatGeneral,
    onPrimary = Color.White,
    secondary = Tokens.StatusRelayed,
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF5F5F5),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF5F6368),
    outline = Color(0xFFDADCE0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF2B8B5),
    onPrimary = Color(0xFF601410),
    secondary = Color(0xFFBAC3FF),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF1E1E1E),
    onBackground = Color(0xFFF1F1F1),
    onSurface = Color(0xFFF1F1F1),
    onSurfaceVariant = Color(0xFFB0B4B8),
    outline = Color(0xFF3C4043),
)

@Composable
fun ResQMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
