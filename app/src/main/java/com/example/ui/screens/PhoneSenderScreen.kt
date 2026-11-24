package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.miracast.DiscoveredReceiver
import com.example.miracast.SenderState
import com.example.sender.MiraBoxRemoteAccessibilityService
import com.example.ui.MiraBoxUiState
import com.example.ui.components.TvGlassCard
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
import com.example.ui.components.appString
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun PhoneSenderScreen(
    uiState: MiraBoxUiState,
    onRefreshDiscovery: () -> Unit,
    onStartCast: (host: String, rtspPort: Int, uibcPort: Int, videoPort: Int, targetName: String) -> Unit,
    onStopCast: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sender = uiState.senderStatus
    val accessibilityActive = MiraBoxRemoteAccessibilityService.isEnabled()
    var manualHost by remember { mutableStateOf("192.168.1.") }
    var manualPort by remember { mutableStateOf("7236") }

    val statusColor = when (sender.state) {
        SenderState.IDLE -> ElectricCyan
        SenderState.AWAITING_TV_OK -> AmberAlert
        SenderState.STREAMING -> EmeraldMint
        SenderState.ERROR -> CoralError
    }

    val statusLabel = when (sender.state) {
        SenderState.IDLE -> appString(R.string.sender_status_idle)
        SenderState.AWAITING_TV_OK -> appString(R.string.sender_status_connecting)
        SenderState.STREAMING -> appString(R.string.sender_status_streaming, sender.targetName)
        SenderState.ERROR -> sender.errorMessage ?: "Error"
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header & Live Sender State
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor
                        )
                    }
                    if (sender.state == SenderState.STREAMING || sender.state == SenderState.AWAITING_TV_OK) {
                        TvPrimaryButton(
                            text = appString(R.string.sender_btn_stop_cast),
                            onClick = onStopCast,
                            containerColor = CoralError,
                            contentColor = Color.White,
                            leadingIcon = { Icon(Icons.Rounded.Stop, contentDescription = null) },
                            modifier = Modifier.testTag("sender_stop_cast_button")
                        )
                    }
                }

                Text(
                    text = appString(R.string.sender_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = TextPrimary
                )
                Text(
                    text = appString(R.string.sender_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                if (sender.state == SenderState.STREAMING) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianCardElevated)
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Frames: ${sender.framesStreamed}",
                            style = MaterialTheme.typography.labelLarge,
                            color = EmeraldMint
                        )
                        Text(
                            text = "UIBC Cmds: ${sender.uibcEventsExecuted} (${sender.lastRemoteEvent})",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                    }
                }
            }
        }

        // Accessibility Remote Control Card
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
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
                            imageVector = if (accessibilityActive) Icons.Rounded.CheckCircle else Icons.Rounded.AccessibilityNew,
                            contentDescription = null,
                            tint = if (accessibilityActive) EmeraldMint else AmberAlert,
                            modifier = Modifier.size(28.dp)
                        )
                        Column {
                            Text(
                                text = if (accessibilityActive) {
                                    appString(R.string.sender_accessibility_enabled)
                                } else {
                                    appString(R.string.sender_accessibility_title)
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = appString(R.string.sender_accessibility_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                    if (!accessibilityActive) {
                        TvPrimaryButton(
                            text = appString(R.string.sender_accessibility_btn),
                            onClick = onOpenAccessibilitySettings,
                            containerColor = AmberAlert,
                            modifier = Modifier.testTag("enable_accessibility_button")
                        )
                    }
                }
            }
        }

        // Auto-Discovered MiraBox TVs via NSD
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.Tv, contentDescription = null, tint = ElectricCyan)
                        Text(
                            text = appString(R.string.sender_discovered_tvs),
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary
                        )
                    }
                    TvSecondaryButton(
                        text = appString(R.string.btn_scan_peers),
                        onClick = onRefreshDiscovery,
                        leadingIcon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                        modifier = Modifier.testTag("sender_refresh_discovery")
                    )
                }

                if (uiState.discoveredReceivers.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianCardElevated)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = appString(R.string.sender_scanning),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                } else {
                    uiState.discoveredReceivers.forEach { rx: DiscoveredReceiver ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ObsidianCardElevated)
                                .border(1.dp, ObsidianBorder, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = rx.serviceName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "${rx.hostAddress}:${rx.rtspPort}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextMuted
                                )
                            }
                            TvPrimaryButton(
                                text = appString(R.string.sender_btn_connect_cast),
                                onClick = {
                                    onStartCast(
                                        rx.hostAddress,
                                        rx.rtspPort,
                                        rx.uibcPort,
                                        rx.tcpVideoPort,
                                        rx.serviceName
                                    )
                                },
                                leadingIcon = { Icon(Icons.Rounded.Cast, contentDescription = null) },
                                modifier = Modifier.testTag("connect_discovered_${rx.hostAddress}")
                            )
                        }
                    }
                }
            }
        }

        // Direct IP Fallback
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = appString(R.string.sender_manual_ip_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = manualHost,
                        onValueChange = { manualHost = it },
                        singleLine = true,
                        label = { Text("IP (192.168.x.x)") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = ObsidianBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .weight(2f)
                            .testTag("sender_manual_ip_input")
                    )
                    OutlinedTextField(
                        value = manualPort,
                        onValueChange = { manualPort = it },
                        singleLine = true,
                        label = { Text(appString(R.string.sender_manual_port_label)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = ObsidianBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("sender_manual_port_input")
                    )
                }
                TvPrimaryButton(
                    text = appString(R.string.sender_btn_connect_cast),
                    onClick = {
                        val port = manualPort.trim().toIntOrNull() ?: 7236
                        val host = manualHost.trim()
                        if (host.isNotEmpty()) {
                            onStartCast(host, port, 7237, 19001, host)
                        }
                    },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sender_manual_connect_button")
                )
            }
        }
    }
}
