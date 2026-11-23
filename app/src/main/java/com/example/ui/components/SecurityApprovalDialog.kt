package com.example.ui.components

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.miracast.ConnectionChannel
import com.example.miracast.PendingConnectionRequest
import com.example.ui.theme.AmberAlert
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Full-screen TV Remote OK Security Approval Gate.
 * Focus defaults to Approve (OK) so the TV Box owner can press OK on their remote
 * to authorize the incoming phone mirroring session, or navigate to Deny / Trust Always.
 */
@Composable
fun SecurityApprovalDialog(
    request: PendingConnectionRequest,
    countdownSec: Int,
    onApproveOnce: () -> Unit,
    onApproveAndTrust: () -> Unit,
    onDeny: () -> Unit
) {
    val approveFocusRequester = remember { FocusRequester() }

    LaunchedEffect(request.sessionId) {
        runCatching { approveFocusRequester.requestFocus() }
    }

    BackHandler {
        onDeny()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg.copy(alpha = 0.86f))
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK
                ) {
                    onDeny()
                    true
                } else {
                    false
                }
            }
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 580.dp)
                .fillMaxWidth()
                .border(2.dp, AmberAlert.copy(alpha = 0.8f), RoundedCornerShape(26.dp)),
            shape = RoundedCornerShape(26.dp),
            color = ObsidianCard,
            tonalElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(AmberContainer)
                            .border(1.5.dp, AmberAlert, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Security,
                            contentDescription = appString(R.string.security_title),
                            tint = AmberAlert,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = appString(R.string.security_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = appString(R.string.security_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }

                // Connection Details Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianCardElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailRow(
                        label = appString(R.string.security_device_label),
                        value = request.deviceName,
                        highlight = true
                    )
                    DetailRow(
                        label = appString(R.string.security_address_label),
                        value = request.remoteAddress
                    )
                    DetailRow(
                        label = appString(R.string.security_channel_label),
                        value = when (request.channel) {
                            ConnectionChannel.WIFI_LAN_RTSP -> "Wi-Fi LAN Miracast (RTSP)"
                            ConnectionChannel.WIFI_DIRECT_P2P -> "Wi-Fi Direct (P2P)"
                            ConnectionChannel.LOCAL_LOOPBACK_TEST -> "Local Loopback Test (127.0.0.1)"
                        }
                    )
                    DetailRow(
                        label = appString(R.string.security_pin_label),
                        value = request.pinCode,
                        highlight = true
                    )
                }

                // Countdown bar
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = appString(R.string.security_countdown, countdownSec),
                            style = MaterialTheme.typography.labelMedium,
                            color = AmberAlert
                        )
                        Text(
                            text = "OK / ENTER",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricCyan
                        )
                    }
                    LinearProgressIndicator(
                        progress = { (countdownSec / 35f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = AmberAlert,
                        trackColor = ObsidianBorder
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TvPrimaryButton(
                        text = appString(R.string.security_btn_approve),
                        onClick = onApproveOnce,
                        containerColor = EmeraldMint,
                        contentColor = ObsidianBg,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(approveFocusRequester)
                            .testTag("approve_ok_button"),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    )

                    TvSecondaryButton(
                        text = appString(R.string.security_btn_trust),
                        onClick = onApproveAndTrust,
                        borderColor = ElectricCyan.copy(alpha = 0.6f),
                        contentColor = ElectricCyan,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("trust_approve_button"),
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.VerifiedUser,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }

                TvSecondaryButton(
                    text = appString(R.string.security_btn_deny),
                    onClick = onDeny,
                    borderColor = CoralError.copy(alpha = 0.6f),
                    contentColor = CoralError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("deny_connection_button"),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    highlight: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
            color = if (highlight) ElectricCyan else TextPrimary
        )
    }
}
