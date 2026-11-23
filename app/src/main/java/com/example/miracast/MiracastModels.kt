package com.example.miracast

enum class ServerState {
    STOPPED,
    LISTENING,
    AWAITING_OK_APPROVAL,
    MIRRORING
}

enum class ConnectionChannel {
    WIFI_LAN_RTSP,
    WIFI_DIRECT_P2P,
    LOCAL_LOOPBACK_TEST
}

data class PendingConnectionRequest(
    val sessionId: String,
    val deviceName: String,
    val deviceId: String,
    val remoteAddress: String,
    val channel: ConnectionChannel,
    val pinCode: String,
    val requestedAtMs: Long = System.currentTimeMillis()
)

sealed class ApprovalDecision {
    data object ApproveOnce : ApprovalDecision()
    data object ApproveAndTrust : ApprovalDecision()
    data object Deny : ApprovalDecision()
}

data class ActiveMirrorSession(
    val sessionId: String,
    val deviceName: String,
    val remoteAddress: String,
    val channel: ConnectionChannel,
    val rtpPort: Int,
    val uibcPort: Int,
    val sourceWidth: Int = 1080,
    val sourceHeight: Int = 2400,
    val isLoopbackDiagnostic: Boolean = false,
    val connectedAtMs: Long = System.currentTimeMillis()
)

data class DiscoveredP2pPeer(
    val deviceName: String,
    val deviceAddress: String,
    val isMiracastCapable: Boolean,
    val statusLabel: String
)

data class WifiDirectStatus(
    val hasPermission: Boolean = false,
    val isP2pEnabled: Boolean = false,
    val isGroupFormed: Boolean = false,
    val groupName: String? = null,
    val groupOwnerIp: String? = null,
    val wfdHardwareSupported: Boolean = false,
    val peers: List<DiscoveredP2pPeer> = emptyList(),
    val statusNote: String = ""
)

data class UibcTelemetry(
    val listeningPort: Int = 7237,
    val isClientConnected: Boolean = false,
    val clientAddress: String? = null,
    val packetsSent: Int = 0,
    val lastPacketSummary: String = "-",
    val lastPacketHex: String = ""
)

data class NetworkEndpoints(
    val wifiIpAddress: String = "0.0.0.0",
    val rtspPort: Int = 7236,
    val uibcPort: Int = 7237,
    val rtpVideoPort: Int = 19000,
    val nsdRegistered: Boolean = false,
    val nsdServiceName: String = ""
)
