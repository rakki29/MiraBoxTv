package com.example.ui.screens

import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.AspectScaleMode
import com.example.data.DisplayMode
import com.example.data.LandscapeRotation
import com.example.ui.MiraBoxUiState
import com.example.ui.components.TvPrimaryButton
import com.example.ui.components.TvSecondaryButton
import com.example.ui.components.TvSelectableChip
import com.example.ui.components.appString
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.ElectricCyanContainer
import com.example.ui.theme.EmeraldMint
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianBorder
import com.example.ui.theme.ObsidianCard
import com.example.ui.theme.ObsidianCardElevated
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

/**
 * Full-screen Mirroring & DeX TV Workspace Stage.
 * Attaches a hardware TextureView to MediaCodec H.264 stream with real-time Matrix rotation
 * (Forced Landscape 16:9), Android TV / Samsung DeX taskbar shell, and UIBC remote/Air-Mouse forwarding.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
fun MirrorStageScreen(
    uiState: MiraBoxUiState,
    onAttachSurface: (Surface?) -> Unit,
    onRemoteKey: (Int, Boolean, Int) -> Boolean,
    onToggleHud: (Boolean?) -> Unit,
    onSetDisplayMode: (DisplayMode) -> Unit,
    onRotateCycle: () -> Unit,
    onToggleAirMouse: () -> Unit,
    onDisconnect: () -> Unit,
    onRunLoopback: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session = uiState.activeSession
    val settings = uiState.settings
    val isDeX = settings.displayMode == DisplayMode.DEX_TV_DESKTOP
    val isForcedLandscape = settings.displayMode == DisplayMode.FORCE_LANDSCAPE
    val stageFocusRequester = remember { FocusRequester() }

    val hudFocusRequester = remember { FocusRequester() }

    LaunchedEffect(session != null, uiState.pendingRequest == null, uiState.stageHudVisible) {
        if (session != null && uiState.pendingRequest == null && !uiState.stageHudVisible) {
            kotlinx.coroutines.delay(100)
            runCatching { stageFocusRequester.requestFocus() }
        } else if (session != null && uiState.stageHudVisible) {
            kotlinx.coroutines.delay(60)
            runCatching { hudFocusRequester.requestFocus() }
        }
    }

    // Only intercept system BackHandler when session is null or HUD is open;
    // when HUD is closed during an active session, short BACK forwards directly to the phone!
    BackHandler(enabled = session == null || uiState.stageHudVisible) {
        if (uiState.stageHudVisible) {
            onToggleHud(false)
        } else {
            onNavigateBack()
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(stageFocusRequester)
            .focusable(enabled = !uiState.stageHudVisible)
            .onPreviewKeyEvent { ev ->
                val isDown = ev.type == KeyEventType.KeyDown
                val isUp = ev.type == KeyEventType.KeyUp
                if ((isDown || isUp) && session != null) {
                    onRemoteKey(ev.nativeKeyEvent.keyCode, isDown, ev.nativeKeyEvent.repeatCount)
                } else {
                    false
                }
            }
    ) {
        val stageW = constraints.maxWidth.toFloat()
        val stageH = constraints.maxHeight.toFloat()

        // 1. Real Hardware MediaCodec Video Surface (TextureView with Upright Landscape Matrix)
        if (session != null && !session.isLoopbackDiagnostic) {
            val streamW = uiState.streamResolution?.first ?: session.sourceWidth
            val streamH = uiState.streamResolution?.second ?: session.sourceHeight
            MiracastTextureSurface(
                streamWidth = streamW,
                streamHeight = streamH,
                displayMode = settings.displayMode,
                landscapeRotation = settings.landscapeRotation,
                aspectScaleMode = settings.aspectScaleMode,
                onAttachSurface = onAttachSurface,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (session == null) {
            StageStandbyOverlay(
                onRunLoopback = onRunLoopback,
                onBackToReceiver = onNavigateBack
            )
        } else if (session.isLoopbackDiagnostic) {
            LoopbackInteractivePhoneMirror(
                uiState = uiState,
                modifier = Modifier.fillMaxSize()
            )
        } else if (uiState.receivedVideoFrames == 0L) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(ObsidianCard.copy(alpha = 0.88f))
                        .border(1.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    Text(
                        text = session.deviceName,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        text = "Video ${session.tcpVideoPort} • Audio ${session.tcpAudioPort} • UIBC ${session.uibcPort}",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
            }
        }

        // 3. Android TV / Samsung DeX Desktop Taskbar Overlay (non-focusable when HUD is closed to prevent D-Pad hijacking)
        if (session != null && isDeX && settings.dexTaskbarEnabled) {
            DeXTaskbarOverlay(
                uiState = uiState,
                onToggleAirMouse = onToggleAirMouse,
                onOpenHud = { onToggleHud(true) },
                onBackKey = { onRemoteKey(android.view.KeyEvent.KEYCODE_BACK, true, 0) },
                onHomeKey = { onRemoteKey(android.view.KeyEvent.KEYCODE_HOME, true, 0) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .focusProperties { canFocus = false }
            )
        }

        // 4. Air-Mouse Glowing Pointer Overlay
        if (session != null && uiState.airMouse.enabled) {
            val animX by animateFloatAsState(targetValue = uiState.airMouse.normX, label = "mouseX")
            val animY by animateFloatAsState(targetValue = uiState.airMouse.normY, label = "mouseY")
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (animX * stageW).roundToInt() - 14,
                            (animY * stageH).roundToInt() - 14
                        )
                    }
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(ElectricCyan.copy(alpha = 0.28f))
                    .border(2.5.dp, ElectricCyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }

        // 5. Top Subtle Telemetry Pill & HUD Toggle
        if (session != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val badgeLabel = when {
                    isDeX -> appString(R.string.dex_workspace_badge, "${settings.dexDpiProfile.width}p")
                    isForcedLandscape -> appString(R.string.landscape_forced_badge)
                    else -> session.deviceName
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(ObsidianCard.copy(alpha = 0.85f))
                        .border(1.dp, ElectricCyan.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = badgeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricCyan
                    )
                }

                TvSecondaryButton(
                    text = appString(R.string.stage_hud_controls),
                    onClick = { onToggleHud(null) },
                    modifier = Modifier
                        .focusProperties { canFocus = false }
                        .testTag("stage_hud_toggle_button")
                )
            }
        }

        // 6. TV Quick Control HUD Drawer (Switch Mirror / Landscape / DeX, Rotate, Air-Mouse, Exit, Disconnect)
        AnimatedVisibility(
            visible = uiState.stageHudVisible && session != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 68.dp, start = 20.dp, end = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(ObsidianCard.copy(alpha = 0.96f))
                    .border(1.5.dp, ElectricCyan.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .focusGroup()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvSelectableChip(
                        label = appString(R.string.mode_standard_title),
                        selected = settings.displayMode == DisplayMode.STANDARD_MIRROR,
                        onClick = { onSetDisplayMode(DisplayMode.STANDARD_MIRROR) },
                        modifier = Modifier.testTag("hud_mode_standard")
                    )
                    TvSelectableChip(
                        label = appString(R.string.mode_landscape_title),
                        selected = settings.displayMode == DisplayMode.FORCE_LANDSCAPE,
                        onClick = { onSetDisplayMode(DisplayMode.FORCE_LANDSCAPE) },
                        modifier = Modifier.testTag("hud_mode_landscape")
                    )
                    TvSelectableChip(
                        label = appString(R.string.mode_dex_title),
                        selected = settings.displayMode == DisplayMode.DEX_TV_DESKTOP,
                        onClick = { onSetDisplayMode(DisplayMode.DEX_TV_DESKTOP) },
                        modifier = Modifier.testTag("hud_mode_dex")
                    )
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TvSecondaryButton(
                        text = appString(R.string.stage_hud_rotate),
                        onClick = onRotateCycle,
                        leadingIcon = { Icon(Icons.Rounded.RotateRight, contentDescription = null) },
                        modifier = Modifier.testTag("hud_rotate_button")
                    )
                    TvSecondaryButton(
                        text = appString(R.string.stage_hud_toggle_mouse),
                        onClick = onToggleAirMouse,
                        borderColor = if (uiState.airMouse.enabled) ElectricCyan else ObsidianBorder,
                        leadingIcon = { Icon(Icons.Rounded.Mouse, contentDescription = null) },
                        modifier = Modifier.testTag("hud_airmouse_button")
                    )
                    TvSecondaryButton(
                        text = appString(R.string.stage_hud_exit),
                        onClick = {
                            onToggleHud(false)
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag("hud_exit_stage_button")
                    )
                    TvPrimaryButton(
                        text = appString(R.string.btn_disconnect),
                        onClick = onDisconnect,
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White,
                        modifier = Modifier
                            .focusRequester(hudFocusRequester)
                            .testTag("hud_disconnect_button")
                    )
                }
            }
        }
    }
}

@Composable
private fun MiracastTextureSurface(
    streamWidth: Int,
    streamHeight: Int,
    displayMode: DisplayMode,
    landscapeRotation: LandscapeRotation,
    aspectScaleMode: AspectScaleMode,
    onAttachSurface: (Surface?) -> Unit,
    modifier: Modifier = Modifier
) {
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var lastAppliedSignature by remember { mutableStateOf("") }

    LaunchedEffect(
        textureViewRef,
        streamWidth,
        streamHeight,
        displayMode,
        landscapeRotation,
        aspectScaleMode
    ) {
        val tv = textureViewRef ?: return@LaunchedEffect
        if (tv.width > 0 && tv.height > 0) {
            val sig = "${tv.width}x${tv.height}|$streamWidth|$streamHeight|$displayMode|$landscapeRotation|$aspectScaleMode"
            if (sig != lastAppliedSignature) {
                lastAppliedSignature = sig
                applyVideoTransformMatrix(
                    textureView = tv,
                    streamW = streamWidth,
                    streamH = streamHeight,
                    displayMode = displayMode,
                    rotation = landscapeRotation,
                    scaleMode = aspectScaleMode
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            onAttachSurface(null)
        }
    }

    AndroidView(
        factory = { context ->
            TextureView(context).apply {
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        onAttachSurface(Surface(st))
                        val sig = "${w}x$h|$streamWidth|$streamHeight|$displayMode|$landscapeRotation|$aspectScaleMode"
                        lastAppliedSignature = sig
                        applyVideoTransformMatrix(
                            textureView = this@apply,
                            streamW = streamWidth,
                            streamH = streamHeight,
                            displayMode = displayMode,
                            rotation = landscapeRotation,
                            scaleMode = aspectScaleMode
                        )
                    }

                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                        val sig = "${w}x$h|$streamWidth|$streamHeight|$displayMode|$landscapeRotation|$aspectScaleMode"
                        if (sig != lastAppliedSignature) {
                            lastAppliedSignature = sig
                            applyVideoTransformMatrix(
                                textureView = this@apply,
                                streamW = streamWidth,
                                streamH = streamHeight,
                                displayMode = displayMode,
                                rotation = landscapeRotation,
                                scaleMode = aspectScaleMode
                            )
                        }
                    }

                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                        onAttachSurface(null)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                }
            }.also { textureViewRef = it }
        },
        modifier = modifier
    )
}

private fun applyVideoTransformMatrix(
    textureView: TextureView,
    streamW: Int,
    streamH: Int,
    displayMode: DisplayMode,
    rotation: LandscapeRotation,
    scaleMode: AspectScaleMode
) {
    val viewW = textureView.width.toFloat().coerceAtLeast(1f)
    val viewH = textureView.height.toFloat().coerceAtLeast(1f)
    // Keep AUTO_LANDSCAPE upright (0°); phone's DisplayListener + True Force Landscape
    // delivers native 1280x720 horizontal frames instead of tilting portrait UI sideways.
    val degrees = when (displayMode) {
        DisplayMode.STANDARD_MIRROR -> 0f
        DisplayMode.FORCE_LANDSCAPE, DisplayMode.DEX_TV_DESKTOP -> {
            when (rotation) {
                LandscapeRotation.AUTO_LANDSCAPE, LandscapeRotation.ROT_0 -> 0f
                LandscapeRotation.ROT_90 -> 90f
                LandscapeRotation.ROT_270 -> 270f
            }
        }
    }

    val isRotated90 = degrees == 90f || degrees == 270f
    val effectiveW = if (isRotated90) streamH.toFloat() else streamW.toFloat()
    val effectiveH = if (isRotated90) streamW.toFloat() else streamH.toFloat()

    val sxRatio = viewW / effectiveW
    val syRatio = viewH / effectiveH

    val (targetW, targetH) = when {
        displayMode == DisplayMode.STANDARD_MIRROR -> {
            val fit = minOf(sxRatio, syRatio)
            effectiveW * fit to effectiveH * fit
        }
        scaleMode == AspectScaleMode.STRETCH_FULL -> {
            viewW to viewH
        }
        scaleMode == AspectScaleMode.FILL_WIDESCREEN -> {
            val fill = maxOf(sxRatio, syRatio)
            effectiveW * fill to effectiveH * fill
        }
        else -> {
            val fit = minOf(sxRatio, syRatio)
            effectiveW * fit to effectiveH * fit
        }
    }

    // Because TextureView rotates around center AFTER scaling, a 90/270 rotation swaps X and Y axes
    val scaleX = if (isRotated90) targetH / viewW else targetW / viewW
    val scaleY = if (isRotated90) targetW / viewH else targetH / viewH

    val matrix = Matrix()
    val cx = viewW / 2f
    val cy = viewH / 2f
    matrix.setScale(scaleX, scaleY, cx, cy)
    if (degrees != 0f) {
        matrix.postRotate(degrees, cx, cy)
    }
    textureView.setTransform(matrix)
}

@Composable
private fun StageStandbyOverlay(
    onRunLoopback: () -> Unit,
    onBackToReceiver: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0E1A2E), ObsidianBg)
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(ObsidianCard)
                .border(1.5.dp, ObsidianBorder, RoundedCornerShape(24.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Tv,
                contentDescription = null,
                tint = ElectricCyan,
                modifier = Modifier.size(56.dp)
            )
            Text(
                text = appString(R.string.stage_standby_title),
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary
            )
            Text(
                text = appString(R.string.stage_standby_desc),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvPrimaryButton(
                    text = appString(R.string.btn_loopback_test),
                    onClick = onRunLoopback,
                    modifier = Modifier.testTag("stage_loopback_button")
                )
                TvSecondaryButton(
                    text = appString(R.string.nav_receiver),
                    onClick = onBackToReceiver,
                    modifier = Modifier.testTag("stage_back_receiver_button")
                )
            }
        }
    }
}

/**
 * Renders the live Loopback Mirroring Canvas so the user can visually verify
 * Standard Portrait Mirror vs Forced Landscape 16:9 vs Android TV / Samsung DeX Desktop
 * and navigate phone apps with D-Pad or Air-Mouse via real UIBC events.
 */
