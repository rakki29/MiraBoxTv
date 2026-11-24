package com.example.network

import android.view.KeyEvent
import com.example.data.DisplayMode
import com.example.miracast.UibcTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real Wi-Fi Display User Input Back Channel (UIBC) TCP server.
 * Encodes TV Remote KeyEvents and Air-Mouse / Touch coordinates into WFD UIBC binary packets
 * and forwards them to the connected phone sender.
 */
class UibcServer(private val scope: CoroutineScope) {

    private val _telemetry = MutableStateFlow(UibcTelemetry())
    val telemetry: StateFlow<UibcTelemetry> = _telemetry.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var clientOut: OutputStream? = null
    private var acceptJob: Job? = null
    private var writerJob: Job? = null

    private val packetQueue = Channel<QueuedPacket>(capacity = Channel.BUFFERED)

    private data class QueuedPacket(
        val bytes: ByteArray,
        val summary: String
    )

    fun start(preferredPort: Int = 7237): Int {
        stop()
        val socket = openServerSocket(preferredPort)
        serverSocket = socket
        val actualPort = socket?.localPort ?: preferredPort
        _telemetry.update {
            it.copy(
                listeningPort = actualPort,
                isClientConnected = false,
                clientAddress = null
            )
        }

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && socket != null && !socket.isClosed) {
                try {
                    val accepted = socket.accept()
                    clientSocket?.close()
                    clientSocket = accepted
                    clientOut = accepted.getOutputStream()
                    val remoteAddr = accepted.inetAddress?.hostAddress ?: "unknown"
                    _telemetry.update {
                        it.copy(
                            isClientConnected = true,
                            clientAddress = remoteAddr
                        )
                    }
                    // Keep reading until remote closes
                    val buf = ByteArray(256)
                    val input = accepted.getInputStream()
                    while (isActive && !accepted.isClosed) {
                        val read = input.read(buf)
                        if (read < 0) break
                    }
                } catch (_: Exception) {
                    // Socket closed or reset
                } finally {
                    _telemetry.update {
                        it.copy(isClientConnected = false, clientAddress = null)
                    }
                }
            }
        }

        writerJob = scope.launch(Dispatchers.IO) {
            for (pkt in packetQueue) {
                try {
                    val out = clientOut
                    if (out != null && clientSocket?.isClosed == false) {
                        out.write(pkt.bytes)
                        out.flush()
                    }
                } catch (_: Exception) {
                    _telemetry.update {
                        it.copy(isClientConnected = false, clientAddress = null)
                    }
                }
                val hex = pkt.bytes.joinToString(" ") { b -> "%02X".format(b) }
                _telemetry.update {
                    it.copy(
                        packetsSent = it.packetsSent + 1,
                        lastPacketSummary = pkt.summary,
                        lastPacketHex = hex
                    )
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

    fun sendKeyEvent(keyCode: Int, isDown: Boolean, label: String = keyName(keyCode)) {
        val ieId = if (isDown) 3 else 4 // WFD Generic Key Down (3) / Key Up (4)
        val bodyLength = 5
        val totalLength = 4 + 3 + bodyLength
        val buf = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        // UIBC Header: version=0, T=0, category=0 (GENERIC), totalLength
        buf.put(0x00.toByte())
        buf.put(0x00.toByte()) // Input Category GENERIC
        buf.putShort(totalLength.toShort())
        // Generic Input Body IE
        buf.put(ieId.toByte())
        buf.putShort(bodyLength.toShort())
        buf.put(0x00.toByte()) // Reserved
        buf.putShort(mapAndroidKeyToHid(keyCode).toShort())
        buf.putShort(0x0000.toShort())

        val actionTag = if (isDown) "DOWN" else "UP"
        packetQueue.trySend(QueuedPacket(buf.array(), "KEY_$actionTag: $label"))
    }

    fun sendTouchEvent(
        action: Int, // 0 = DOWN, 1 = UP, 2 = MOVE
        normalizedX: Float,
        normalizedY: Float,
        width: Int = 1920,
        height: Int = 1080
    ) {
        val px = (normalizedX.coerceIn(0f, 1f) * width).toInt()
        val py = (normalizedY.coerceIn(0f, 1f) * height).toInt()
        val bodyLength = 6 // numPointers(1) + pointerId(1) + X(2) + Y(2)
        val totalLength = 4 + 3 + bodyLength
        val buf = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x00.toByte())
        buf.put(0x00.toByte()) // GENERIC
        buf.putShort(totalLength.toShort())
        buf.put(action.toByte()) // 0=TouchDown, 1=TouchUp, 2=TouchMove
        buf.putShort(bodyLength.toShort())
        buf.put(0x01.toByte()) // 1 pointer
        buf.put(0x00.toByte()) // pointer id 0
        buf.putShort(px.toShort())
        buf.putShort(py.toShort())

        val typeName = when (action) {
            0 -> "TAP_DOWN"
            1 -> "TAP_UP"
            else -> "CURSOR_MOVE"
        }
        packetQueue.trySend(QueuedPacket(buf.array(), "$typeName ($px, $py)"))
    }

    fun sendDisplayMode(mode: DisplayMode) {
        val bodyLength = 2
        val totalLength = 4 + 3 + bodyLength
        val buf = ByteBuffer.allocate(totalLength).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x00.toByte())
        buf.put(0x00.toByte()) // GENERIC
        buf.putShort(totalLength.toShort())
        buf.put(0x50.toByte()) // Custom DisplayMode Sync IE
        buf.putShort(bodyLength.toShort())
        buf.put(mode.ordinal.toByte())
        buf.put(0x00.toByte())
        packetQueue.trySend(QueuedPacket(buf.array(), "MODE -> ${mode.name}"))
    }

    fun stop() {
        acceptJob?.cancel()
        writerJob?.cancel()
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        clientOut = null
        serverSocket = null
        _telemetry.update {
            it.copy(isClientConnected = false, clientAddress = null)
        }
    }

    private fun mapAndroidKeyToHid(keyCode: Int): Int = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> 0x0052
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x0051
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x0050
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x004F
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x0028
        KeyEvent.KEYCODE_BACK -> 0x0029
        KeyEvent.KEYCODE_HOME -> 0x004A
        KeyEvent.KEYCODE_APP_SWITCH -> 0x0076
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> 0x004B
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 0x004E
        KeyEvent.KEYCODE_VOLUME_MUTE -> 0x007F
        KeyEvent.KEYCODE_VOLUME_UP -> 0x0080
        KeyEvent.KEYCODE_VOLUME_DOWN -> 0x0081
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> 0x00E8
        KeyEvent.KEYCODE_MEDIA_NEXT -> 0x00EB
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 0x00EC
        KeyEvent.KEYCODE_1 -> 0x001E
        KeyEvent.KEYCODE_2 -> 0x001F
        KeyEvent.KEYCODE_3 -> 0x0020
        KeyEvent.KEYCODE_4 -> 0x0021
        KeyEvent.KEYCODE_5 -> 0x0022
        KeyEvent.KEYCODE_6 -> 0x0023
        KeyEvent.KEYCODE_7 -> 0x0024
        KeyEvent.KEYCODE_8 -> 0x0025
        KeyEvent.KEYCODE_9 -> 0x0026
        KeyEvent.KEYCODE_0 -> 0x0027
        else -> keyCode and 0xFFFF
    }

    private fun keyName(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "DPAD_UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD_DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD_LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD_RIGHT"
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER -> "OK"
        KeyEvent.KEYCODE_BACK -> "BACK"
        KeyEvent.KEYCODE_HOME -> "HOME"
        KeyEvent.KEYCODE_APP_SWITCH -> "RECENTS"
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> "P+"
        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> "P-"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "MUTE"
        KeyEvent.KEYCODE_VOLUME_UP -> "VOL+"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "VOL-"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "PLAY/PAUSE"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREV"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")
    }
}
