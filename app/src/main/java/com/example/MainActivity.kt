package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppLanguage
import com.example.ui.AppDestination
import com.example.ui.MiraBoxUiState
import com.example.ui.MiraBoxViewModel
import com.example.ui.components.LocalizedEnvironment
import com.example.ui.components.SecurityApprovalDialog
import com.example.ui.components.appString
import com.example.ui.screens.DisplaySettingsScreen
import com.example.ui.screens.MirrorStageScreen
import com.example.ui.screens.ReceiverHomeScreen
import com.example.ui.screens.RemoteControlScreen
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.MiraBoxTheme
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val viewModel: MiraBoxViewModel = viewModel()
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.onPermissionResult()
            }

            val requestWifiPermissions = {
                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                permissionLauncher.launch(perms)
            }

            MiraBoxTheme {
                LocalizedEnvironment(language = uiState.settings.language) {
                    MiraBoxAppShell(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestWifiPermission = requestWifiPermissions
                    )
                }
            }
        }
    }
}

@Composable
private fun MiraBoxAppShell(
    uiState: MiraBoxUiState,
    viewModel: MiraBoxViewModel,
    onRequestWifiPermission: () -> Unit
) {
    val isFullscreenStage =
        uiState.currentDestination == AppDestination.STAGE && uiState.activeSession != null

    val navItems = listOf(
        NavItem(
            destination = AppDestination.RECEIVER,
            label = appString(R.string.nav_receiver),
            icon = Icons.Rounded.Cast,
            testTag = "nav_receiver"
        ),
        NavItem(
            destination = AppDestination.STAGE,
            label = appString(R.string.nav_stage),
            icon = Icons.Rounded.DesktopWindows,
            testTag = "nav_stage"
        ),
        NavItem(
            destination = AppDestination.SETTINGS,
            label = appString(R.string.nav_settings),
            icon = Icons.Rounded.Tune,
            testTag = "nav_settings"
        ),
        NavItem(
            destination = AppDestination.REMOTE,
            label = appString(R.string.nav_remote),
            icon = Icons.Rounded.SettingsRemote,
            testTag = "nav_remote"
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianBg)
    ) {
        if (isFullscreenStage) {
            MirrorStageScreen(
                uiState = uiState,
                onAttachSurface = viewModel::attachVideoSurface,
                onRemoteKey = viewModel::handleStageRemoteKey,
                onToggleHud = viewModel::toggleStageHud,
                onSetDisplayMode = viewModel::setDisplayMode,
                onRotateCycle = viewModel::cycleLandscapeRotation,
                onToggleAirMouse = viewModel::toggleAirMouse,
                onDisconnect = viewModel::disconnectSession,
                onRunLoopback = viewModel::runLoopbackTest,
                onNavigateBack = { viewModel.navigateTo(AppDestination.RECEIVER) },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val useNavigationRail = maxWidth >= 680.dp

                if (useNavigationRail) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        NavigationRail(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(104.dp)
                                .border(1.dp, ObsidianBorder),
                            containerColor = ObsidianSurface,
                            header = {
                                Column(
                                    modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "MiraBox",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = ElectricCyan
                                    )
                                    LanguageQuickToggle(
                                        current = uiState.settings.language,
                                        onToggle = {
                                            val next = if (uiState.settings.language == AppLanguage.ARABIC) {
                                                AppLanguage.ENGLISH
                                            } else {
                                                AppLanguage.ARABIC
                                            }
                                            viewModel.setLanguage(next)
                                        }
                                    )
                                }
                            }
                        ) {
                            Spacer(modifier = Modifier.height(8.dp))
                            navItems.forEach { item ->
                                val selected = uiState.currentDestination == item.destination
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { viewModel.navigateTo(item.destination) },
                                    icon = {
                                        Icon(
                                            imageVector = item.icon,
                                            contentDescription = item.label
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = item.label,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = ElectricCyan,
                                        selectedTextColor = ElectricCyan,
                                        indicatorColor = ElectricCyanContainer,
                                        unselectedIconColor = TextSecondary,
                                        unselectedTextColor = TextSecondary
                                    ),
                                    modifier = Modifier.testTag(item.testTag)
                                )
                            }
                        }

                        DestinationContent(
                            uiState = uiState,
                            viewModel = viewModel,
                            onRequestWifiPermission = onRequestWifiPermission,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        )
                    }
                } else {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = ObsidianBg,
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ObsidianSurface)
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = appString(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = ElectricCyan
                                )
                                LanguageQuickToggle(
                                    current = uiState.settings.language,
                                    onToggle = {
                                        val next = if (uiState.settings.language == AppLanguage.ARABIC) {
                                            AppLanguage.ENGLISH
                                        } else {
                                            AppLanguage.ARABIC
                                        }
                                        viewModel.setLanguage(next)
                                    }
                                )
                            }
                        },
                        bottomBar = {
                            NavigationBar(
                                containerColor = ObsidianSurface,
                                modifier = Modifier.navigationBarsPadding()
                            ) {
                                navItems.forEach { item ->
                                    val selected = uiState.currentDestination == item.destination
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { viewModel.navigateTo(item.destination) },
                                        icon = {
                                            Icon(
                                                imageVector = item.icon,
                                                contentDescription = item.label
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = item.label,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = ElectricCyan,
                                            selectedTextColor = ElectricCyan,
                                            indicatorColor = ElectricCyanContainer,
                                            unselectedIconColor = TextSecondary,
                                            unselectedTextColor = TextSecondary
                                        ),
                                        modifier = Modifier.testTag(item.testTag)
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        DestinationContent(
                            uiState = uiState,
                            viewModel = viewModel,
                            onRequestWifiPermission = onRequestWifiPermission,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        )
                    }
                }
            }
        }

        // Global Security Approval Modal (Remote OK confirmation gate)
        val pending = uiState.pendingRequest
        if (pending != null) {
            SecurityApprovalDialog(
                request = pending,
                countdownSec = uiState.approvalCountdownSec,
                onApproveOnce = viewModel::approveOnce,
                onApproveAndTrust = viewModel::approveAndTrust,
                onDeny = viewModel::denyConnection
            )
        }
    }
}

