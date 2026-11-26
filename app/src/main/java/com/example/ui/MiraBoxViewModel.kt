package com.example.ui

import android.app.Application
import android.view.KeyEvent
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppLanguage
import com.example.data.AspectScaleMode
import com.example.data.DexDpiProfile
import com.example.data.DisplayMode
import com.example.data.LandscapeRotation
import com.example.data.ReceiverSettings
import com.example.data.SecondScreenProfile
import com.example.data.SettingsRepository
import com.example.data.UibcControlMode
import com.example.miracast.ActiveMirrorSession
import com.example.miracast.ApprovalDecision
import com.example.miracast.ConnectionChannel
import com.example.miracast.DiscoveredReceiver
import com.example.miracast.NetworkEndpoints
import com.example.miracast.PendingConnectionRequest
import com.example.miracast.ServerState
import com.example.miracast.UibcTelemetry
import com.example.miracast.WifiDirectStatus
import com.example.network.AudioStreamReceiver
import com.example.network.MiracastRtspServer
import com.example.network.RtpVideoReceiver
import com.example.network.UibcServer
import com.example.network.WifiDirectController
import com.example.sender.NsdReceiverBrowser
import com.example.sender.SenderRuntimeBus
import com.example.sender.SenderRuntimeStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class AppDestination {
    RECEIVER,
    STAGE,
    SETTINGS,
    REMOTE,
    SENDER
}

data class AirMouseState(
    val enabled: Boolean = false,
    val normX: Float = 0.5f,
    val normY: Float = 0.5f
)

data class LoopbackAppState(
    val focusedAppIndex: Int = 0,
    val openAppTitle: String? = null,
    val volumeLevel: Int = 68,
    val lastRemoteFeedback: String = "Ready"
)

data class MiraBoxUiState(
    val settings: ReceiverSettings = ReceiverSettings(),
    val serverState: ServerState = ServerState.STOPPED,
    val endpoints: NetworkEndpoints = NetworkEndpoints(),
    val p2pStatus: WifiDirectStatus = WifiDirectStatus(),
    val pendingRequest: PendingConnectionRequest? = null,
    val approvalCountdownSec: Int = 35,
    val activeSession: ActiveMirrorSession? = null,
    val receivedVideoFrames: Long = 0L,
    val streamResolution: Pair<Int, Int>? = null,
    val uibcTelemetry: UibcTelemetry = UibcTelemetry(),
    val protocolLogs: List<String> = emptyList(),
    val currentDestination: AppDestination = AppDestination.RECEIVER,
    val airMouse: AirMouseState = AirMouseState(),
    val loopbackAppState: LoopbackAppState = LoopbackAppState(),
    val stageHudVisible: Boolean = false,
    val discoveredReceivers: List<DiscoveredReceiver> = emptyList(),
    val senderStatus: SenderRuntimeStatus = SenderRuntimeStatus(),
    val audioActive: Boolean = false
)

class MiraBoxViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val uibcServer = UibcServer(viewModelScope)
    private val rtpVideoReceiver = RtpVideoReceiver(viewModelScope)
    private val audioStreamReceiver = AudioStreamReceiver(viewModelScope)
    private val nsdReceiverBrowser = NsdReceiverBrowser(application)

    private val _latestSettings = MutableStateFlow(ReceiverSettings())

    private val rtspServer = MiracastRtspServer(
        scope = viewModelScope,
        uibcServer = uibcServer,
        rtpVideoReceiver = rtpVideoReceiver,
        audioStreamReceiver = audioStreamReceiver,
        getSettings = { _latestSettings.value },
        onTrustDevice = { deviceId -> settingsRepository.addTrustedDevice(deviceId) }
    )

    private val wifiDirectController = WifiDirectController(
        context = application,
        onGroupClientJoined = { deviceName, address ->
            viewModelScope.launch {
                val settings = _latestSettings.value
                if (settings.requireOkApproval && !settings.trustedDevices.contains(address)) {
                    val req = PendingConnectionRequest(
                        sessionId = "P2P-${address.takeLast(4)}",
                        deviceName = deviceName,
                        deviceId = address,
                        remoteAddress = address,
                        channel = ConnectionChannel.WIFI_DIRECT_P2P,
                        pinCode = "%04d".format(Random.nextInt(1000, 9999))
                    )
                    rtspServer.requestOwnerApproval(req)
                }
            }
        }
    )

    private val _destination = MutableStateFlow(AppDestination.RECEIVER)
    private val _airMouse = MutableStateFlow(AirMouseState())
    private val _loopbackApp = MutableStateFlow(LoopbackAppState())
    private val _stageHudVisible = MutableStateFlow(false)
    private val _approvalCountdown = MutableStateFlow(35)
    private var countdownJob: Job? = null

    private val navAndCursorFlow = combine(
        _destination,
        _airMouse,
        _loopbackApp,
        _stageHudVisible,
        _approvalCountdown
    ) { dest, mouse, loopback, hud, countdown ->
        NavCursorSnapshot(dest, mouse, loopback, hud, countdown)
    }

    private val serverCoreFlow = combine(
        settingsRepository.settingsFlow,
        rtspServer.serverState,
        wifiDirectController.endpoints,
        wifiDirectController.p2pStatus,
        rtspServer.pendingRequest
    ) { settings, serverState, endpoints, p2p, pending ->
        ServerCoreSnapshot(settings, serverState, endpoints, p2p, pending)
    }

    private val streamTelemetryFlow = combine(
        rtspServer.activeSession,
        rtpVideoReceiver.receivedFrames,
        rtpVideoReceiver.streamResolution,
        uibcServer.telemetry,
        rtspServer.protocolLogs
    ) { session, frames, res, uibc, logs ->
        StreamTelemetrySnapshot(session, frames, res, uibc, logs)
    }

    private val senderFlow = combine(
        nsdReceiverBrowser.receivers,
        SenderRuntimeBus.status,
        audioStreamReceiver.audioActive
    ) { receivers, status, audio ->
        Triple(receivers, status, audio)
    }

    val uiState: StateFlow<MiraBoxUiState> = combine(
        serverCoreFlow,
        streamTelemetryFlow,
        navAndCursorFlow,
        senderFlow
    ) { core, stream, nav, sender ->
        MiraBoxUiState(
            settings = core.settings,
            serverState = core.serverState,
            endpoints = core.endpoints,
            p2pStatus = core.p2p,
            pendingRequest = core.pending,
            approvalCountdownSec = nav.countdown,
            activeSession = stream.session,
            receivedVideoFrames = stream.frames,
            streamResolution = stream.res,
            uibcTelemetry = stream.uibc,
            protocolLogs = stream.logs,
            currentDestination = nav.dest,
            airMouse = nav.mouse,
            loopbackAppState = nav.loopback,
            stageHudVisible = nav.hud,
            discoveredReceivers = sender.first,
            senderStatus = sender.second,
            audioActive = sender.third
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MiraBoxUiState()
    )

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                _latestSettings.value = settings
                val shouldMouse = when (settings.uibcControlMode) {
                    UibcControlMode.AIR_MOUSE_POINTER -> true
                    UibcControlMode.DPAD_KEYS, UibcControlMode.HYBRID_AUTO -> false
                }
                _airMouse.update { it.copy(enabled = shouldMouse) }
            }
        }

        viewModelScope.launch {
            rtspServer.pendingRequest.collect { pending ->
                countdownJob?.cancel()
                if (pending != null) {
                    _approvalCountdown.value = 35
                    countdownJob = launch {
                        for (sec in 35 downTo 0) {
                            _approvalCountdown.value = sec
                            delay(1000)
                        }
                    }
                }
            }
        }

        viewModelScope.launch {
            rtspServer.activeSession.collect { session ->
                if (session != null) {
                    _destination.value = AppDestination.STAGE
                }
            }
        }

        startReceiver()
    }

    fun startReceiver() {
        val rtspPort = rtspServer.start(7236)
        val uibcPort = uibcServer.telemetry.value.listeningPort
        wifiDirectController.startDiscoveryAndAdvertising(
            receiverName = _latestSettings.value.receiverName,
            rtspPort = rtspPort,
            uibcPort = uibcPort
        )
    }

    fun onPermissionResult() {
        startReceiver()
    }

    fun scanWifiDirectPeers() {
        wifiDirectController.discoverPeers()
    }

    fun invitePeer(address: String) {
        wifiDirectController.connectToPeer(address)
    }

    fun runLoopbackTest() {
        rtspServer.runLocalLoopbackTest()
    }

    fun disconnectSession() {
        rtspServer.disconnectActiveSession()
        _stageHudVisible.value = false
        _loopbackApp.value = LoopbackAppState()
    }

    fun approveOnce() {
        rtspServer.submitApprovalDecision(ApprovalDecision.ApproveOnce)
    }

    fun approveAndTrust() {
        rtspServer.submitApprovalDecision(ApprovalDecision.ApproveAndTrust)
    }

    fun denyConnection() {
        rtspServer.submitApprovalDecision(ApprovalDecision.Deny)
    }

    fun navigateTo(destination: AppDestination) {
        _destination.value = destination
        if (destination != AppDestination.STAGE) {
            _stageHudVisible.value = false
        }
        if (destination == AppDestination.SENDER) {
            nsdReceiverBrowser.startDiscovery()
        } else {
            nsdReceiverBrowser.stopDiscovery()
        }
    }

    fun refreshSenderDiscovery() {
        nsdReceiverBrowser.startDiscovery()
    }

    fun toggleStageHud(visible: Boolean? = null) {
        _stageHudVisible.update { visible ?: !it }
    }

    fun toggleAirMouse() {
        _airMouse.update { it.copy(enabled = !it.enabled) }
    }

    fun attachVideoSurface(surface: Surface?) {
        val profile = _latestSettings.value.dexDpiProfile
        rtpVideoReceiver.attachSurface(surface, profile.width, profile.height)
    }

    private var lastDpadRepeatMs: Long = 0L

    fun shouldInterceptStageKeyInActivity(keyCode: Int): Boolean {
        val state = uiState.value
        val onStage = state.currentDestination == AppDestination.STAGE &&
            state.activeSession != null &&
            state.pendingRequest == null
        if (!onStage) return false
        if (!state.stageHudVisible) return true
        return keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == KeyEvent.KEYCODE_GUIDE ||
            keyCode == KeyEvent.KEYCODE_BACK
    }

    /**
     * Handles TV Box Remote Control input while on the Mirror Stage.
     * Separates TV HUD navigation from Phone UIBC injection with zero button overlap.
     */
    fun handleStageRemoteKey(keyCode: Int, isDown: Boolean, repeatCount: Int = 0): Boolean {
        // Open/close TV Box HUD on Menu, Info, Settings, Guide, or Long-Press Back
        if (keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_INFO ||
            keyCode == KeyEvent.KEYCODE_SETTINGS ||
            keyCode == KeyEvent.KEYCODE_GUIDE
        ) {
            if (isDown && repeatCount == 0) toggleStageHud()
            return true
        }

        if (_stageHudVisible.value) {
            // While HUD is open, BACK closes HUD and D-Pad/OK navigate HUD buttons cleanly
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (!isDown) toggleStageHud(false)
                return true
            }
            return false
        }

        // Long-press BACK opens the TV HUD (convenient for remotes without a dedicated MENU key)
        if (keyCode == KeyEvent.KEYCODE_BACK && isDown && repeatCount >= 4) {
            toggleStageHud(true)
            return true
        }

        // Ignore auto-repeat on discrete action keys (prevents accidental double-taps on OK/Back/Home)
        val isDiscreteKey = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            keyCode == KeyEvent.KEYCODE_BACK ||
            keyCode == KeyEvent.KEYCODE_HOME ||
            keyCode == KeyEvent.KEYCODE_APP_SWITCH ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
        if (isDown && repeatCount > 0 && isDiscreteKey) {
            return true
        }

        // Rate-limit held D-Pad repeats so focus moves cleanly item-by-item without flooding
        if (isDown && repeatCount > 0 && isDirectionalOrOk(keyCode)) {
            val now = System.currentTimeMillis()
            if (now - lastDpadRepeatMs < 115L) {
                return true
            }
            lastDpadRepeatMs = now
        }

        val mouse = _airMouse.value
        val step = 0.04f * _latestSettings.value.airMouseSpeed

        if (mouse.enabled && isDirectionalOrOk(keyCode)) {
            if (!isDown) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                ) {
                    uibcServer.sendTouchEvent(1, mouse.normX, mouse.normY, 1920, 1080)
                }
                return true
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    val ny = (mouse.normY - step).coerceIn(0.03f, 0.97f)
                    _airMouse.update { it.copy(normY = ny) }
                    uibcServer.sendTouchEvent(2, mouse.normX, ny, 1920, 1080)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    val ny = (mouse.normY + step).coerceIn(0.03f, 0.97f)
                    _airMouse.update { it.copy(normY = ny) }
                    uibcServer.sendTouchEvent(2, mouse.normX, ny, 1920, 1080)
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val nx = (mouse.normX - step).coerceIn(0.03f, 0.97f)
                    _airMouse.update { it.copy(normX = nx) }
                    uibcServer.sendTouchEvent(2, nx, mouse.normY, 1920, 1080)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val nx = (mouse.normX + step).coerceIn(0.03f, 0.97f)
                    _airMouse.update { it.copy(normX = nx) }
                    uibcServer.sendTouchEvent(2, nx, mouse.normY, 1920, 1080)
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    uibcServer.sendTouchEvent(0, mouse.normX, mouse.normY, 1920, 1080)
                    handleLoopbackCursorClick(mouse.normX, mouse.normY)
                }
            }
            return true
        }

        // Standard UIBC Key Injection (Spatial D-Pad Focus, OK, P+/P-, Back, Home, Volume, Mute, Play/Pause, 0-9)
        uibcServer.sendKeyEvent(keyCode, isDown)
        if (isDown) {
            updateLoopbackDpadState(keyCode)
        }
        return true
    }

    fun sendVirtualRemoteKey(keyCode: Int) {
        uibcServer.sendKeyEvent(keyCode, true)
        uibcServer.sendKeyEvent(keyCode, false)
        updateLoopbackDpadState(keyCode)
    }

    private fun updateLoopbackDpadState(keyCode: Int) {
        _loopbackApp.update { cur ->
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> cur.copy(
                    focusedAppIndex = (cur.focusedAppIndex + 1) % 6,
                    lastRemoteFeedback = "UIBC -> DPAD_RIGHT"
                )
                KeyEvent.KEYCODE_DPAD_LEFT -> cur.copy(
                    focusedAppIndex = (cur.focusedAppIndex + 5) % 6,
                    lastRemoteFeedback = "UIBC -> DPAD_LEFT"
                )
                KeyEvent.KEYCODE_DPAD_DOWN -> cur.copy(
                    focusedAppIndex = (cur.focusedAppIndex + 3) % 6,
                    lastRemoteFeedback = "UIBC -> DPAD_DOWN"
                )
                KeyEvent.KEYCODE_DPAD_UP -> cur.copy(
                    focusedAppIndex = (cur.focusedAppIndex + 3) % 6,
                    lastRemoteFeedback = "UIBC -> DPAD_UP"
                )
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    val appNames = listOf("YouTube", "Chrome", "Gallery", "Files", "Messages", "Music")
                    val chosen = appNames[cur.focusedAppIndex % appNames.size]
                    cur.copy(openAppTitle = chosen, lastRemoteFeedback = "UIBC -> OK ($chosen)")
                }
                KeyEvent.KEYCODE_BACK -> cur.copy(
                    openAppTitle = null,
                    lastRemoteFeedback = "UIBC -> BACK"
                )
                KeyEvent.KEYCODE_HOME -> cur.copy(
                    openAppTitle = null,
                    lastRemoteFeedback = "UIBC -> HOME"
                )
                KeyEvent.KEYCODE_VOLUME_UP -> cur.copy(
                    volumeLevel = (cur.volumeLevel + 6).coerceAtMost(100),
                    lastRemoteFeedback = "UIBC -> VOL+"
                )
                KeyEvent.KEYCODE_VOLUME_DOWN -> cur.copy(
                    volumeLevel = (cur.volumeLevel - 6).coerceAtLeast(0),
                    lastRemoteFeedback = "UIBC -> VOL-"
                )
                else -> cur.copy(lastRemoteFeedback = "UIBC -> KEY $keyCode")
            }
        }
    }

    private fun handleLoopbackCursorClick(normX: Float, normY: Float) {
        val appNames = listOf("YouTube", "Chrome", "Gallery", "Files", "Messages", "Music")
        val col = if (normX < 0.34f) 0 else if (normX < 0.67f) 1 else 2
        val row = if (normY < 0.5f) 0 else 1
        val idx = (row * 3 + col).coerceIn(0, 5)
        _loopbackApp.update {
            if (normY > 0.86f) {
                it.copy(openAppTitle = null, lastRemoteFeedback = "DeX Taskbar Tap")
            } else {
                val chosen = appNames[idx]
                it.copy(
                    focusedAppIndex = idx,
                    openAppTitle = chosen,
                    lastRemoteFeedback = "Air-Mouse Tap -> $chosen"
                )
            }
        }
    }

    private fun isDirectionalOrOk(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> true
        else -> false
    }

    // Settings updates
    fun updateReceiverName(name: String) {
        viewModelScope.launch {
            settingsRepository.updateReceiverName(name)
            startReceiver()
        }
    }

    fun setRequireOkApproval(required: Boolean) {
        viewModelScope.launch { settingsRepository.setRequireOkApproval(required) }
    }

    fun setDisplayMode(mode: DisplayMode) {
        viewModelScope.launch {
            settingsRepository.setDisplayMode(mode)
            uibcServer.sendDisplayMode(mode)
        }
    }

    fun setLandscapeRotation(rotation: LandscapeRotation) {
        viewModelScope.launch { settingsRepository.setLandscapeRotation(rotation) }
    }

    fun cycleLandscapeRotation() {
        val next = when (_latestSettings.value.landscapeRotation) {
            LandscapeRotation.AUTO_LANDSCAPE -> LandscapeRotation.ROT_90
            LandscapeRotation.ROT_90 -> LandscapeRotation.ROT_270
            LandscapeRotation.ROT_270 -> LandscapeRotation.ROT_0
            LandscapeRotation.ROT_0 -> LandscapeRotation.AUTO_LANDSCAPE
        }
        setLandscapeRotation(next)
    }

    fun setAspectScaleMode(mode: AspectScaleMode) {
        viewModelScope.launch { settingsRepository.setAspectScaleMode(mode) }
    }

    fun setDexDpiProfile(profile: DexDpiProfile) {
        viewModelScope.launch { settingsRepository.setDexDpiProfile(profile) }
    }

    fun setDexTaskbarEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDexTaskbarEnabled(enabled) }
    }

    fun setUibcControlMode(mode: UibcControlMode) {
        viewModelScope.launch { settingsRepository.setUibcControlMode(mode) }
    }

    fun setAirMouseSpeed(speed: Float) {
        viewModelScope.launch { settingsRepository.setAirMouseSpeed(speed) }
    }

    fun setSecondScreenProfile(profile: SecondScreenProfile) {
        viewModelScope.launch { settingsRepository.setSecondScreenProfile(profile) }
    }

    fun setLaunchTabletLauncherOnCast(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLaunchTabletLauncherOnCast(enabled) }
    }

    fun setLanguage(language: AppLanguage) {
        viewModelScope.launch { settingsRepository.setLanguage(language) }
    }

    fun revokeTrustedDevice(deviceId: String) {
        viewModelScope.launch { settingsRepository.removeTrustedDevice(deviceId) }
    }

    override fun onCleared() {
        super.onCleared()
        nsdReceiverBrowser.stopDiscovery()
        rtspServer.stop()
        wifiDirectController.stop()
    }

    private data class NavCursorSnapshot(
        val dest: AppDestination,
        val mouse: AirMouseState,
        val loopback: LoopbackAppState,
        val hud: Boolean,
        val countdown: Int
    )

    private data class ServerCoreSnapshot(
        val settings: ReceiverSettings,
        val serverState: ServerState,
        val endpoints: NetworkEndpoints,
        val p2p: WifiDirectStatus,
        val pending: PendingConnectionRequest?
    )

    private data class StreamTelemetrySnapshot(
        val session: ActiveMirrorSession?,
        val frames: Long,
        val res: Pair<Int, Int>?,
        val uibc: UibcTelemetry,
        val logs: List<String>
    )
}
