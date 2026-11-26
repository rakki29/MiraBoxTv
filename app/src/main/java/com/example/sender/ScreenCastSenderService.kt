package com.example.sender

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.R
import com.example.data.DisplayMode
import com.example.miracast.SenderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

data class SenderRuntimeStatus(
    val state: SenderState = SenderState.IDLE,
    val targetHost: String = "",
    val targetName: String = "",
    val framesStreamed: Long = 0L,
    val uibcEventsExecuted: Int = 0,
    val lastRemoteEvent: String = "-",
    val audioStreaming: Boolean = false,
    val audioStatusNote: String = "",
    val landscapeLockActive: Boolean = false,
    val secondScreenNote: String = "",
    val errorMessage: String? = null
)

object SenderRuntimeBus {
    private val _status = MutableStateFlow(SenderRuntimeStatus())
    val status: StateFlow<SenderRuntimeStatus> = _status.asStateFlow()

    @Volatile
    var isTabletLauncherActive: Boolean = false

    private val _tabletRemoteKeys = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val tabletRemoteKeys: SharedFlow<Int> = _tabletRemoteKeys.asSharedFlow()

    fun emitTabletKey(hidKeyCode: Int): Boolean = _tabletRemoteKeys.tryEmit(hidKeyCode)

    fun update(transform: (SenderRuntimeStatus) -> SenderRuntimeStatus) {
        _status.update(transform)
    }

    fun reset() {
        _status.value = SenderRuntimeStatus()
    }
}

/**
 * MediaProjection Foreground Service that:
 * - Completes RTSP M1-M7 handshake (awaiting TV Remote OK approval)
 * - Enforces True Force Landscape on the phone via a 0x0 SYSTEM_ALERT_WINDOW overlay when TV is in Force Landscape / DeX mode
 * - Dynamically reconfigures VirtualDisplay & H.264 encoder whenever phone orientation changes
 * - Streams low-jitter zero-B-frame H.264 video with exact PTS over TCP 19001
 * - Streams internal PCM audio via AudioPlaybackCapture (Android 10+) over TCP 19002
 * - Receives UIBC TV Remote commands (D-Pad, OK, P+/P-, Vol+/-, Mute, Play/Pause, Air-Mouse, DisplayMode)
 */
class ScreenCastSenderService : Service() {

