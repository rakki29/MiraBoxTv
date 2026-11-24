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
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Receives H.264 video frames over either UDP RTP (port 19000) or framed TCP stream (port 19001)
 * and decodes them into the Compose TextureView Surface via MediaCodec hardware decoder.
 */
class RtpVideoReceiver(private val scope: CoroutineScope) {

    private val _receivedFrames = MutableStateFlow(0L)
    val receivedFrames: StateFlow<Long> = _receivedFrames.asStateFlow()

    private val _streamResolution = MutableStateFlow<Pair<Int, Int>?>(null)
    val streamResolution: StateFlow<Pair<Int, Int>?> = _streamResolution.asStateFlow()

    private var udpSocket: DatagramSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var tcpClientSocket: Socket? = null
    private var udpReceiveJob: Job? = null
    private var tcpReceiveJob: Job? = null
    private var decoder: MediaCodec? = null
    private var renderSurface: Surface? = null
    private var targetWidth: Int = 1920
    private var targetHeight: Int = 1080
    private var configuredWidth: Int = 0
    private var configuredHeight: Int = 0
    private var lastCodecConfig: ByteArray? = null
    private var lastPendingKeyFrame: ByteArray? = null
    private var lastFrameSize: Pair<Int, Int>? = null
    private val codecLock = Any()

    fun attachSurface(surface: Surface?, width: Int = 1920, height: Int = 1080) {
        synchronized(codecLock) {
            renderSurface = surface
            targetWidth = width
            targetHeight = height
            if (surface == null) {
                releaseDecoderLocked()
                return
            }
            if (udpReceiveJob?.isActive == true || tcpReceiveJob?.isActive == true) {
                val initW = lastFrameSize?.first ?: width
                val initH = lastFrameSize?.second ?: height
                if (decoder == null) {
                    initDecoderLocked(surface, initW, initH)
                }
                replayCachedConfigLocked()
            }
        }
    }

