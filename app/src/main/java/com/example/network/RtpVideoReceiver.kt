package com.example.network

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer

/**
 * Receives RTP / Annex-B H.264 video packets over UDP and decodes them into a Surface
 * via Android MediaCodec hardware decoder.
 */
class RtpVideoReceiver(private val scope: CoroutineScope) {

    private val _receivedFrames = MutableStateFlow(0L)
    val receivedFrames: StateFlow<Long> = _receivedFrames.asStateFlow()

    private val _streamResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val streamResolution: StateFlow<Pair<Int, Int>?> = _streamResolution.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var decoder: MediaCodec? = null
    private var renderSurface: Surface? = null

    fun attachSurface(surface: Surface?, width: Int = 1920, height: Int = 1080) {
        renderSurface = surface
        if (surface != null && receiveJob?.isActive == true && decoder == null) {
            initDecoder(surface, width, height)
        }
    }

    fun start(rtpPort: Int = 19000, width: Int = 1920, height: Int = 1080) {
        stop()
        renderSurface?.let { initDecoder(it, width, height) }

        receiveJob = scope.launch(Dispatchers.IO) {
            val socket = runCatching {
                DatagramSocket(rtpPort).apply {
                    reuseAddress = true
                    soTimeout = 2000
                }
            }.getOrNull() ?: return@launch
            udpSocket = socket

            val buffer = ByteArray(65535)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive && !socket.isClosed) {
                try {
                    socket.receive(packet)
                    val len = packet.length
                    if (len > 12) {
                        val payloadOffset = extractH264PayloadOffset(buffer, len)
                        if (payloadOffset in 0 until len) {
                            feedDecoder(buffer, payloadOffset, len - payloadOffset)
                        }
                        _receivedFrames.value += 1
                    }
                } catch (_: Exception) {
                    // Timeout or closed socket
                }
            }
        }
    }

    private fun extractH264PayloadOffset(data: ByteArray, length: Int): Int {
        // Check for direct Annex-B start code 00 00 00 01
        if (length >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        ) {
            return 0
        }
        // Standard RTP header is 12 bytes (+ optional CSRC/extension)
        val firstByte = data[0].toInt() and 0xFF
        val version = (firstByte shr 6) and 0x03
        if (version == 2 && length > 12) {
            val csrcCount = firstByte and 0x0F
            var offset = 12 + (csrcCount * 4)
            val hasExtension = (firstByte and 0x10) != 0
            if (hasExtension && offset + 4 <= length) {
                val extLen = ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
                offset += 4 + extLen * 4
            }
            return offset
        }
        return -1
    }

    private fun initDecoder(surface: Surface, width: Int, height: Int) {
        runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
        }
    }

    private fun feedDecoder(data: ByteArray, offset: Int, length: Int) {
        val codec = decoder ?: return
        runCatching {
            val inputIndex = codec.dequeueInputBuffer(5000)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data, offset, length)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    length,
                    System.nanoTime() / 1000L,
                    0
                )
            }
            val info = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                val outFormat = codec.outputFormat
                val w = outFormat.getInteger(MediaFormat.KEY_WIDTH)
                val h = outFormat.getInteger(MediaFormat.KEY_HEIGHT)
                if (w > 0 && h > 0) {
                    _streamResolution.value = w to h
                }
                outputIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        runCatching { udpSocket?.close() }
        udpSocket = null
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        _receivedFrames.value = 0L
        _streamResolution.value = null
    }
}
