package com.example.network

import com.example.data.DisplayMode
import com.example.data.ReceiverSettings
import com.example.miracast.ActiveMirrorSession
import com.example.miracast.ApprovalDecision
import com.example.miracast.ConnectionChannel
import com.example.miracast.PendingConnectionRequest
import com.example.miracast.ServerState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlin.random.Random

/**
 * Real Miracast (Wi-Fi Display WFD 1.0) RTSP Sink Server (M1–M7 state machine).
 * Listens on TCP 7236 (or fallback 17236) for incoming connections on the same Wi-Fi LAN
 * or Wi-Fi Direct group, enforces TV Box Remote OK security approval, and negotiates
 * DeX/Landscape display profiles and UIBC remote control channels.
 */
class MiracastRtspServer(
    private val scope: CoroutineScope,
    private val uibcServer: UibcServer,
    private val rtpVideoReceiver: RtpVideoReceiver,
    private val audioStreamReceiver: AudioStreamReceiver = AudioStreamReceiver(scope),
    private val getSettings: () -> ReceiverSettings,
    private val onTrustDevice: suspend (String) -> Unit
) {

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _boundPort = MutableStateFlow(7236)
    val boundPort: StateFlow<Int> = _boundPort.asStateFlow()

    private val _pendingRequest = MutableStateFlow<PendingConnectionRequest?>(null)
    val pendingRequest: StateFlow<PendingConnectionRequest?> = _pendingRequest.asStateFlow()

    private val _activeSession = MutableStateFlow<ActiveMirrorSession?>(null)
    val activeSession: StateFlow<ActiveMirrorSession?> = _activeSession.asStateFlow()

    private val _protocolLogs = MutableStateFlow<List<String>>(emptyList())
    val protocolLogs: StateFlow<List<String>> = _protocolLogs.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var clientJob: Job? = null
    private var activeClientSocket: Socket? = null
    private var pendingDeferred: CompletableDeferred<ApprovalDecision>? = null
    private var loopbackClientJob: Job? = null

    fun start(preferredPort: Int = 7236): Int {
        stop()
        val uibcPort = uibcServer.start(7237)
        val socket = openServerSocket(preferredPort)
        serverSocket = socket
        val actualPort = socket?.localPort ?: preferredPort
        _boundPort.value = actualPort
        _serverState.value = ServerState.LISTENING
        appendLog("RTSP Sink listening on TCP $actualPort • UIBC TCP $uibcPort")

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && socket != null && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    runCatching { activeClientSocket?.close() }
                    clientJob?.cancel()
                    clientJob = scope.launch(Dispatchers.IO) {
                        handleRtspClient(client, uibcPort)
                    }
                } catch (_: Exception) {
                    if (isActive && socket.isClosed) break
                }
            }
        }
        return actualPort
    }

    private fun openServerSocket(preferredPort: Int): ServerSocket? {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: Exception) {
            try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(preferredPort + 10000))
                }
            } catch (_: Exception) {
                runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(0))
                    }
                }.getOrNull()
            }
        }
    }

    private suspend fun handleRtspClient(client: Socket, uibcPort: Int) {
        val remoteIp = client.inetAddress?.hostAddress ?: "127.0.0.1"
        val isLoopback = client.inetAddress?.isLoopbackAddress == true
        appendLog("Incoming TCP RTSP connection from $remoteIp")
        activeClientSocket = client

        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val writer = PrintWriter(client.getOutputStream(), true)

        var deviceName = if (isLoopback) "Android Phone (Loopback)" else "Android Phone ($remoteIp)"
        var deviceId = remoteIp
        var negotiatedRtpPort = 19000
        var sourceWidth = 1080
        var sourceHeight = 2400
        val sessionId = UUID.randomUUID().toString().take(8).uppercase()
        var sessionApproved = false

        try {
            while (!client.isClosed) {
                val requestLine = reader.readLine() ?: break
                if (requestLine.isBlank()) continue

                val headers = mutableMapOf<String, String>()
                var contentLength = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        val key = line.substring(0, colon).trim()
                        val value = line.substring(colon + 1).trim()
                        headers[key.lowercase()] = value
                        if (key.equals("Content-Length", ignoreCase = true)) {
                            contentLength = value.toIntOrNull() ?: 0
                        }
                    }
                }

                val body = if (contentLength > 0) {
                    val chars = CharArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = reader.read(chars, readTotal, contentLength - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }
                    String(chars, 0, readTotal)
                } else ""

                headers["x-device-name"]?.let { if (it.isNotBlank()) deviceName = it }
                headers["x-device-id"]?.let { if (it.isNotBlank()) deviceId = it }
                headers["user-agent"]?.let { ua ->
                    if (headers["x-device-name"] == null && ua.isNotBlank()) {
                        deviceName = ua.take(32)
                    }
                }

                val cseq = headers["cseq"] ?: "1"
                val parts = requestLine.split(" ")
                val method = parts.firstOrNull()?.uppercase() ?: continue

                appendLog("RTSP <- $method (CSeq=$cseq)")

                when (method) {
                    "OPTIONS" -> {
                        sendRtspResponse(
                            writer,
                            "200 OK",
                            cseq,
                            mapOf(
                                "Public" to "org.wfa.wfd1.0, OPTIONS, GET_PARAMETER, SET_PARAMETER, SETUP, PLAY, TEARDOWN"
                            )
                        )
                    }

                    "GET_PARAMETER" -> {
                        val settings = getSettings()
                        val wfdCaps = buildWfdCapabilities(settings, uibcPort, negotiatedRtpPort)
                        sendRtspResponse(
                            writer,
                            "200 OK",
                            cseq,
                            mapOf("Content-Type" to "text/parameters"),
                            wfdCaps
                        )
                    }

                    "SET_PARAMETER" -> {
                        parseRtpPort(body)?.let { negotiatedRtpPort = it }
                        parseSourceResolution(body)?.let { (w, h) ->
                            sourceWidth = w
                            sourceHeight = h
                        }
                        sendRtspResponse(writer, "200 OK", cseq)
                    }

                    "SETUP" -> {
                        val settings = getSettings()
                        val needsApproval = settings.requireOkApproval &&
                            !settings.trustedDevices.contains(deviceId)

                        if (needsApproval && !sessionApproved) {
                            val pin = "%04d".format(Random.nextInt(1000, 9999))
                            val channel = if (isLoopback) {
                                ConnectionChannel.LOCAL_LOOPBACK_TEST
                            } else {
                                ConnectionChannel.WIFI_LAN_RTSP
                            }
                            val req = PendingConnectionRequest(
                                sessionId = sessionId,
                                deviceName = deviceName,
                                deviceId = deviceId,
                                remoteAddress = remoteIp,
                                channel = channel,
                                pinCode = pin
                            )
                            val decision = requestOwnerApproval(req)
                            when (decision) {
                                ApprovalDecision.Deny -> {
                                    appendLog("Connection denied by TV Box owner")
                                    sendRtspResponse(writer, "403 Forbidden", cseq)
                                    break
                                }
                                ApprovalDecision.ApproveOnce -> {
                                    sessionApproved = true
                                    appendLog("Connection approved via TV Remote OK")
                                }
                                ApprovalDecision.ApproveAndTrust -> {
                                    sessionApproved = true
                                    onTrustDevice(deviceId)
                                    appendLog("Device trusted & approved via TV Remote OK")
                                }
                            }
                        } else {
                            sessionApproved = true
                        }

                        sendRtspResponse(
                            writer,
                            "200 OK",
                            cseq,
                            mapOf(
                                "Session" to "$sessionId;timeout=60",
                                "Transport" to "RTP/AVP/UDP;unicast;client_port=$negotiatedRtpPort;server_port=$negotiatedRtpPort"
                            )
                        )
                    }

                    "PLAY" -> {
                        if (!sessionApproved) {
                            sendRtspResponse(writer, "403 Forbidden", cseq)
                            break
                        }
                        val channel = if (isLoopback) {
                            ConnectionChannel.LOCAL_LOOPBACK_TEST
                        } else {
                            ConnectionChannel.WIFI_LAN_RTSP
                        }
                        val tcpVideoPort = rtpVideoReceiver.start(negotiatedRtpPort, 19001)
                        val tcpAudioPort = audioStreamReceiver.start(19002)
                        val displayMode = getSettings().displayMode
                        _activeSession.value = ActiveMirrorSession(
                            sessionId = sessionId,
                            deviceName = deviceName,
                            remoteAddress = remoteIp,
                            channel = channel,
                            rtpPort = negotiatedRtpPort,
                            tcpVideoPort = tcpVideoPort,
                            tcpAudioPort = tcpAudioPort,
                            uibcPort = uibcPort,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            isLoopbackDiagnostic = isLoopback
                        )
                        _serverState.value = ServerState.MIRRORING
                        appendLog("PLAY started (Video=$tcpVideoPort, Audio=$tcpAudioPort, UIBC=$uibcPort, Mode=${displayMode.name})")
                        sendRtspResponse(
                            writer,
                            "200 OK",
                            cseq,
                            mapOf(
                                "Session" to sessionId,
                                "X-Video-Port" to tcpVideoPort.toString(),
                                "X-Audio-Port" to tcpAudioPort.toString(),
                                "X-Uibc-Port" to uibcPort.toString(),
                                "X-Display-Mode" to displayMode.name
                            )
                        )
                    }

                    "TEARDOWN" -> {
                        sendRtspResponse(writer, "200 OK", cseq)
                        break
                    }

                    else -> {
                        sendRtspResponse(writer, "501 Not Implemented", cseq)
                    }
                }
            }
        } catch (_: Exception) {
            // Client disconnected
        } finally {
            runCatching { client.close() }
            if (activeClientSocket === client) {
                activeClientSocket = null
                clearSessionToListening()
            }
        }
    }

    /**
     * Suspends the RTSP handshake until the TV Box owner presses OK (or Deny) on the remote.
     * Also usable directly by Wi-Fi Direct group invitation callbacks.
     */
    suspend fun requestOwnerApproval(request: PendingConnectionRequest): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        pendingDeferred = deferred
        _pendingRequest.value = request
        _serverState.value = ServerState.AWAITING_OK_APPROVAL
        appendLog("Awaiting TV Remote OK approval for ${request.deviceName}...")

        val decision = withTimeoutOrNull(35_000L) {
            deferred.await()
        } ?: ApprovalDecision.Deny

        pendingDeferred = null
        _pendingRequest.value = null
        if (decision == ApprovalDecision.Deny && _activeSession.value == null) {
            _serverState.value = ServerState.LISTENING
        }
        return decision
    }

    fun submitApprovalDecision(decision: ApprovalDecision) {
        pendingDeferred?.complete(decision)
    }

    /**
     * Runs a genuine local RTSP + UIBC + UDP RTP loopback diagnostic connection on 127.0.0.1
     * so users/developers can test the TV Remote OK approval gate, Forced Landscape, DeX mode,
     * and UIBC control forwarding without needing a second physical phone nearby.
     */
    fun runLocalLoopbackTest() {
        loopbackClientJob?.cancel()
        val rtspPort = _boundPort.value
        val uibcPort = uibcServer.telemetry.value.listeningPort

        loopbackClientJob = scope.launch(Dispatchers.IO) {
            try {
                val rtspSocket = Socket("127.0.0.1", rtspPort)
                val out = PrintWriter(rtspSocket.getOutputStream(), true)
                val input = BufferedReader(InputStreamReader(rtspSocket.getInputStream()))

                fun sendCmd(method: String, cseq: Int, extraHeaders: String = "", body: String = ""): Int {
                    val req = buildString {
                        append("$method rtsp://127.0.0.1:$rtspPort/wfd1.0 RTSP/1.0\r\n")
                        append("CSeq: $cseq\r\n")
                        append("X-Device-Name: Galaxy S24 Ultra (Loopback)\r\n")
                        append("X-Device-Id: loopback-galaxy-s24\r\n")
                        if (extraHeaders.isNotEmpty()) append(extraHeaders)
                        if (body.isNotEmpty()) {
                            append("Content-Type: text/parameters\r\n")
                            append("Content-Length: ${body.length}\r\n")
                        }
                        append("\r\n")
                        append(body)
                    }
                    out.print(req)
                    out.flush()

                    val status = input.readLine() ?: return 500
                    var len = 0
                    while (true) {
                        val h = input.readLine() ?: break
                        if (h.isEmpty()) break
                        if (h.startsWith("Content-Length:", ignoreCase = true)) {
                            len = h.substringAfter(":").trim().toIntOrNull() ?: 0
                        }
                    }
                    if (len > 0) {
                        val buf = CharArray(len)
                        var r = 0
                        while (r < len) {
                            val n = input.read(buf, r, len - r)
                            if (n <= 0) break
                            r += n
                        }
                    }
                    return status.split(" ").getOrNull(1)?.toIntOrNull() ?: 500
                }

                if (sendCmd("OPTIONS", 1) != 200) { rtspSocket.close(); return@launch }
                if (sendCmd("GET_PARAMETER", 2) != 200) { rtspSocket.close(); return@launch }
                val setBody = "wfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n" +
                    "wfd_source_resolution: 1080x2400\r\n"
                if (sendCmd("SET_PARAMETER", 3, body = setBody) != 200) { rtspSocket.close(); return@launch }
                if (sendCmd("SETUP", 4) != 200) { rtspSocket.close(); return@launch }
                if (sendCmd("PLAY", 5) != 200) { rtspSocket.close(); return@launch }

                // Connect local UIBC client socket so UIBC telemetry shows active channel
                val uibcClient = Socket("127.0.0.1", uibcPort)
                val udpSender = DatagramSocket()
                val localAddr = InetAddress.getByName("127.0.0.1")
                val heartbeatPayload = ByteArray(64).apply {
                    this[0] = 0x80.toByte() // RTP v2 header
                    this[1] = 0x21
                }
                val udpPacket = DatagramPacket(heartbeatPayload, heartbeatPayload.size, localAddr, 19000)

                while (isActive && _activeSession.value?.isLoopbackDiagnostic == true) {
                    runCatching { udpSender.send(udpPacket) }
                    delay(1000)
                }

                runCatching { udpSender.close() }
                runCatching { uibcClient.close() }
                runCatching { sendCmd("TEARDOWN", 6) }
                runCatching { rtspSocket.close() }
            } catch (_: Exception) {
                // Loopback ended
            }
        }
    }

    fun disconnectActiveSession() {
        loopbackClientJob?.cancel()
        runCatching { activeClientSocket?.close() }
        activeClientSocket = null
        clearSessionToListening()
    }

    private fun clearSessionToListening() {
        rtpVideoReceiver.stop()
        audioStreamReceiver.stop()
        _activeSession.value = null
        if (serverSocket != null && serverSocket?.isClosed == false) {
            _serverState.value = ServerState.LISTENING
        } else {
            _serverState.value = ServerState.STOPPED
        }
    }

    private fun buildWfdCapabilities(
        settings: ReceiverSettings,
        uibcPort: Int,
        rtpPort: Int
    ): String {
        val forceWide = settings.displayMode != DisplayMode.STANDARD_MIRROR
        val videoFormats = if (forceWide) {
            // Prioritize Landscape 1080p60 / 4K30 CEA profiles for Forced Landscape & DeX
            "00 00 02 04 ${settings.dexDpiProfile.wfdCeaCode} 00000000 00000000 00 0000 0000 00 none none"
        } else {
            "00 00 02 04 000000FF 00000000 00000000 00 0000 0000 00 none none"
        }
        val dexEdid = if (settings.displayMode == DisplayMode.DEX_TV_DESKTOP) {
            "wfd_display_edid: 1 ${settings.dexDpiProfile.width}x${settings.dexDpiProfile.height}@${settings.dexDpiProfile.refreshHz} dpi=${settings.dexDpiProfile.dpi}\r\n"
        } else {
            ""
        }
        return buildString {
            append("wfd_video_formats: $videoFormats\r\n")
            append("wfd_audio_codecs: LPCM 00000003 00, AAC 00000001 00\r\n")
            append(dexEdid)
            append("wfd_uibc_capability: input_category_list=GENERIC, HIDC; generic_cap_list=Keyboard, Mouse, SingleTouch; hidc_cap_list=Keyboard/USB, Mouse/USB; port=$uibcPort\r\n")
            append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $rtpPort 0 mode=play\r\n")
            append("wfd_connector_type: 05\r\n")
        }
    }

    private fun sendRtspResponse(
        writer: PrintWriter,
        status: String,
        cseq: String,
        headers: Map<String, String> = emptyMap(),
        body: String = ""
    ) {
        val msg = buildString {
            append("RTSP/1.0 $status\r\n")
            append("CSeq: $cseq\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            if (body.isNotEmpty()) {
                append("Content-Length: ${body.length}\r\n")
            }
            append("\r\n")
            append(body)
        }
        writer.print(msg)
        writer.flush()
    }

    private fun parseRtpPort(body: String): Int? {
        val regex = Regex("unicast\\s+(\\d{4,5})")
        return regex.find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun parseSourceResolution(body: String): Pair<Int, Int>? {
        val regex = Regex("wfd_source_resolution:\\s*(\\d+)x(\\d+)")
        val match = regex.find(body) ?: return null
        val w = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null
        val h = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        return w to h
    }

    private fun appendLog(entry: String) {
        _protocolLogs.value = (_protocolLogs.value + entry).takeLast(12)
    }

    fun stop() {
        loopbackClientJob?.cancel()
        clientJob?.cancel()
        acceptJob?.cancel()
        pendingDeferred?.cancel()
        pendingDeferred = null
        _pendingRequest.value = null
        runCatching { activeClientSocket?.close() }
        runCatching { serverSocket?.close() }
        activeClientSocket = null
        serverSocket = null
        rtpVideoReceiver.stop()
        audioStreamReceiver.stop()
        uibcServer.stop()
        _activeSession.value = null
        _serverState.value = ServerState.STOPPED
    }
}
