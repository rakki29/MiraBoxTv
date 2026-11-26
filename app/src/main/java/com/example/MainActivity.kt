package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
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
import androidx.compose.material.icons.rounded.PhoneAndroid
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppLanguage
import com.example.sender.ScreenCastSenderService
import com.example.sender.SecondScreenController
import com.example.ui.AppDestination
import com.example.ui.MiraBoxUiState
import com.example.ui.MiraBoxViewModel
import com.example.ui.components.LocalizedEnvironment
import com.example.ui.components.SecurityApprovalDialog
import com.example.ui.components.appString
import com.example.ui.screens.DisplaySettingsScreen
import com.example.ui.screens.MirrorStageScreen
import com.example.ui.screens.PhoneSenderScreen
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

private data class PendingCastTarget(
    val host: String,
    val rtspPort: Int,
    val uibcPort: Int,
    val videoPort: Int,
    val targetName: String
)

class MainActivity : ComponentActivity() {

    private var activeViewModel: MiraBoxViewModel? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val vm = activeViewModel
        val action = event.action
        if (vm != null &&
            (action == KeyEvent.ACTION_DOWN || action == KeyEvent.ACTION_UP) &&
            vm.shouldInterceptStageKeyInActivity(event.keyCode)
        ) {
            if (vm.handleStageRemoteKey(event.keyCode, action == KeyEvent.ACTION_DOWN, event.repeatCount)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val context = LocalContext.current
            val viewModel: MiraBoxViewModel = viewModel()
            activeViewModel = viewModel
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()

            var pendingCastTarget by remember { mutableStateOf<PendingCastTarget?>(null) }

            val wifiPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                viewModel.onPermissionResult()
            }

            val projectionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val target = pendingCastTarget
                val data = result.data
                if (result.resultCode == Activity.RESULT_OK && data != null && target != null) {
                    val intent = Intent(context, ScreenCastSenderService::class.java).apply {
                        action = ScreenCastSenderService.ACTION_START
                        putExtra(ScreenCastSenderService.EXTRA_RESULT_CODE, result.resultCode)
                        putExtra(ScreenCastSenderService.EXTRA_RESULT_DATA, data)
                        putExtra(ScreenCastSenderService.EXTRA_HOST, target.host)
                        putExtra(ScreenCastSenderService.EXTRA_RTSP_PORT, target.rtspPort)
                        putExtra(ScreenCastSenderService.EXTRA_UIBC_PORT, target.uibcPort)
                        putExtra(ScreenCastSenderService.EXTRA_VIDEO_PORT, target.videoPort)
                        putExtra(ScreenCastSenderService.EXTRA_AUDIO_PORT, 19002)
                        putExtra(ScreenCastSenderService.EXTRA_TARGET_NAME, target.targetName)
                        putExtra(
                            ScreenCastSenderService.EXTRA_SECOND_SCREEN_PROFILE,
                            uiState.settings.secondScreenProfile.name
                        )
                        putExtra(
                            ScreenCastSenderService.EXTRA_LAUNCH_TABLET_LAUNCHER,
                            uiState.settings.launchTabletLauncherOnCast
                        )
                        putExtra(
                            ScreenCastSenderService.EXTRA_LANGUAGE,
                            uiState.settings.language.name
                        )
                    }
                    ContextCompat.startForegroundService(context, intent)
                }
            }