@Composable
private fun LoopbackInteractivePhoneMirror(
    uiState: MiraBoxUiState,
    modifier: Modifier = Modifier
) {
    val mode = uiState.settings.displayMode
    val apps = remember {
        listOf(
            "YouTube" to Icons.Rounded.PlayCircle,
            "Chrome" to Icons.Rounded.Public,
            "Gallery" to Icons.Rounded.PhotoLibrary,
            "Files" to Icons.Rounded.Folder,
            "Messages" to Icons.Rounded.Sms,
            "Music" to Icons.Rounded.MusicNote
        )
    }

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(Color(0xFF060A13), Color(0xFF0D1B2E), Color(0xFF07131D))
                )
            )
            .padding(
                bottom = if (mode == DisplayMode.DEX_TV_DESKTOP && uiState.settings.dexTaskbarEnabled) 68.dp else 18.dp,
                top = 56.dp,
                start = 18.dp,
                end = 18.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        val frameModifier = when (mode) {
            DisplayMode.STANDARD_MIRROR -> Modifier
                .fillMaxHeight(0.95f)
                .aspectRatio(9f / 16f)
            DisplayMode.FORCE_LANDSCAPE, DisplayMode.DEX_TV_DESKTOP -> Modifier
                .fillMaxSize()
        }

        Column(
            modifier = frameModifier
                .clip(RoundedCornerShape(if (mode == DisplayMode.STANDARD_MIRROR) 26.dp else 16.dp))
                .background(Color(0xFF0E1729))
                .border(
                    width = 2.dp,
                    color = if (mode == DisplayMode.DEX_TV_DESKTOP) ElectricCyan.copy(alpha = 0.65f) else ObsidianBorder,
                    shape = RoundedCornerShape(if (mode == DisplayMode.STANDARD_MIRROR) 26.dp else 16.dp)
                )
                .padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Phone / DeX Status Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = when (mode) {
                            DisplayMode.STANDARD_MIRROR -> Icons.Rounded.ScreenRotation
                            DisplayMode.FORCE_LANDSCAPE -> Icons.Rounded.StayCurrentLandscape
                            DisplayMode.DEX_TV_DESKTOP -> Icons.Rounded.DesktopWindows
                        },
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = appString(R.string.loopback_banner),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
                Text(
                    text = uiState.loopbackAppState.lastRemoteFeedback,
                    style = MaterialTheme.typography.labelMedium,
                    color = EmeraldMint
                )
            }

            // Active App Window or Phone / DeX App Grid
            val openApp = uiState.loopbackAppState.openAppTitle
            if (openApp != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(ObsidianCardElevated)
                        .border(1.5.dp, ElectricCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$openApp — ${if (mode == DisplayMode.DEX_TV_DESKTOP) "Desktop Window (16:9)" else "Mirrored App"}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = TextPrimary
                        )
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Back closes app",
                            tint = ElectricCyan
                        )
                    }
                    Text(
                        text = "UIBC Packets Sent: ${uiState.uibcTelemetry.packetsSent} • Vol ${uiState.loopbackAppState.volumeLevel}%",
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextSecondary
                    )
                    Text(
                        text = "D-Pad / OK / Back / Vol forwarded live via UIBC TCP ${uiState.uibcTelemetry.listeningPort}",
                        style = MaterialTheme.typography.labelLarge,
                        color = ElectricCyan
                    )
                }
            } else {
                val cols = if (mode == DisplayMode.STANDARD_MIRROR) 2 else 3
                val rows = apps.chunked(cols)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rows.forEachIndexed { rIdx, rowApps ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            rowApps.forEachIndexed { cIdx, (name, icon) ->
                                val idx = rIdx * cols + cIdx
                                val isFocused = uiState.loopbackAppState.focusedAppIndex == idx
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            if (isFocused) ElectricCyanContainer else ObsidianCardElevated
                                        )
                                        .border(
                                            width = if (isFocused) 2.5.dp else 1.dp,
                                            color = if (isFocused) ElectricCyan else ObsidianBorder,
                                            shape = RoundedCornerShape(16.dp)
                                        )
                                        .padding(12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = name,
                                        tint = if (isFocused) ElectricCyan else TextPrimary,
                                        modifier = Modifier.size(34.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (isFocused) ElectricCyan else TextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeXTaskbarOverlay(
    uiState: MiraBoxUiState,
    onToggleAirMouse: () -> Unit,
    onOpenHud: () -> Unit,
    onBackKey: () -> Unit,
    onHomeKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ObsidianBg.copy(alpha = 0.90f))
            .border(1.dp, ObsidianBorder)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskbarIconBtn(icon = Icons.Rounded.Apps, label = "DeX", onClick = onOpenHud)
            TaskbarIconBtn(icon = Icons.Rounded.ArrowBack, label = "Back", onClick = onBackKey)
            TaskbarIconBtn(icon = Icons.Rounded.Home, label = "Home", onClick = onHomeKey)
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (uiState.airMouse.enabled) ElectricCyanContainer else ObsidianCardElevated)
                    .border(
                        1.dp,
                        if (uiState.airMouse.enabled) ElectricCyan else ObsidianBorder,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable(onClick = onToggleAirMouse)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = if (uiState.airMouse.enabled) Icons.Rounded.Mouse else Icons.Rounded.SettingsRemote,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = if (uiState.airMouse.enabled) "Air-Mouse" else "D-Pad",
                    style = MaterialTheme.typography.labelMedium,
                    color = ElectricCyan,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "UIBC ${uiState.uibcTelemetry.packetsSent} pkts",
                style = MaterialTheme.typography.labelMedium,
                color = EmeraldMint
            )
        }
    }
}

@Composable
private fun TaskbarIconBtn(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(ObsidianCardElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = TextPrimary,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary
        )
    }
}