    fun start(
        rtpPort: Int = 19000,
        tcpVideoPort: Int = 19001,
        width: Int = 1920,
        height: Int = 1080
    ): Int {
        stop()
        synchronized(codecLock) {
            targetWidth = width
            targetHeight = height
            lastCodecConfig = null
            lastPendingKeyFrame = null
            lastFrameSize = null
            renderSurface?.let {
                initDecoderLocked(it, width, height)
            }
        }

        // Bind sockets synchronously BEFORE returning so RTSP PLAY 200 OK never races client connect()
        val uSocket = openUdpSocket(rtpPort)
        udpSocket = uSocket
        val tServer = openTcpServerSocket(tcpVideoPort)
        tcpServerSocket = tServer
        val actualTcpPort = tServer?.localPort ?: tcpVideoPort

        // 1. UDP RTP Listener
        if (uSocket != null) {
            udpReceiveJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(65535)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive && !uSocket.isClosed) {
                    try {
                        uSocket.receive(packet)
                        val len = packet.length
                        if (len > 12) {
                            val payloadOffset = extractH264PayloadOffset(buffer, len)
                            if (payloadOffset in 0 until len) {
                                val nal = buffer.copyOfRange(payloadOffset, len)
                                feedDecoder(nal, isConfig = false)
                            }
                            _receivedFrames.value += 1
                        }
                    } catch (_: Exception) {
                        if (!isActive || uSocket.isClosed) break
                    }
                }
            }
        }

        // 2. Framed TCP H.264 Stream Listener (reliable across all Wi-Fi routers)
        if (tServer != null) {
            tcpReceiveJob = scope.launch(Dispatchers.IO) {
                while (isActive && !tServer.isClosed) {
                    try {
                        val client = tServer.accept()
                        runCatching { tcpClientSocket?.close() }
                        tcpClientSocket = client
                        client.tcpNoDelay = true
                        val dis = DataInputStream(BufferedInputStream(client.getInputStream()))
                        while (isActive && !client.isClosed) {
                            val frameLen = dis.readInt()
                            if (frameLen <= 0 || frameLen > 4 * 1024 * 1024) break
                            val flags = dis.readInt()
                            val frameW = dis.readInt()
                            val frameH = dis.readInt()
                            if (frameW > 0 && frameH > 0) {
                                _streamResolution.value = frameW to frameH
                            }
                            val payload = ByteArray(frameLen)
                            dis.readFully(payload)
                            val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                            feedDecoder(
                                data = payload,
                                isConfig = isConfig,
                                flags = flags,
                                frameW = frameW,
                                frameH = frameH
                            )
                            _receivedFrames.value += 1
                        }
                    } catch (_: Exception) {
                        if (!isActive || tServer.isClosed) break
                    }
                }
            }
        }

        return actualTcpPort
    }

    private fun openUdpSocket(preferredPort: Int): DatagramSocket? {
        return try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 2000
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: Exception) {
            runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 2000
                    bind(InetSocketAddress(0))
                }
            }.getOrNull()
        }
    }

    private fun openTcpServerSocket(preferredPort: Int): ServerSocket? {
        return try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
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

    private fun extractH264PayloadOffset(data: ByteArray, length: Int): Int {
        if (length >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
            data[2] == 0.toByte() && data[3] == 1.toByte()
        ) {
            return 0
        }
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

    private fun initDecoderLocked(surface: Surface, width: Int, height: Int) {
        releaseDecoderLocked()
        runCatching {
            val safeW = width.coerceAtLeast(320)
            val safeH = height.coerceAtLeast(320)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeW, safeH)
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, surface, null, 0)
            codec.start()
            decoder = codec
            configuredWidth = safeW
            configuredHeight = safeH
        }
    }

    private fun releaseDecoderLocked() {
        runCatching {
            decoder?.stop()
            decoder?.release()
        }
        decoder = null
        configuredWidth = 0
        configuredHeight = 0
    }

    private fun replayCachedConfigLocked() {
        val codec = decoder ?: return
        lastCodecConfig?.let { cfg ->
            queueToCodecLocked(codec, cfg, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
        }
        lastPendingKeyFrame?.let { key ->
            queueToCodecLocked(codec, key, MediaCodec.BUFFER_FLAG_KEY_FRAME)
        }
    }

    private fun feedDecoder(
        data: ByteArray,
        isConfig: Boolean,
        flags: Int = 0,
        frameW: Int = 0,
        frameH: Int = 0
    ) {
        synchronized(codecLock) {
            if (frameW > 0 && frameH > 0) {
                lastFrameSize = frameW to frameH
            }
            if (isConfig) {
                lastCodecConfig = data.copyOf()
            } else if ((flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
                lastPendingKeyFrame = data.copyOf()
            }

            val surf = renderSurface ?: return
            val desiredW = if (frameW > 0) frameW else targetWidth
            val desiredH = if (frameH > 0) frameH else targetHeight

            if (decoder == null) {
                initDecoderLocked(surf, desiredW, desiredH)
                if (!isConfig) {
                    replayCachedConfigLocked()
                }
            } else if (isConfig && frameW > 0 && frameH > 0 &&
                (frameW != configuredWidth || frameH != configuredHeight)
            ) {
                initDecoderLocked(surf, frameW, frameH)
            }

            val codec = decoder ?: return
            val bufferFlags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else flags
            queueToCodecLocked(codec, data, bufferFlags)
        }
    }

    private fun queueToCodecLocked(codec: MediaCodec, data: ByteArray, bufferFlags: Int) {
        runCatching {
            val inputIndex = codec.dequeueInputBuffer(15_000)
            if (inputIndex >= 0) {
                val inputBuffer: ByteBuffer? = codec.getInputBuffer(inputIndex)
                inputBuffer?.clear()
                inputBuffer?.put(data)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    data.size,
                    System.nanoTime() / 1000L,
                    bufferFlags
                )
            }
            val info = MediaCodec.BufferInfo()
            var outputIndex = codec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                codec.releaseOutputBuffer(outputIndex, true)
                val outFormat = codec.outputFormat
                if (outFormat.containsKey(MediaFormat.KEY_WIDTH) &&
                    outFormat.containsKey(MediaFormat.KEY_HEIGHT)
                ) {
                    val w = outFormat.getInteger(MediaFormat.KEY_WIDTH)
                    val h = outFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    if (w > 0 && h > 0) {
                        _streamResolution.value = w to h
                    }
                }
                outputIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }
    }

    fun stop() {
        udpReceiveJob?.cancel()
        tcpReceiveJob?.cancel()
        runCatching { udpSocket?.close() }
        runCatching { tcpClientSocket?.close() }
        runCatching { tcpServerSocket?.close() }
        udpSocket = null
        tcpClientSocket = null
        tcpServerSocket = null
        synchronized(codecLock) {
            releaseDecoderLocked()
            lastCodecConfig = null
            lastPendingKeyFrame = null
            lastFrameSize = null
        }
        _receivedFrames.value = 0L
        _streamResolution.value = null
    }
}