    companion object {
        const val ACTION_START = "com.example.sender.ACTION_START"
        const val ACTION_STOP = "com.example.sender.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_HOST = "host"
        const val EXTRA_RTSP_PORT = "rtsp_port"
        const val EXTRA_UIBC_PORT = "uibc_port"
        const val EXTRA_VIDEO_PORT = "video_port"
        const val EXTRA_AUDIO_PORT = "audio_port"
        const val EXTRA_TARGET_NAME = "target_name"
        const val EXTRA_SECOND_SCREEN_PROFILE = "second_screen_profile"
        const val EXTRA_LAUNCH_TABLET_LAUNCHER = "launch_tablet_launcher"
        const val EXTRA_LANGUAGE = "language"

        private const val CHANNEL_ID = "mirabox_cast_sender"
        private const val NOTIFICATION_ID = 4102
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var sessionJob: Job? = null
    private var uibcJob: Job? = null
    private var audioJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var activeEncW: Int = 1280
    private var activeEncH: Int = 720
    private var activeIsPortrait: Boolean = false
    @Volatile
    private var pendingReconfigureSize: Pair<Int, Int>? = null
    private val encoderLock = Any()

    private var rtspSocket: Socket? = null
    private var videoSocket: Socket? = null
    private var audioSocket: Socket? = null
    private var uibcSocket: Socket? = null

    private var displayManager: DisplayManager? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var orientationOverlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCastAndSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        val rtspPort = intent?.getIntExtra(EXTRA_RTSP_PORT, 7236) ?: 7236
        val uibcPort = intent?.getIntExtra(EXTRA_UIBC_PORT, 7237) ?: 7237
        val videoPort = intent?.getIntExtra(EXTRA_VIDEO_PORT, 19001) ?: 19001
        val audioPort = intent?.getIntExtra(EXTRA_AUDIO_PORT, 19002) ?: 19002
        val targetName = intent?.getStringExtra(EXTRA_TARGET_NAME) ?: host
        val secondScreenProfile = runCatching {
            com.example.data.SecondScreenProfile.valueOf(
                intent?.getStringExtra(EXTRA_SECOND_SCREEN_PROFILE) ?: ""
            )
        }.getOrDefault(com.example.data.SecondScreenProfile.TABLET_720P)
        val launchTabletLauncher = intent?.getBooleanExtra(EXTRA_LAUNCH_TABLET_LAUNCHER, true) ?: true
        val appLanguage = intent?.getStringExtra(EXTRA_LANGUAGE) ?: "ARABIC"

        if (resultCode != Activity.RESULT_OK || resultData == null || host.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = buildNotification(targetName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startStreamingSession(
            resultCode = resultCode,
            resultData = resultData,
            host = host,
            rtspPort = rtspPort,
            uibcPort = uibcPort,
            videoPort = videoPort,
            audioPort = audioPort,
            targetName = targetName,
            secondScreenProfile = secondScreenProfile,
            launchTabletLauncher = launchTabletLauncher,
            appLanguage = appLanguage
        )

        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkOrientationChange()
    }

    private fun startStreamingSession(
        resultCode: Int,
        resultData: Intent,
        host: String,
        rtspPort: Int,
        uibcPort: Int,
        videoPort: Int,
        audioPort: Int,
        targetName: String,
        secondScreenProfile: com.example.data.SecondScreenProfile,
        launchTabletLauncher: Boolean,
        appLanguage: String
    ) {
        sessionJob?.cancel()
        uibcJob?.cancel()
        audioJob?.cancel()

        sessionJob = serviceScope.launch {
            try {
                SenderRuntimeBus.update {
                    SenderRuntimeStatus(
                        state = SenderState.AWAITING_TV_OK,
                        targetHost = host,
                        targetName = targetName
                    )
                }

                // 1. RTSP Handshake with TV Box
                val rtsp = Socket()
                rtsp.connect(InetSocketAddress(host, rtspPort), 8000)
                rtspSocket = rtsp
                val out = PrintWriter(rtsp.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(rtsp.getInputStream()))
                val phoneName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val (initW, initH) = readCurrentScreenDimensions()

                data class RtspResp(
                    val code: Int,
                    val headers: Map<String, String>,
                    val body: String
                )

                fun sendRtsp(method: String, cseq: Int, body: String = ""): RtspResp {
                    val req = buildString {
                        append("$method rtsp://$host:$rtspPort/wfd1.0 RTSP/1.0\r\n")
                        append("CSeq: $cseq\r\n")
                        append("X-Device-Name: $phoneName\r\n")
                        append("X-Device-Id: ${Build.MODEL.replace(" ", "_")}\r\n")
                        if (body.isNotEmpty()) {
                            append("Content-Type: text/parameters\r\n")
                            append("Content-Length: ${body.length}\r\n")
                        }
                        append("\r\n")
                        append(body)
                    }
                    out.print(req)
                    out.flush()
                    val statusLine = input.readLine() ?: return RtspResp(500, emptyMap(), "")
                    val respHeaders = mutableMapOf<String, String>()
                    var len = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            val k = line.substring(0, colon).trim().lowercase()
                            val v = line.substring(colon + 1).trim()
                            respHeaders[k] = v
                            if (k == "content-length") {
                                len = v.toIntOrNull() ?: 0
                            }
                        }
                    }
                    val respBody = if (len > 0) {
                        val buf = CharArray(len)
                        var read = 0
                        while (read < len) {
                            val n = input.read(buf, read, len - read)
                            if (n <= 0) break
                            read += n
                        }
                        String(buf, 0, read)
                    } else ""
                    val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
                    return RtspResp(code, respHeaders, respBody)
                }

                if (sendRtsp("OPTIONS", 1).code != 200) error("RTSP OPTIONS failed")
                val getParamResp = sendRtsp("GET_PARAMETER", 2)
                if (getParamResp.code != 200) error("RTSP GET_PARAMETER failed")
                val uibcFromCaps = Regex("port=(\\d{2,5})").find(getParamResp.body)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

                sendRtsp(
                    "SET_PARAMETER",
                    3,
                    "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n" +
                        "wfd_source_resolution: ${initW}x${initH}\r\n"
                )
                val setupResp = sendRtsp("SETUP", 4)
                if (setupResp.code == 403) error("Denied by TV Box owner")
                if (setupResp.code != 200) error("RTSP SETUP failed (${setupResp.code})")
                val playResp = sendRtsp("PLAY", 5)
                if (playResp.code != 200) error("RTSP PLAY failed (${playResp.code})")

                val actualVideoPort = playResp.headers["x-video-port"]?.toIntOrNull() ?: videoPort
                val actualAudioPort = playResp.headers["x-audio-port"]?.toIntOrNull() ?: audioPort
                val actualUibcPort = playResp.headers["x-uibc-port"]?.toIntOrNull()
                    ?: uibcFromCaps
                    ?: uibcPort
                val tvDisplayMode = runCatching {
                    DisplayMode.valueOf(playResp.headers["x-display-mode"] ?: "")
                }.getOrDefault(DisplayMode.DEX_TV_DESKTOP)

                val forceLandscape = tvDisplayMode != DisplayMode.STANDARD_MIRROR

                // Apply SecondScreen Tablet resolution/DPI (if granted) + system rotation lock + overlay lock
                val secondScreenRes = SecondScreenController.applySecondScreenProfile(
                    context = this@ScreenCastSenderService,
                    profile = secondScreenProfile,
                    forceLandscape = forceLandscape
                )
                applyPhoneLandscapeLock(forceLandscape)
                if (secondScreenRes.description.isNotEmpty()) {
                    SenderRuntimeBus.update { it.copy(secondScreenNote = secondScreenRes.description) }
                }

                // 2. Start UIBC Listener Client to receive TV Remote commands & live DisplayMode updates
                startUibcReceiver(host, actualUibcPort)

                // 3. Connect TCP Video Socket
                val vSocket = connectWithRetry(host, actualVideoPort, connectTimeoutMs = 5000)
                vSocket.tcpNoDelay = true
                videoSocket = vSocket
                val videoOut = DataOutputStream(BufferedOutputStream(vSocket.getOutputStream(), 65536))

                // 4. Obtain MediaProjection and register dynamic display orientation listener
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(resultCode, resultData)
                    ?: error("MediaProjection unavailable")
                mediaProjection = projection
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCastAndSelf()
                    }
                }, mainHandler)

