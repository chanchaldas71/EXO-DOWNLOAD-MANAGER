package com.example.data.model

enum class DownloadStatus {
    DOWNLOADING,
    QUEUED,
    PAUSED,
    COMPLETED,
    FAILED,
    EXPIRED_LINK,
    VERIFYING;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == EXPIRED_LINK

    val isActiveOrPending: Boolean
        get() = this == DOWNLOADING || this == QUEUED || this == VERIFYING
}

enum class NetworkTransportType(
    val displayName: String,
    val defaultMaxStreams: Int,
    val colorIndex: Int
) {
    WIFI("Wi-Fi", 8, 0),
    CELLULAR("5G / Cellular", 4, 1),
    ETHERNET("USB Ethernet", 16, 2),
    VPN("VPN Tunnel", 6, 3),
    OTHER("Network", 4, 3)
}

enum class ChunkState {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

enum class HashAlgorithm(val jcaName: String, val label: String) {
    NONE("", "None"),
    SHA256("SHA-256", "SHA-256"),
    MD5("MD5", "MD5")
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    DARK("Dark"),
    LIGHT("Light")
}

enum class ReachabilityState(val label: String) {
    READY("Ready"),
    PROBING("Probing…"),
    UNREACHABLE("Can't reach server"),
    PARKED("Parked (unstable)"),
    DISABLED("Disabled"),
    METERED_OFF("Metered (off)")
}
