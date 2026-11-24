package com.example.sender

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.miracast.SenderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    val errorMessage: String? = null
)

object SenderRuntimeBus {
    private val _status = MutableStateFlow(SenderRuntimeStatus())
    val status: StateFlow<SenderRuntimeStatus> = _status.asStateFlow()

    fun update(transform: (SenderRuntimeStatus) -> SenderRuntimeStatus) {
        _status.update(transform)
    }

    fun reset() {
        _status.value = SenderRuntimeStatus()
    }
}

/**
 * Android 14+ compliant MediaProjection Foreground Service that connects to MiraBox TV
 * via RTSP M1-M7 (waiting for Remote OK Approval), encodes the phone display to H.264,
 * streams it over TCP 19001, and receives UIBC TV Remote commands over TCP 7237.
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
        const val EXTRA_TARGET_NAME = "target_name"

        private const val CHANNEL_ID = "mirabox_cast_sender"
        private const val NOTIFICATION_ID = 4102
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sessionJob: Job? = null
    private var uibcJob: Job? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var rtspSocket: Socket? = null
    private var videoSocket: Socket? = null
    private var uibcSocket: Socket? = null

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
        val targetName = intent?.getStringExtra(EXTRA_TARGET_NAME) ?: host

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
            targetName = targetName
        )

        return START_NOT_STICKY
    }

    private fun startStreamingSession(
        resultCode: Int,
        resultData: Intent,
        host: String,
        rtspPort: Int,
        uibcPort: Int,
        videoPort: Int,
        targetName: String
    ) {
        sessionJob?.cancel()
        uibcJob?.cancel()

        sessionJob = serviceScope.launch {
            try {
                SenderRuntimeBus.update {
                    SenderRuntimeStatus(
                        state = SenderState.AWAITING_TV_OK,
                        targetHost = host,
                        targetName = targetName
                    )
                }

                // 1. RTSP Handshake with TV Box (suspends on SETUP until TV Remote OK is pressed)
                val rtsp = Socket()
                rtsp.connect(InetSocketAddress(host, rtspPort), 8000)
                rtspSocket = rtsp
                val out = PrintWriter(rtsp.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(rtsp.getInputStream()))
                val phoneName = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                val phoneRes = resources.displayMetrics
                val srcW = phoneRes.widthPixels
                val srcH = phoneRes.heightPixels

                fun sendRtsp(method: String, cseq: Int, body: String = ""): Int {
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
                    val statusLine = input.readLine() ?: return 500
                    var len = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            len = line.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    if (len > 0) {
                        val buf = CharArray(len)
                        var read = 0
                        while (read < len) {
                            val n = input.read(buf, read, len - read)
                            if (n <= 0) break
                            read += n
                        }
                    }
                    return statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
                }

                if (sendRtsp("OPTIONS", 1) != 200) error("RTSP OPTIONS failed")
                if (sendRtsp("GET_PARAMETER", 2) != 200) error("RTSP GET_PARAMETER failed")
                sendRtsp(
                    "SET_PARAMETER",
                    3,
                    "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n" +
                        "wfd_source_resolution: ${srcW}x${srcH}\r\n"
                )
                val setupCode = sendRtsp("SETUP", 4)
                if (setupCode == 403) error("Denied by TV Box owner")
                if (setupCode != 200) error("RTSP SETUP failed ($setupCode)")
                if (sendRtsp("PLAY", 5) != 200) error("RTSP PLAY failed")

                // 2. Start UIBC Listener Client to receive TV Remote commands
                startUibcReceiver(host, uibcPort, srcW, srcH)

                // 3. Connect TCP Video Socket & Initialize MediaProjection + H.264 Encoder
                val vSocket = Socket()
                vSocket.connect(InetSocketAddress(host, videoPort), 8000)
                vSocket.tcpNoDelay = true
                videoSocket = vSocket
                val videoOut = DataOutputStream(BufferedOutputStream(vSocket.getOutputStream()))

                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val projection = mpm.getMediaProjection(resultCode, resultData)
                    ?: error("MediaProjection unavailable")
                mediaProjection = projection
                projection.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCastAndSelf()
                    }
                }, null)

                val encW = 1280
                val encH = 720
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, 3_500_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }

                val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val inputSurface = codec.createInputSurface()
                codec.start()
                encoder = codec

                virtualDisplay = projection.createVirtualDisplay(
                    "MiraBoxCast",
                    encW,
                    encH,
                    resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null,
                    null
                )

                SenderRuntimeBus.update {
                    it.copy(
                        state = SenderState.STREAMING,
                        errorMessage = null
                    )
                }

                val bufferInfo = MediaCodec.BufferInfo()
                while (isActive && !vSocket.isClosed) {
                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 25_000)
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
                            videoOut.write(frameBytes)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                                videoOut.flush()
                            } else {
                                videoOut.flush()
                            }
                            SenderRuntimeBus.update { st ->
                                st.copy(framesStreamed = st.framesStreamed + 1)
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                    }
                }
            } catch (e: Exception) {
                SenderRuntimeBus.update {
                    it.copy(
                        state = SenderState.ERROR,
                        errorMessage = e.message ?: "Connection failed"
                    )
                }
            } finally {
                cleanupResources()
            }
        }
    }

    private fun startUibcReceiver(host: String, uibcPort: Int, dispW: Int, dispH: Int) {
        uibcJob = serviceScope.launch {
            runCatching {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, uibcPort), 6000)
                uibcSocket = sock
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
                                    acc?.executeRemoteKey(hid)
                                    SenderRuntimeBus.update {
                                        it.copy(
                                            uibcEventsExecuted = it.uibcEventsExecuted + 1,
                                            lastRemoteEvent = "KEY 0x%04X".format(hid)
                                        )
                                    }
                                }
                            }
                            0 -> { // TouchDown (Air-Mouse tap)
                                if (body.size >= 9) {
                                    val px = ((body[5].toInt() and 0xFF) shl 8) or
                                        (body[6].toInt() and 0xFF)
                                    val py = ((body[7].toInt() and 0xFF) shl 8) or
                                        (body[8].toInt() and 0xFF)
                                    val normX = (px.toFloat() / 1920f).coerceIn(0f, 1f)
                                    val normY = (py.toFloat() / 1080f).coerceIn(0f, 1f)
                                    acc?.executeRemoteTap(normX, normY)
                                    SenderRuntimeBus.update {
                                        it.copy(
                                            uibcEventsExecuted = it.uibcEventsExecuted + 1,
                                            lastRemoteEvent = "TAP ($px, $py)"
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

    private fun stopCastAndSelf() {
        sessionJob?.cancel()
        uibcJob?.cancel()
        cleanupResources()
        SenderRuntimeBus.reset()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupResources() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching {
            encoder?.stop()
            encoder?.release()
        }
        encoder = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { videoSocket?.close() }
        runCatching { uibcSocket?.close() }
        runCatching { rtspSocket?.close() }
        videoSocket = null
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