                registerOrientationListener()

                // Give orientation overlay / SecondScreen display switch ~250ms to settle before capture
                if (forceLandscape && (canDrawOverlayPermission() || secondScreenRes.appliedResolutionDpi || secondScreenRes.appliedRotationLock)) {
                    delay(260)
                }

                val (curW, curH) = readCurrentScreenDimensions()
                synchronized(encoderLock) {
                    setupEncoderAndDisplayLocked(projection, curW, curH)
                }

                // 5. Start Internal Audio Streaming (Android 10+ AudioPlaybackCapture)
                startAudioSender(projection, host, actualAudioPort)

                SenderRuntimeBus.update {
                    it.copy(
                        state = SenderState.STREAMING,
                        errorMessage = null
                    )
                }

                // 6. Launch Tablet/TV App Grid on phone if requested (SecondScreen experience)
                if (launchTabletLauncher && forceLandscape) {
                    mainHandler.post {
                        runCatching {
                            startActivity(
                                Intent(this@ScreenCastSenderService, TabletLauncherActivity::class.java).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                    putExtra(EXTRA_LANGUAGE, appLanguage)
                                    putExtra(EXTRA_SECOND_SCREEN_PROFILE, secondScreenProfile.name)
                                }
                            )
                        }
                    }
                }

                // 7. Main H.264 Output Loop with seamless orientation reconfiguration
                val bufferInfo = MediaCodec.BufferInfo()
                var sentFrames = 0L
                while (isActive && !vSocket.isClosed) {
                    val reconfigSize = pendingReconfigureSize
                    if (reconfigSize != null) {
                        pendingReconfigureSize = null
                        synchronized(encoderLock) {
                            setupEncoderAndDisplayLocked(
                                projection = projection,
                                screenW = reconfigSize.first,
                                screenH = reconfigSize.second
                            )
                        }
                    }

                    val codec = synchronized(encoderLock) { encoder } ?: break
                    val encW = activeEncW
                    val encH = activeEncH
                    val outIdx = runCatching { codec.dequeueOutputBuffer(bufferInfo, 20_000) }
                        .getOrDefault(MediaCodec.INFO_TRY_AGAIN_LATER)
                    if (outIdx >= 0) {
                        val buf: ByteBuffer? = codec.getOutputBuffer(outIdx)
                        if (buf != null && bufferInfo.size > 0) {
                            buf.position(bufferInfo.offset)
                            buf.limit(bufferInfo.offset + bufferInfo.size)
                            val frameBytes = ByteArray(bufferInfo.size)
                            buf.get(frameBytes)
                            videoOut.writeInt(frameBytes.size)
                            videoOut.writeInt(bufferInfo.flags)
                            videoOut.writeInt(encW)
                            videoOut.writeInt(encH)
                            videoOut.writeLong(bufferInfo.presentationTimeUs)
                            videoOut.write(frameBytes)
                            videoOut.flush()
                            sentFrames += 1L
                            if (sentFrames <= 3L || sentFrames % 30L == 0L) {
                                val snapshotCount = sentFrames
                                SenderRuntimeBus.update { st ->
                                    st.copy(framesStreamed = snapshotCount)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            } catch (e: Exception) {
                if (SenderRuntimeBus.status.value.state == SenderState.STREAMING) {
                    // Remote TV Box closed the stream cleanly or dropped: restore cleanly
                    SenderRuntimeBus.reset()
                } else {
                    SenderRuntimeBus.update {
                        it.copy(
                            state = SenderState.ERROR,
                            errorMessage = e.message ?: "Connection failed"
                        )
                    }
                }
            } finally {
                if (SenderRuntimeBus.status.value.state == SenderState.STREAMING) {
                    SenderRuntimeBus.reset()
                }
                cleanupResources()
                mainHandler.post {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun setupEncoderAndDisplayLocked(
        projection: MediaProjection,
        screenW: Int,
        screenH: Int
    ) {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching {
            encoder?.stop()
            encoder?.release()
        }
        encoder = null

        val isPortrait = screenH > screenW
        val maxDim = 1280
        val rawW = if (isPortrait) ((maxDim * screenW.toFloat()) / screenH).toInt() else maxDim
        val rawH = if (isPortrait) maxDim else ((maxDim * screenH.toFloat()) / screenW).toInt()
        val encW = (((rawW + 8) / 16) * 16).coerceIn(368, 1280)
        val encH = (((rawH + 8) / 16) * 16).coerceIn(368, 1280)

        val (codec, w, h) = createConfiguredEncoder(encW, encH, isPortrait)
        val inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
        activeEncW = w
        activeEncH = h
        activeIsPortrait = isPortrait

        virtualDisplay = projection.createVirtualDisplay(
            "MiraBoxCast",
            w,
            h,
            resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null,
            null
        )
    }

    private val debouncedOrientationCheck = Runnable {
        if (mediaProjection == null) return@Runnable
        val (w, h) = readCurrentScreenDimensions()
        val nowPortrait = h > w
        if (nowPortrait != activeIsPortrait) {
            pendingReconfigureSize = w to h
        }
    }

    private fun checkOrientationChange() {
        if (mediaProjection == null) return
        mainHandler.removeCallbacks(debouncedOrientationCheck)
        mainHandler.postDelayed(debouncedOrientationCheck, 320L)
    }

    private fun registerOrientationListener() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayManager = dm
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) {
                    checkOrientationChange()
                }
            }
        }
        displayListener = listener
        dm.registerDisplayListener(listener, mainHandler)
    }

    @Suppress("DEPRECATION")
    private fun readCurrentScreenDimensions(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width().coerceAtLeast(320) to b.height().coerceAtLeast(320)
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.coerceAtLeast(320) to dm.heightPixels.coerceAtLeast(320)
        }
    }

    private fun canDrawOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
    }

    private fun applyPhoneLandscapeLock(forceLandscape: Boolean) {
        mainHandler.post {
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            if (!forceLandscape || !canDrawOverlayPermission()) {
                orientationOverlayView?.let { v ->
                    runCatching { wm.removeViewImmediate(v) }
                        .onFailure { runCatching { wm.removeView(v) } }
                }
                orientationOverlayView = null
                SenderRuntimeBus.update { it.copy(landscapeLockActive = false) }
                return@post
            }

            if (orientationOverlayView != null) {
                SenderRuntimeBus.update { it.copy(landscapeLockActive = true) }
                return@post
            }

            val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                0,
                0,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }

            val view = View(this)
            runCatching {
                wm.addView(view, params)
                orientationOverlayView = view
                SenderRuntimeBus.update { it.copy(landscapeLockActive = true) }
            }.onFailure {
                SenderRuntimeBus.update { it.copy(landscapeLockActive = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioSender(projection: MediaProjection, host: String, audioPort: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            SenderRuntimeBus.update {
                it.copy(
                    audioStreaming = false,
                    audioStatusNote = getString(R.string.sender_audio_android9_limit)
                )
            }
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            SenderRuntimeBus.update {
                it.copy(
                    audioStreaming = false,
                    audioStatusNote = getString(R.string.sender_audio_perm_missing)
                )
            }
            return
        }

        audioJob = serviceScope.launch {
            var recorder: AudioRecord? = null
            var sock: Socket? = null
            try {
                val sampleRate = 48000
                val channelMask = AudioFormat.CHANNEL_IN_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
                    .coerceAtLeast(4096)

                val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                recorder = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()

                sock = connectWithRetry(host, audioPort, connectTimeoutMs = 4000)
                sock.tcpNoDelay = true
                audioSocket = sock
                val dos = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 8192))
                dos.writeInt(sampleRate)
                dos.writeInt(2) // Stereo
                dos.flush()

                recorder.startRecording()
                SenderRuntimeBus.update {
                    it.copy(
                        audioStreaming = true,
                        audioStatusNote = getString(R.string.sender_audio_active)
                    )
                }

                val pcmBuf = ByteArray(3840) // ~20ms of 48kHz 16-bit stereo
                while (isActive && !sock.isClosed) {
                    val read = recorder.read(pcmBuf, 0, pcmBuf.size)
                    if (read > 0) {
                        dos.writeInt(read)
                        dos.write(pcmBuf, 0, read)
                        dos.flush()
                    }
                }
            } catch (_: Exception) {
                SenderRuntimeBus.update {
                    it.copy(audioStreaming = false)
                }
            } finally {
                runCatching { recorder?.stop() }
                runCatching { recorder?.release() }
                runCatching { sock?.close() }
                audioSocket = null
            }
        }
    }

    private suspend fun connectWithRetry(
        host: String,
        port: Int,
        connectTimeoutMs: Int = 5000,
        maxAttempts: Int = 6,
        retryDelayMs: Long = 250
    ): Socket {
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            val sock = Socket()
            try {
                sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
                return sock
            } catch (e: Exception) {
                runCatching { sock.close() }
                lastError = e
                if (attempt < maxAttempts - 1) {
                    delay(retryDelayMs)
                }
            }
        }
        throw lastError ?: IllegalStateException("Unable to connect to $host:$port")
    }

    private fun createConfiguredEncoder(
        preferredW: Int,
        preferredH: Int,
        isPortrait: Boolean
    ): Triple<MediaCodec, Int, Int> {
        val candidates = listOf(
            preferredW to preferredH,
            if (isPortrait) 720 to 1280 else 1280 to 720
        )
        var lastException: Exception? = null
        for ((w, h) in candidates) {
            // Try progressively simpler low-latency formats while preserving Baseline Profile (zero B-frames)
            val attemptBuilders: List<() -> MediaFormat> = listOf(
                {
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                        setInteger(MediaFormat.KEY_PRIORITY, 0)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                    }
                },
                {
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 4_500_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L)
                    }
                },
                {
                    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                        setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
                        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                    }
                }
            )
            for (buildFormat in attemptBuilders) {
                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                try {
                    codec.configure(buildFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return Triple(codec, w, h)
                } catch (e: Exception) {
                    runCatching { codec.release() }
                    lastException = e
                }
            }
        }
        throw lastException ?: IllegalStateException("No compatible H.264 encoder")
    }

    private fun startUibcReceiver(host: String, uibcPort: Int) {
        uibcJob = serviceScope.launch {
            runCatching {
                val sock = connectWithRetry(host, uibcPort, connectTimeoutMs = 5000)
                uibcSocket = sock
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                val dis = DataInputStream(BufferedInputStream(sock.getInputStream()))
                while (isActive && !sock.isClosed) {
                    dis.readByte() // version/flags
                    dis.readByte() // category
                    val totalLen = dis.readUnsignedShort()
                    val bodyLen = (totalLen - 4).coerceAtLeast(0)
                    if (bodyLen <= 0 || bodyLen > 512) break
                    val body = ByteArray(bodyLen)
                    dis.readFully(body)
                    if (body.size >= 4) {
                        val ieId = body[0].toInt() and 0xFF
                        val acc = MiraBoxRemoteAccessibilityService.instance
                        when (ieId) {
                            3 -> { // KeyDown
                                if (body.size >= 6) {
                                    val hid = ((body[4].toInt() and 0xFF) shl 8) or
                                        (body[5].toInt() and 0xFF)
                                    handleRemoteHidKey(hid, audioManager, acc)
                                    SenderRuntimeBus.update {
                                        it.copy(
                                            uibcEventsExecuted = it.uibcEventsExecuted + 1,
                                            lastRemoteEvent = "KEY 0x%04X".format(hid)
                                        )
                                    }
                                }
                            }
                            0, 2 -> { // TouchDown (0) or Cursor Move (2)
                                if (body.size >= 9) {
                                    val px = ((body[5].toInt() and 0xFF) shl 8) or
                                        (body[6].toInt() and 0xFF)
                                    val py = ((body[7].toInt() and 0xFF) shl 8) or
                                        (body[8].toInt() and 0xFF)
                                    val normX = (px.toFloat() / 1920f).coerceIn(0f, 1f)
                                    val normY = (py.toFloat() / 1080f).coerceIn(0f, 1f)
                                    if (ieId == 0) {
                                        acc?.executeRemoteTap(normX, normY)
                                        SenderRuntimeBus.update {
                                            it.copy(
                                                uibcEventsExecuted = it.uibcEventsExecuted + 1,
                                                lastRemoteEvent = "TAP ($px, $py)"
                                            )
                                        }
                                    } else {
                                        acc?.updateRemoteCursor(normX, normY)
                                    }
                                }
                            }
                            0x50 -> { // Live DisplayMode Sync from TV Box
                                val ord = body[3].toInt() and 0xFF
                                val mode = DisplayMode.entries.getOrNull(ord) ?: DisplayMode.DEX_TV_DESKTOP
                                applyPhoneLandscapeLock(mode != DisplayMode.STANDARD_MIRROR)
                                SenderRuntimeBus.update {
                                    it.copy(lastRemoteEvent = "MODE ${mode.name}")
                                }
                            }
                            0x51 -> { // Clean Disconnect initiated from TV Box
                                stopCastAndSelf()
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleRemoteHidKey(
        hid: Int,
        audioManager: AudioManager?,
        acc: MiraBoxRemoteAccessibilityService?
    ) {
        when (hid) {
            0x0080 -> audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI
            )
            0x0081 -> audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
            0x007F -> audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                AudioManager.ADJUST_TOGGLE_MUTE,
                AudioManager.FLAG_SHOW_UI
            )
            0x00E8 -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            0x00EB -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_NEXT)
            0x00EC -> dispatchMediaKey(audioManager, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            else -> {
                val isTabletNavKey = hid == 0x004F || hid == 0x0050 || hid == 0x0051 ||
                    hid == 0x0052 || hid == 0x0028 || hid == 0x0029 || hid == 0x004B || hid == 0x004E
                if (SenderRuntimeBus.isTabletLauncherActive && isTabletNavKey) {
                    acc?.clearFocusHighlightOnly()
                    SenderRuntimeBus.emitTabletKey(hid)
                } else {
                    acc?.executeRemoteKey(hid)
                }
            }
        }
    }

    private fun dispatchMediaKey(audioManager: AudioManager?, androidKeyCode: Int) {
        if (audioManager == null) return
        val now = SystemClock.uptimeMillis()
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, androidKeyCode, 0))
        audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, androidKeyCode, 0))
    }

    private fun stopCastAndSelf() {
        sessionJob?.cancel()
        uibcJob?.cancel()
        audioJob?.cancel()
        cleanupResources()
        SenderRuntimeBus.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupResources() {
        mainHandler.removeCallbacks(debouncedOrientationCheck)
        applyPhoneLandscapeLock(false)
        SecondScreenController.restoreOriginalDisplay(this)
        displayListener?.let { listener ->
            runCatching { displayManager?.unregisterDisplayListener(listener) }
        }
        displayListener = null
        synchronized(encoderLock) {
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
            runCatching {
                encoder?.stop()
                encoder?.release()
            }
            encoder = null
        }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { videoSocket?.close() }
        runCatching { audioSocket?.close() }
        runCatching { uibcSocket?.close() }
        runCatching { rtspSocket?.close() }
        videoSocket = null
        audioSocket = null
        uibcSocket = null
        rtspSocket = null
    }

    private fun buildNotification(targetName: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sender_notification_title),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.sender_notification_title))
            .setContentText(getString(R.string.sender_notification_text, targetName))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        cleanupResources()
        serviceScope.cancel()
        super.onDestroy()
    }
}
