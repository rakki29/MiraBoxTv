package com.example

import android.view.KeyEvent
import com.example.data.DisplayMode
import com.example.data.ReceiverSettings
import com.example.miracast.ApprovalDecision
import com.example.miracast.ServerState
import com.example.network.MiracastRtspServer
import com.example.network.RtpVideoReceiver
import com.example.network.UibcServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

class MiracastProtocolTest {

    @Test
    fun `uibc server encodes and sends key and touch packets`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val uibc = UibcServer(scope)
        val port = uibc.start(0)
        assertTrue(port > 0)

        val client = Socket("127.0.0.1", port)
        delay(100)
        uibc.sendKeyEvent(KeyEvent.KEYCODE_DPAD_CENTER, true)
        uibc.sendTouchEvent(0, 0.5f, 0.5f, 1920, 1080)
        delay(150)

        val telemetry = uibc.telemetry.value
        assertTrue(telemetry.packetsSent >= 2)
        assertTrue(telemetry.lastPacketHex.isNotEmpty())

        client.close()
        uibc.stop()
        scope.cancel()
    }

    @Test
    fun `rtsp server enforces ok approval before play and advertises dex edid`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val uibc = UibcServer(scope)
        val rtp = RtpVideoReceiver(scope)
        val settings = ReceiverSettings(
            requireOkApproval = true,
            displayMode = DisplayMode.DEX_TV_DESKTOP
        )
        val server = MiracastRtspServer(
            scope = scope,
            uibcServer = uibc,
            rtpVideoReceiver = rtp,
            getSettings = { settings },
            onTrustDevice = {}
        )
        val rtspPort = server.start(0)
        assertEquals(ServerState.LISTENING, server.serverState.value)

        // Auto-approve when pending request arrives
        val approver = scope.launch {
            while (server.pendingRequest.value == null) {
                delay(20)
            }
            assertEquals(ServerState.AWAITING_OK_APPROVAL, server.serverState.value)
            server.submitApprovalDecision(ApprovalDecision.ApproveOnce)
        }

        val socket = Socket("127.0.0.1", rtspPort)
        val out = PrintWriter(socket.getOutputStream(), true)
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))

        out.print("GET_PARAMETER rtsp://127.0.0.1:$rtspPort/wfd1.0 RTSP/1.0\r\nCSeq: 1\r\n\r\n")
        out.flush()
        val getResp = readRtspResponse(input)
        assertTrue(getResp.contains("200 OK"))
        assertTrue(getResp.contains("wfd_display_edid"))
        assertTrue(getResp.contains("wfd_uibc_capability"))

        out.print("SETUP rtsp://127.0.0.1:$rtspPort/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\nX-Device-Name: TestPhone\r\n\r\n")
        out.flush()
        val setupResp = readRtspResponse(input)
        assertTrue(setupResp.contains("200 OK"))

        out.print("PLAY rtsp://127.0.0.1:$rtspPort/wfd1.0 RTSP/1.0\r\nCSeq: 3\r\n\r\n")
        out.flush()
        val playResp = readRtspResponse(input)
        assertTrue(playResp.contains("200 OK"))
        assertEquals(ServerState.MIRRORING, server.serverState.value)
        assertNotNull(server.activeSession.value)

        approver.join()
        socket.close()
        server.stop()
        scope.cancel()
    }

    private fun readRtspResponse(reader: BufferedReader): String {
        val sb = StringBuilder()
        var contentLength = 0
        while (true) {
            val line = reader.readLine() ?: break
            sb.appendLine(line)
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        if (contentLength > 0) {
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(chars, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            sb.append(chars, 0, read)
        }
        return sb.toString()
    }
}
