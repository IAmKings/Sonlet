package com.sonlet.app.ui.theme

import androidx.compose.ui.graphics.Color

// DESIGN.md §3:Sonlet 品牌扩展色(浅色模式)
val LightPrimary = Color(0xFFBC482F)
val LightOnPrimary = Color(0xFFFFFFFF)
val LightPrimaryContainer = Color(0xFFFFDBD1)
val LightOnPrimaryContainer = Color(0xFF3D0A00)
val LightSecondary = Color(0xFF56645C)
val LightOnSecondary = Color(0xFFFFFFFF)
val LightSecondaryContainer = Color(0xFFE3E7DF)
val LightOnSecondaryContainer = Color(0xFF202B28)
val LightTertiary = Color(0xFF2D674C)
val LightOnTertiary = Color(0xFFFFFFFF)
val LightTertiaryContainer = Color(0xFFD3EEE0)
val LightOnTertiaryContainer = Color(0xFF0B2A1C)
val LightBackground = Color(0xFFF7F5EF)
val LightOnBackground = Color(0xFF202B28)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF202B28)
val LightSurfaceVariant = Color(0xFFE3E7DF)
val LightOnSurfaceVariant = Color(0xFF56645C)
val LightOutline = Color(0xFF7A877F)
val LightOutlineVariant = Color(0xFFE3E7DF)
val LightError = Color(0xFFB3261E)
val LightOnError = Color(0xFFFFFFFF)
val LightErrorContainer = Color(0xFFFCEAE6)
val LightOnErrorContainer = Color(0xFF410E0B)
val LightInverseSurface = Color(0xFF202B28)
val LightInverseOnSurface = Color(0xFFF7F5EF)

// DESIGN.md §3:Sonlet 品牌扩展色(深色模式)
val DarkPrimary = Color(0xFFF59C7E)
val DarkOnPrimary = Color(0xFF202B28)
val DarkPrimaryContainer = Color(0xFF6E2E1B)
val DarkOnPrimaryContainer = Color(0xFFFFDBD1)
val DarkSecondary = Color(0xFFB8C4BC)
val DarkOnSecondary = Color(0xFF202B28)
val DarkSecondaryContainer = Color(0xFF3A4841)
val DarkOnSecondaryContainer = Color(0xFFF7F5EF)
val DarkTertiary = Color(0xFFA0D4B0)
val DarkOnTertiary = Color(0xFF0B2A1C)
val DarkTertiaryContainer = Color(0xFF234A37)
val DarkOnTertiaryContainer = Color(0xFFC8E8D0)
val DarkBackground = Color(0xFF161E1B)
val DarkOnBackground = Color(0xFFF7F5EF)
val DarkSurface = Color(0xFF202B28)
val DarkOnSurface = Color(0xFFF7F5EF)
val DarkSurfaceVariant = Color(0xFF3A4841)
val DarkOnSurfaceVariant = Color(0xFFB8C4BC)
val DarkOutline = Color(0xFF84948A)
val DarkOutlineVariant = Color(0xFF3A4841)
val DarkError = Color(0xFFFFB4AB)
val DarkOnError = Color(0xFF202B28)
val DarkErrorContainer = Color(0xFF40201C)
val DarkOnErrorContainer = Color(0xFFFFDAD4)
val DarkInverseSurface = Color(0xFFF7F5EF)
val DarkInverseOnSurface = Color(0xFF202B28)

// DESIGN.md §3 语义扩展:成功与警告(M3 ColorScheme 无对应槽位,单独承载)
data class SonletExtendedColors(
    val success: Color,
    val warning: Color,
)

val SonletExtendedLight = SonletExtendedColors(
    success = Color(0xFF2D674C),
    warning = Color(0xFF7B5D19),
)

val SonletExtendedDark = SonletExtendedColors(
    success = Color(0xFFA0D4B0),
    warning = Color(0xFFE8C775),
)
