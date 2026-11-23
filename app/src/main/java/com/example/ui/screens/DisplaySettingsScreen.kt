package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.AppLanguage
import com.example.data.AspectScaleMode
import com.example.data.DexDpiProfile
import com.example.data.DisplayMode
import com.example.data.LandscapeRotation
import com.example.ui.MiraBoxUiState
import com.example.ui.components.TvGlassCard
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
import com.example.ui.components.TvSelectableChip
import com.example.ui.components.appString
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DisplaySettingsScreen(
    uiState: MiraBoxUiState,
    onSetDisplayMode: (DisplayMode) -> Unit,
    onSetLandscapeRotation: (LandscapeRotation) -> Unit,
    onSetAspectScaleMode: (AspectScaleMode) -> Unit,
    onSetDexDpiProfile: (DexDpiProfile) -> Unit,
    onSetDexTaskbarEnabled: (Boolean) -> Unit,
    onSetRequireOkApproval: (Boolean) -> Unit,
    onUpdateReceiverName: (String) -> Unit,
    onRevokeTrustedDevice: (String) -> Unit,
    onSetLanguage: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    val settings = uiState.settings
    var nameDraft by remember(settings.receiverName) { mutableStateOf(settings.receiverName) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = appString(R.string.settings_display_section),
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
        }

        // 3 Display Transformation Mode Cards
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ModeOptionCard(
                    title = appString(R.string.mode_standard_title),
                    desc = appString(R.string.mode_standard_desc),
                    icon = Icons.Rounded.StayCurrentPortrait,
                    selected = settings.displayMode == DisplayMode.STANDARD_MIRROR,
                    onClick = { onSetDisplayMode(DisplayMode.STANDARD_MIRROR) },
                    modifier = Modifier.testTag("mode_standard_card")
                )
                ModeOptionCard(
                    title = appString(R.string.mode_landscape_title),
                    desc = appString(R.string.mode_landscape_desc),
                    icon = Icons.Rounded.StayCurrentLandscape,
                    selected = settings.displayMode == DisplayMode.FORCE_LANDSCAPE,
                    onClick = { onSetDisplayMode(DisplayMode.FORCE_LANDSCAPE) },
                    modifier = Modifier.testTag("mode_landscape_card")
                )
                ModeOptionCard(
                    title = appString(R.string.mode_dex_title),
                    desc = appString(R.string.mode_dex_desc),
                    icon = Icons.Rounded.DesktopWindows,
                    selected = settings.displayMode == DisplayMode.DEX_TV_DESKTOP,
                    onClick = { onSetDisplayMode(DisplayMode.DEX_TV_DESKTOP) },
                    modifier = Modifier.testTag("mode_dex_card")
                )
            }
        }

        // Forced Landscape Rotation & Scaling Controls
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = appString(R.string.rotation_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvSelectableChip(
                        label = appString(R.string.rot_auto),
                        selected = settings.landscapeRotation == LandscapeRotation.AUTO_LANDSCAPE,
                        onClick = { onSetLandscapeRotation(LandscapeRotation.AUTO_LANDSCAPE) },
                        modifier = Modifier.testTag("rot_auto_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.rot_0),
                        selected = settings.landscapeRotation == LandscapeRotation.ROT_0,
                        onClick = { onSetLandscapeRotation(LandscapeRotation.ROT_0) },
                        modifier = Modifier.testTag("rot_0_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.rot_90),
                        selected = settings.landscapeRotation == LandscapeRotation.ROT_90,
                        onClick = { onSetLandscapeRotation(LandscapeRotation.ROT_90) },
                        modifier = Modifier.testTag("rot_90_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.rot_270),
                        selected = settings.landscapeRotation == LandscapeRotation.ROT_270,
                        onClick = { onSetLandscapeRotation(LandscapeRotation.ROT_270) },
                        modifier = Modifier.testTag("rot_270_chip")
                    )
                }

                Text(
                    text = appString(R.string.aspect_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvSelectableChip(
                        label = appString(R.string.aspect_fit),
                        selected = settings.aspectScaleMode == AspectScaleMode.FIT_16_9,
                        onClick = { onSetAspectScaleMode(AspectScaleMode.FIT_16_9) },
                        modifier = Modifier.testTag("aspect_fit_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.aspect_fill),
                        selected = settings.aspectScaleMode == AspectScaleMode.FILL_WIDESCREEN,
                        onClick = { onSetAspectScaleMode(AspectScaleMode.FILL_WIDESCREEN) },
                        modifier = Modifier.testTag("aspect_fill_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.aspect_stretch),
                        selected = settings.aspectScaleMode == AspectScaleMode.STRETCH_FULL,
                        onClick = { onSetAspectScaleMode(AspectScaleMode.STRETCH_FULL) },
                        modifier = Modifier.testTag("aspect_stretch_chip")
                    )
                }
            }
        }

        // DeX / Desktop EDID Profile & Taskbar
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = appString(R.string.dex_profile_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvSelectableChip(
                        label = appString(R.string.dex_1080p),
                        selected = settings.dexDpiProfile == DexDpiProfile.TV_1080P_160DPI,
                        onClick = { onSetDexDpiProfile(DexDpiProfile.TV_1080P_160DPI) },
                        modifier = Modifier.testTag("dex_1080p_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.dex_4k),
                        selected = settings.dexDpiProfile == DexDpiProfile.TV_4K_240DPI,
                        onClick = { onSetDexDpiProfile(DexDpiProfile.TV_4K_240DPI) },
                        modifier = Modifier.testTag("dex_4k_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.dex_720p),
                        selected = settings.dexDpiProfile == DexDpiProfile.COMPACT_720P_120DPI,
                        onClick = { onSetDexDpiProfile(DexDpiProfile.COMPACT_720P_120DPI) },
                        modifier = Modifier.testTag("dex_720p_chip")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = appString(R.string.dex_taskbar_toggle),
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = appString(R.string.dex_taskbar_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = settings.dexTaskbarEnabled,
                        onCheckedChange = onSetDexTaskbarEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianBg,
                            checkedTrackColor = ElectricCyan
                        ),
                        modifier = Modifier.testTag("dex_taskbar_switch")
                    )
                }
            }
        }

        // Security & Box Identity Section
        item {
            Text(
                text = appString(R.string.settings_security_section),
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

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
                            imageVector = Icons.Rounded.Security,
                            contentDescription = null,
                            tint = EmeraldMint,
                            modifier = Modifier.size(26.dp)
                        )
                        Column {
                            Text(
                                text = appString(R.string.security_ok_toggle),
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = appString(R.string.security_ok_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                    Switch(
                        checked = settings.requireOkApproval,
                        onCheckedChange = onSetRequireOkApproval,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = ObsidianBg,
                            checkedTrackColor = EmeraldMint
                        ),
                        modifier = Modifier.testTag("require_ok_approval_switch")
                    )
                }

                // Receiver Name Editor
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text(appString(R.string.receiver_name_label)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = ElectricCyan,
                            unfocusedBorderColor = ObsidianBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("receiver_name_input")
                    )
                    TvPrimaryButton(
                        text = "OK",
                        onClick = { onUpdateReceiverName(nameDraft) },
                        modifier = Modifier.testTag("save_receiver_name_button")
                    )
                }

                // Trusted Devices List
                Text(
                    text = appString(R.string.trusted_devices_label, settings.trustedDevices.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (settings.trustedDevices.isEmpty()) {
                    Text(
                        text = appString(R.string.trusted_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                } else {
                    settings.trustedDevices.forEach { devId ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = devId,
                                style = MaterialTheme.typography.labelLarge,
                                color = ElectricCyan
                            )
                            TvSecondaryButton(
                                text = appString(R.string.btn_revoke_trust),
                                onClick = { onRevokeTrustedDevice(devId) },
                                borderColor = CoralError.copy(alpha = 0.6f),
                                contentColor = CoralError,
                                leadingIcon = {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null)
                                },
                                modifier = Modifier.testTag("revoke_trust_$devId")
                            )
                        }
                    }
                }
            }
        }

        // Language Toggle Card
        item {
            TvGlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Language,
                        contentDescription = null,
                        tint = ElectricCyan
                    )
                    Text(
                        text = appString(R.string.language_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvSelectableChip(
                        label = appString(R.string.lang_arabic),
                        selected = settings.language == AppLanguage.ARABIC,
                        onClick = { onSetLanguage(AppLanguage.ARABIC) },
                        modifier = Modifier.testTag("lang_arabic_chip")
                    )
                    TvSelectableChip(
                        label = appString(R.string.lang_english),
                        selected = settings.language == AppLanguage.ENGLISH,
                        onClick = { onSetLanguage(AppLanguage.ENGLISH) },
                        modifier = Modifier.testTag("lang_english_chip")
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeOptionCard(
    title: String,
    desc: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvGlassCard(
        modifier = modifier.fillMaxWidth(),
        selected = selected,
        onClick = onClick
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) ElectricCyan else TextSecondary,
                modifier = Modifier.size(30.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (selected) ElectricCyan else TextPrimary
                )
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
    }
}