            val castPermLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }

            val requestWifiPermissions = {
                val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                wifiPermissionLauncher.launch(perms)
            }

            val startPhoneCast: (String, Int, Int, Int, String) -> Unit = { host, rtsp, uibc, video, name ->
                pendingCastTarget = PendingCastTarget(host, rtsp, uibc, video, name)
                val permsNeeded = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        add(Manifest.permission.RECORD_AUDIO)
                    }
                }
                if (permsNeeded.isNotEmpty()) {
                    castPermLauncher.launch(permsNeeded.toTypedArray())
                } else {
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    projectionLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }

            val stopPhoneCast: () -> Unit = {
                val intent = Intent(context, ScreenCastSenderService::class.java).apply {
                    action = ScreenCastSenderService.ACTION_STOP
                }
                context.startService(intent)
                SecondScreenController.restoreOriginalDisplay(context)
            }

            val restorePhoneDisplay: () -> Unit = {
                val stopIntent = Intent(context, ScreenCastSenderService::class.java).apply {
                    action = ScreenCastSenderService.ACTION_STOP
                }
                runCatching { context.startService(stopIntent) }
                SecondScreenController.forceRestorePortraitAndAuto(context)
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                Handler(Looper.getMainLooper()).postDelayed({
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }, 400L)
            }

            val openSystemCastSettings: () -> Unit = {
                val castIntent = Intent(Settings.ACTION_CAST_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(castIntent) }
                    .onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
            }

            val openAccessibilitySettings: () -> Unit = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }

            val openOverlaySettings: () -> Unit = {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }

            val openWriteSettings: () -> Unit = {
                runCatching {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }

            val openDeveloperSettings: () -> Unit = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }

            MiraBoxTheme {
                LocalizedEnvironment(language = uiState.settings.language) {
                    MiraBoxAppShell(
                        uiState = uiState,
                        viewModel = viewModel,
                        onRequestWifiPermission = requestWifiPermissions,
                        onOpenSystemCastSettings = openSystemCastSettings,
                        onStartPhoneCast = startPhoneCast,
                        onStopPhoneCast = stopPhoneCast,
                        onRestorePhoneDisplay = restorePhoneDisplay,
                        onOpenAccessibilitySettings = openAccessibilitySettings,
                        onOpenOverlaySettings = openOverlaySettings,
                        onOpenWriteSettings = openWriteSettings,
                        onOpenDeveloperSettings = openDeveloperSettings
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
    onRequestWifiPermission: () -> Unit,
    onOpenSystemCastSettings: () -> Unit,
    onStartPhoneCast: (String, Int, Int, Int, String) -> Unit,
    onStopPhoneCast: () -> Unit,
    onRestorePhoneDisplay: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenDeveloperSettings: () -> Unit
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
        ),
        NavItem(
            destination = AppDestination.SENDER,
            label = appString(R.string.role_phone_sender),
            icon = Icons.Rounded.PhoneAndroid,
            testTag = "nav_sender"
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
                            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp)
                    ) {
                        NavigationRail(
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(112.dp)
                                .border(1.dp, ObsidianBorder, RoundedCornerShape(18.dp)),
                            containerColor = ObsidianSurface,
                            header = {
                                Column(
                                    modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
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
                            Spacer(modifier = Modifier.height(6.dp))
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
                            onOpenSystemCastSettings = onOpenSystemCastSettings,
                            onStartPhoneCast = onStartPhoneCast,
                            onStopPhoneCast = onStopPhoneCast,
                            onRestorePhoneDisplay = onRestorePhoneDisplay,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onOpenOverlaySettings = onOpenOverlaySettings,
                            onOpenWriteSettings = onOpenWriteSettings,
                            onOpenDeveloperSettings = onOpenDeveloperSettings,
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
                            onOpenSystemCastSettings = onOpenSystemCastSettings,
                            onStartPhoneCast = onStartPhoneCast,
                            onStopPhoneCast = onStopPhoneCast,
                            onRestorePhoneDisplay = onRestorePhoneDisplay,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onOpenOverlaySettings = onOpenOverlaySettings,
                            onOpenWriteSettings = onOpenWriteSettings,
                            onOpenDeveloperSettings = onOpenDeveloperSettings,
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
    onOpenSystemCastSettings: () -> Unit,
    onStartPhoneCast: (String, Int, Int, Int, String) -> Unit,
    onStopPhoneCast: () -> Unit,
    onRestorePhoneDisplay: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onOpenDeveloperSettings: () -> Unit,
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
            onOpenSystemCastSettings = onOpenSystemCastSettings,
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

        AppDestination.SENDER -> PhoneSenderScreen(
            uiState = uiState,
            onRefreshDiscovery = viewModel::refreshSenderDiscovery,
            onStartCast = onStartPhoneCast,
            onStopCast = onStopPhoneCast,
            onRestorePhoneDisplay = onRestorePhoneDisplay,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onOpenOverlaySettings = onOpenOverlaySettings,
            onOpenWriteSettings = onOpenWriteSettings,
            onOpenDeveloperSettings = onOpenDeveloperSettings,
            onSetSecondScreenProfile = viewModel::setSecondScreenProfile,
            onSetLaunchTabletLauncher = viewModel::setLaunchTabletLauncherOnCast,
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
