package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.miracast.ServerState
import com.example.ui.MiraBoxUiState
import com.example.ui.components.PairingMatrixGraphic
import com.example.ui.components.TvGlassCard
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
import com.example.ui.components.appString
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun ReceiverHomeScreen(
    uiState: MiraBoxUiState,
    onRequestWifiPermission: () -> Unit,
    onRestartServer: () -> Unit,
    onRunLoopbackTest: () -> Unit,
    onOpenStage: () -> Unit,
    onDisconnect: () -> Unit,
    onScanPeers: () -> Unit,
    onInvitePeer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor by animateColorAsState(
        targetValue = when (uiState.serverState) {
            ServerState.LISTENING -> ElectricCyan
            ServerState.AWAITING_OK_APPROVAL -> AmberAlert
            ServerState.MIRRORING -> EmeraldMint
            ServerState.STOPPED -> CoralError
        },
        label = "statusBeacon"
    )

    val statusText = when (uiState.serverState) {
        ServerState.LISTENING -> appString(R.string.status_listening)
        ServerState.AWAITING_OK_APPROVAL -> appString(R.string.status_awaiting_ok)
        ServerState.MIRRORING -> appString(R.string.status_mirroring)
        ServerState.STOPPED -> appString(R.string.status_stopped)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 760.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Hero Banner Card with generated atmospheric backdrop
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.5.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.img_receiver_backdrop),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(
                                        ObsidianBg.copy(alpha = 0.94f),
                                        ObsidianBg.copy(alpha = 0.78f),
                                        ObsidianBg.copy(alpha = 0.55f)
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = statusText,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = statusColor
                                )
                            }

                            if (uiState.settings.requireOkApproval) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(ObsidianCardElevated.copy(alpha = 0.9f))
                                        .border(1.dp, AmberAlert.copy(alpha = 0.5f), RoundedCornerShape(50))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Security,
                                        contentDescription = null,
                                        tint = AmberAlert,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "OK Gate ON",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = AmberAlert
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = uiState.settings.receiverName,
                                    style = MaterialTheme.typography.displayLarge,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = appString(R.string.receiver_subtitle),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = TextSecondary
                                )
                            }

                            if (isWide) {
                                Spacer(modifier = Modifier.width(16.dp))
                                PairingMatrixGraphic(
                                    payload = "miracast://${uiState.endpoints.wifiIpAddress}:${uiState.endpoints.rtspPort}/${uiState.settings.receiverName}"
                                )
                            }
                        }

                        // Endpoint Telemetry Chips
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EndpointBadge(
                                icon = Icons.Rounded.Wifi,
                                label = appString(R.string.wifi_ip_label),
                                value = uiState.endpoints.wifiIpAddress,
                                modifier = Modifier.weight(1f)
                            )
                            EndpointBadge(
                                icon = Icons.Rounded.Router,
                                label = appString(R.string.rtsp_port_label),
                                value = "TCP ${uiState.endpoints.rtspPort}",
                                modifier = Modifier.weight(1f)
                            )
                            EndpointBadge(
                                icon = Icons.Rounded.SettingsRemote,
                                label = appString(R.string.uibc_port_label),
                                value = "TCP ${uiState.uibcTelemetry.listeningPort}",
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Primary Actions
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (uiState.activeSession != null) {
                                TvPrimaryButton(
                                    text = appString(R.string.btn_open_stage),
                                    onClick = onOpenStage,
                                    containerColor = EmeraldMint,
                                    modifier = Modifier.testTag("open_stage_button"),
                                    leadingIcon = {
                                        Icon(Icons.Rounded.DesktopWindows, contentDescription = null)
                                    }
                                )
                                TvSecondaryButton(
                                    text = appString(R.string.btn_disconnect),
                                    onClick = onDisconnect,
                                    borderColor = CoralError.copy(alpha = 0.6f),
                                    contentColor = CoralError,
                                    modifier = Modifier.testTag("disconnect_session_button")
                                )
                            } else {
                                TvPrimaryButton(
                                    text = appString(R.string.btn_loopback_test),
                                    onClick = onRunLoopbackTest,
                                    modifier = Modifier.testTag("loopback_test_button"),
                                    leadingIcon = {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    }
                                )
                                TvSecondaryButton(
                                    text = appString(R.string.btn_restart_server),
                                    onClick = onRestartServer,
                                    modifier = Modifier.testTag("restart_server_button"),
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Runtime Permission Prompt if Wi-Fi Direct access is not yet granted
            if (!uiState.p2pStatus.hasPermission) {
                item {
                    TvGlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onRequestWifiPermission
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.WifiTethering,
                                    contentDescription = null,
                                    tint = AmberAlert,
                                    modifier = Modifier.size(28.dp)
                                )
                                Column {
                                    Text(
                                        text = appString(R.string.permission_title),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = appString(R.string.permission_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            TvPrimaryButton(
                                text = appString(R.string.permission_grant_btn),
                                onClick = onRequestWifiPermission,
                                containerColor = AmberAlert,
                                modifier = Modifier.testTag("grant_wifi_permission_button")
                            )
                        }
                    }
                }
            }

            // Two-column layout on TV / Widescreen, stacked on compact phones
            item {
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PairingInstructionsCard(
                            receiverName = uiState.settings.receiverName,
                            p2pStatusText = if (uiState.p2pStatus.hasPermission && uiState.p2pStatus.isP2pEnabled) {
                                appString(R.string.p2p_ready)
                            } else if (!uiState.p2pStatus.hasPermission) {
                                appString(R.string.p2p_permission_needed)
                            } else {
                                appString(R.string.p2p_unavailable)
                            },
                            nsdText = if (uiState.endpoints.nsdRegistered) {
                                appString(R.string.nsd_registered)
                            } else {
                                appString(R.string.nsd_pending)
                            },
                            modifier = Modifier.weight(1f)
                        )
                        NearbyPeersCard(
                            uiState = uiState,
                            onScanPeers = onScanPeers,
                            onInvitePeer = onInvitePeer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PairingInstructionsCard(
                            receiverName = uiState.settings.receiverName,
                            p2pStatusText = if (uiState.p2pStatus.hasPermission && uiState.p2pStatus.isP2pEnabled) {
                                appString(R.string.p2p_ready)
                            } else if (!uiState.p2pStatus.hasPermission) {
                                appString(R.string.p2p_permission_needed)
                            } else {
                                appString(R.string.p2p_unavailable)
                            },
                            nsdText = if (uiState.endpoints.nsdRegistered) {
                                appString(R.string.nsd_registered)
                            } else {
                                appString(R.string.nsd_pending)
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        NearbyPeersCard(
                            uiState = uiState,
                            onScanPeers = onScanPeers,
                            onInvitePeer = onInvitePeer,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // Live RTSP Protocol Log Feed
            if (uiState.protocolLogs.isNotEmpty()) {
                item {
                    TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "RTSP / WFD Sink Log",
                            style = MaterialTheme.typography.titleMedium,
                            color = ElectricCyan
                        )
                        uiState.protocolLogs.takeLast(5).forEach { line ->
                            Text(
                                text = "• $line",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianCard.copy(alpha = 0.85f))
            .border(1.dp, ObsidianBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PairingInstructionsCard(
    receiverName: String,
    p2pStatusText: String,
    nsdText: String,
    modifier: Modifier = Modifier
) {
    TvGlassCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.CastConnected,
                contentDescription = null,
                tint = ElectricCyan
            )
            Text(
                text = appString(R.string.how_to_connect_title),
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )
        }
        Text(text = appString(R.string.step_1), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = appString(R.string.step_2, receiverName), style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = appString(R.string.step_3), style = MaterialTheme.typography.bodyMedium, color = EmeraldMint)

        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = appString(R.string.wifi_direct_status_label),
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
            Text(
                text = p2pStatusText,
                style = MaterialTheme.typography.labelMedium,
                color = ElectricCyan
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = appString(R.string.nsd_status_label),
                style = MaterialTheme.typography.labelMedium,
                color = TextMuted
            )
            Text(
                text = nsdText,
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldMint
            )
        }
    }
}

@Composable
private fun NearbyPeersCard(
    uiState: MiraBoxUiState,
    onScanPeers: () -> Unit,
    onInvitePeer: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TvGlassCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhoneAndroid,
                    contentDescription = null,
                    tint = ElectricCyan
                )
                Text(
                    text = appString(R.string.peers_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
            }
            TvSecondaryButton(
                text = appString(R.string.btn_scan_peers),
                onClick = onScanPeers,
                modifier = Modifier.testTag("scan_peers_button")
            )
        }

        if (uiState.p2pStatus.peers.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ObsidianCardElevated)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = appString(R.string.peers_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        } else {
            uiState.p2pStatus.peers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianCardElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${peer.deviceAddress} • ${peer.statusLabel}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (peer.isMiracastCapable) EmeraldMint else TextMuted
                        )
                    }
                    TvPrimaryButton(
                        text = appString(R.string.btn_connect_peer),
                        onClick = { onInvitePeer(peer.deviceAddress) },
                        modifier = Modifier.testTag("invite_peer_${peer.deviceAddress}")
                    )
                }
            }
        }
    }
}
