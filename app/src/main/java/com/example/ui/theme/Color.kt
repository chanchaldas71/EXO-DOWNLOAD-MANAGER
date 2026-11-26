package com.example.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.data.model.NetworkTransportType

// Deep Obsidian Dark Palette
val PlexoDarkBackground = Color(0xFF090E1A)
val PlexoDarkSurface = Color(0xFF101829)
val PlexoDarkSurfaceVariant = Color(0xFF172238)
val PlexoDarkSurfaceElevated = Color(0xFF1E2C47)
val PlexoDarkOutline = Color(0xFF283A5A)
val PlexoDarkOnSurface = Color(0xFFF1F5F9)
val PlexoDarkOnSurfaceVariant = Color(0xFF94A3B8)

// Crisp Alabaster Light Palette
val PlexoLightBackground = Color(0xFFF3F6FB)
val PlexoLightSurface = Color(0xFFFFFFFF)
val PlexoLightSurfaceVariant = Color(0xFFE7EEF7)
val PlexoLightOutline = Color(0xFFCBD5E1)
val PlexoLightOnSurface = Color(0xFF0F172A)
val PlexoLightOnSurfaceVariant = Color(0xFF475569)

// Primary / Accent Brand Tokens
val CyanElectric = Color(0xFF22D3EE)
val CyanDeep = Color(0xFF0284C7)
val EmeraldMint = Color(0xFF34D399)
val EmeraldDeep = Color(0xFF059669)
val AmberPulse = Color(0xFFFB923C)
val AmberDeep = Color(0xFFD97706)
val VioletSignal = Color(0xFFA78BFA)
val VioletDeep = Color(0xFF7C3AED)
val CoralError = Color(0xFFF87171)
val CoralErrorDeep = Color(0xFFDC2626)

object PlexoNetworkColors {
    fun colorFor(transportType: NetworkTransportType, isDark: Boolean = true): Color = when (transportType) {
        NetworkTransportType.WIFI -> if (isDark) CyanElectric else CyanDeep
        NetworkTransportType.CELLULAR -> if (isDark) AmberPulse else AmberDeep
        NetworkTransportType.ETHERNET -> if (isDark) EmeraldMint else EmeraldDeep
        NetworkTransportType.VPN, NetworkTransportType.OTHER -> if (isDark) VioletSignal else VioletDeep
    }

    fun colorForIndex(colorIndex: Int, isDark: Boolean = true): Color = when (colorIndex) {
        0 -> if (isDark) CyanElectric else CyanDeep
        1 -> if (isDark) AmberPulse else AmberDeep
        2 -> if (isDark) EmeraldMint else EmeraldDeep
        else -> if (isDark) VioletSignal else VioletDeep
    }
}