@Composable
private fun DestinationContent(
    uiState: MiraBoxUiState,
    viewModel: MiraBoxViewModel,
    onRequestWifiPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (uiState.currentDestination) {
        AppDestination.RECEIVER -> ReceiverHomeScreen(
            uiState = uiState,
            onRequestWifiPermission = onRequestWifiPermission,
            onRestartServer = viewModel::startReceiver,
            onRunLoopbackTest = viewModel::runLoopbackTest,
            onOpenStage = { viewModel.navigateTo(AppDestination.STAGE) },
            onDisconnect = viewModel::disconnectSession,
            onScanPeers = viewModel::scanWifiDirectPeers,
            onInvitePeer = viewModel::invitePeer,
            modifier = modifier
        )

        AppDestination.STAGE -> MirrorStageScreen(
            uiState = uiState,
            onAttachSurface = viewModel::attachVideoSurface,
            onRemoteKey = viewModel::handleStageRemoteKey,
            onToggleHud = viewModel::toggleStageHud,
            onSetDisplayMode = viewModel::setDisplayMode,
            onRotateCycle = viewModel::cycleLandscapeRotation,
            onToggleAirMouse = viewModel::toggleAirMouse,
            onDisconnect = viewModel::disconnectSession,
            onRunLoopback = viewModel::runLoopbackTest,
            onNavigateBack = { viewModel.navigateTo(AppDestination.RECEIVER) },
            modifier = modifier
        )

        AppDestination.SETTINGS -> DisplaySettingsScreen(
            uiState = uiState,
            onSetDisplayMode = viewModel::setDisplayMode,
            onSetLandscapeRotation = viewModel::setLandscapeRotation,
            onSetAspectScaleMode = viewModel::setAspectScaleMode,
            onSetDexDpiProfile = viewModel::setDexDpiProfile,
            onSetDexTaskbarEnabled = viewModel::setDexTaskbarEnabled,
            onSetRequireOkApproval = viewModel::setRequireOkApproval,
            onUpdateReceiverName = viewModel::updateReceiverName,
            onRevokeTrustedDevice = viewModel::revokeTrustedDevice,
            onSetLanguage = viewModel::setLanguage,
            modifier = modifier
        )

        AppDestination.REMOTE -> RemoteControlScreen(
            uiState = uiState,
            onSetUibcMode = viewModel::setUibcControlMode,
            onSetAirMouseSpeed = viewModel::setAirMouseSpeed,
            onSendVirtualKey = viewModel::sendVirtualRemoteKey,
            modifier = modifier
        )
    }
}

@Composable
private fun LanguageQuickToggle(
    current: AppLanguage,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianCardElevated)
            .border(1.dp, ObsidianBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag("language_quick_toggle"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Language,
            contentDescription = "Language",
            tint = ElectricCyan,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = if (current == AppLanguage.ARABIC) "EN" else "عربي",
            style = MaterialTheme.typography.labelMedium,
            color = TextPrimary
        )
    }
}

private data class NavItem(
    val destination: AppDestination,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)
