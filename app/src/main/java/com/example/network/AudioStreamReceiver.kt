package com.example.network

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Receives framed 16-bit PCM audio stream over TCP (port 19002) from the Phone Sender
 * and plays it with low latency through the TV Box AudioTrack media output.
 */
class AudioStreamReceiver(private val scope: CoroutineScope) {

    private val _audioActive = MutableStateFlow(false)
    val audioActive: StateFlow<Boolean> = _audioActive.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var acceptJob: Job? = null
    private var audioTrack: AudioTrack? = null

    fun start(preferredPort: Int = 19002): Int {
        stop()
        val server = openServerSocket(preferredPort)
        serverSocket = server
        val actualPort = server?.localPort ?: preferredPort

        if (server != null) {
            acceptJob = scope.launch(Dispatchers.IO) {
                while (isActive && !server.isClosed) {
                    try {
                        val client = server.accept()
                        runCatching { clientSocket?.close() }
                        clientSocket = client
                        client.tcpNoDelay = true
                        val dis = DataInputStream(BufferedInputStream(client.getInputStream()))

                        val sampleRate = dis.readInt().coerceIn(16000, 96000)
                        val channelCount = dis.readInt().coerceIn(1, 2)
                        val channelMask = if (channelCount == 1) {
                            AudioFormat.CHANNEL_OUT_MONO
                        } else {
                            AudioFormat.CHANNEL_OUT_STEREO
                        }
                        val minBuf = AudioTrack.getMinBufferSize(
                            sampleRate,
                            channelMask,
                            AudioFormat.ENCODING_PCM_16BIT
                        ).coerceAtLeast(4096)

                        val track = AudioTrack.Builder()
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                                    .build()
                            )
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(channelMask)
                                    .build()
                            )
                            .setBufferSizeInBytes(minBuf * 2)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()

                        releaseTrack()
                        audioTrack = track
                        track.play()
                        _audioActive.value = true

                        val buf = ByteArray(16384)
                        while (isActive && !client.isClosed) {
                            val chunkLen = dis.readInt()
                            if (chunkLen <= 0 || chunkLen > 65536) break
                            val data = if (chunkLen <= buf.size) buf else ByteArray(chunkLen)
                            dis.readFully(data, 0, chunkLen)
                            track.write(data, 0, chunkLen)
                        }
                    } catch (_: Exception) {
                        if (!isActive || server.isClosed) break
                    } finally {
                        _audioActive.value = false
                        releaseTrack()
                    }
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
            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
            }.getOrNull()
        }
    }

    private fun releaseTrack() {
        runCatching {
            audioTrack?.stop()
            audioTrack?.release()
        }
        audioTrack = null
    }

    fun stop() {
        acceptJob?.cancel()
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
        clientSocket = null
        serverSocket = null
        releaseTrack()
        _audioActive.value = false
    }
}
