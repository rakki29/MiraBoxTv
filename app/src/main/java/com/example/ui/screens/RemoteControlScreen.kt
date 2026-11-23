package com.example.ui.screens

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.UibcControlMode
import com.example.ui.MiraBoxUiState
import com.example.ui.components.TvGlassCard
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
import com.example.ui.components.TvSelectableChip
import com.example.ui.components.appString
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RemoteControlScreen(
    uiState: MiraBoxUiState,
    onSetUibcMode: (UibcControlMode) -> Unit,
    onSetAirMouseSpeed: (Float) -> Unit,
    onSendVirtualKey: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    val telemetry = uiState.uibcTelemetry

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SettingsRemote,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(30.dp)
                )
                Column {
                    Text(
                        text = appString(R.string.remote_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = appString(R.string.remote_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
            }
        }

        // UIBC Input Mode & Air-Mouse Speed
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = appString(R.string.uibc_mode_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvSelectableChip(
                        label = appString(R.string.uibc_hybrid),
                        selected = settings.uibcControlMode == UibcControlMode.HYBRID_AUTO,
                        onClick = { onSetUibcMode(UibcControlMode.HYBRID_AUTO) },
                        modifier = Modifier.testTag("uibc_hybrid_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.uibc_dpad),
                        selected = settings.uibcControlMode == UibcControlMode.DPAD_KEYS,
                        onClick = { onSetUibcMode(UibcControlMode.DPAD_KEYS) },
                        modifier = Modifier.testTag("uibc_dpad_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.uibc_mouse),
                        selected = settings.uibcControlMode == UibcControlMode.AIR_MOUSE_POINTER,
                        onClick = { onSetUibcMode(UibcControlMode.AIR_MOUSE_POINTER) },
                        modifier = Modifier.testTag("uibc_mouse_chip")
                    )
                }

                Text(
                    text = "${appString(R.string.cursor_speed_label)}: ${"%.1f".format(settings.airMouseSpeed)}x",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Slider(
                    value = settings.airMouseSpeed,
                    onValueChange = onSetAirMouseSpeed,
                    valueRange = 0.5f..2.2f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricCyan,
                        activeTrackColor = ElectricCyan
                    ),
                    modifier = Modifier.testTag("air_mouse_speed_slider")
                )
            }
        }

        // Live UIBC Telemetry Card
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = appString(R.string.uibc_telemetry_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = ElectricCyan
                    )
                    Text(
                        text = if (telemetry.isClientConnected) {
                            appString(R.string.uibc_client_connected, telemetry.clientAddress ?: "")
                        } else {
                            appString(R.string.uibc_client_waiting, telemetry.listeningPort)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = if (telemetry.isClientConnected) EmeraldMint else TextMuted
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianCardElevated)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = appString(R.string.uibc_packets_sent, telemetry.packetsSent),
                        style = MaterialTheme.typography.labelLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = appString(R.string.uibc_last_event, telemetry.lastPacketSummary),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ElectricCyan
                    )
                    if (telemetry.lastPacketHex.isNotEmpty()) {
                        Text(
                            text = "WFD UIBC Hex: ${telemetry.lastPacketHex}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextSecondary
                        )
                    }
                }
            }
        }

        // Interactive Remote Pad to Test UIBC Forwarding
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = appString(R.string.remote_pad_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvSecondaryButton(
                        text = appString(R.string.remote_btn_up),
                        onClick = { onSendVirtualKey(KeyEvent.KEYCODE_DPAD_UP) },
                        leadingIcon = { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null) },
                        modifier = Modifier.testTag("virtual_dpad_up")
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TvSecondaryButton(
                            text = appString(R.string.remote_btn_left),
                            onClick = { onSendVirtualKey(KeyEvent.KEYCODE_DPAD_LEFT) },
                            leadingIcon = { Icon(Icons.Rounded.KeyboardArrowLeft, contentDescription = null) },
                            modifier = Modifier.testTag("virtual_dpad_left")
                        )
                        TvPrimaryButton(
                            text = appString(R.string.remote_btn_ok),
                            onClick = { onSendVirtualKey(KeyEvent.KEYCODE_DPAD_CENTER) },
                            modifier = Modifier.testTag("virtual_dpad_ok")
                        )
                        TvSecondaryButton(
                            text = appString(R.string.remote_btn_right),
                            onClick = { onSendVirtualKey(KeyEvent.KEYCODE_DPAD_RIGHT) },
                            leadingIcon = { Icon(Icons.Rounded.KeyboardArrowRight, contentDescription = null) },
                            modifier = Modifier.testTag("virtual_dpad_right")
                        )
                    }

                    TvSecondaryButton(
                        text = appString(R.string.remote_btn_down),
                        onClick = { onSendVirtualKey(KeyEvent.KEYCODE_DPAD_DOWN) },
                        leadingIcon = { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null) },
                        modifier = Modifier.testTag("virtual_dpad_down")
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvSecondaryButton(
                            text = appString(R.string.remote_btn_back),
                            onClick = { onSendVirtualKey(KeyEvent.KEYCODE_BACK) },
                            leadingIcon = { Icon(Icons.Rounded.ArrowBack, contentDescription = null) },
                            modifier = Modifier.testTag("virtual_btn_back")
                        )
                        TvSecondaryButton(
                            text = appString(R.string.remote_btn_home),
                            onClick = { onSendVirtualKey(KeyEvent.KEYCODE_HOME) },
                            leadingIcon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                            modifier = Modifier.testTag("virtual_btn_home")
                        )
                        TvSecondaryButton(
                            text = appString(R.string.remote_btn_recents),
                            onClick = { onSendVirtualKey(KeyEvent.KEYCODE_APP_SWITCH) },
                            leadingIcon = { Icon(Icons.Rounded.Apps, contentDescription = null) },
                            modifier = Modifier.testTag("virtual_btn_recents")
                        )
                    }
                }
            }
        }

        // Key Mapping Guide
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = appString(R.string.map_dpad),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = appString(R.string.map_ok),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = appString(R.string.map_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = appString(R.string.map_menu),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Text(
                    text = appString(R.string.map_vol),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}
