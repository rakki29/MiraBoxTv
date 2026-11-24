package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.example.miracast.DiscoveredP2pPeer
import com.example.miracast.NetworkEndpoints
import com.example.miracast.WifiDirectStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Coordinates Android Wi-Fi Direct (WifiP2pManager), WFD Sink advertisement,
 * NSD (_miracast._tcp) local Wi-Fi service discovery, and accurate local LAN IPv4 detection.
 */
class WifiDirectController(
    private val context: Context,
    private val onGroupClientJoined: (deviceName: String, address: String) -> Unit
) {

    private val _p2pStatus = MutableStateFlow(WifiDirectStatus())
    val p2pStatus: StateFlow<WifiDirectStatus> = _p2pStatus.asStateFlow()

    private val _endpoints = MutableStateFlow(NetworkEndpoints())
    val endpoints: StateFlow<NetworkEndpoints> = _endpoints.asStateFlow()

    private val wifiManager: WifiManager? =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val manager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var nsdListener: NsdManager.RegistrationListener? = null

    private val intentFilter = IntentFilter().apply {
        addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
    }

    private val p2pReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    val enabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _p2pStatus.update { it.copy(isP2pEnabled = enabled) }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val selfDevice: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                            WifiP2pDevice::class.java
                        )
                    } else {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    if (selfDevice != null) {
                        _p2pStatus.update {
                            it.copy(
                                p2pDeviceName = selfDevice.deviceName.orEmpty(),
                                wfdHardwareSupported = it.wfdHardwareSupported || isWfdCapable(selfDevice)
                            )
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (hasWifiDirectPermission()) {
                        channel?.let { ch ->
                            manager?.requestPeers(ch) { peerList ->
                                val mapped = peerList.deviceList.map { dev ->
                                    DiscoveredP2pPeer(
                                        deviceName = dev.deviceName.ifBlank { dev.deviceAddress },
                                        deviceAddress = dev.deviceAddress,
                                        isMiracastCapable = isWfdCapable(dev),
                                        statusLabel = peerStatusLabel(dev.status)
                                    )
                                }
                                _p2pStatus.update { it.copy(peers = mapped) }
                            }
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    if (hasWifiDirectPermission()) {
                        channel?.let { ch ->
                            manager?.requestConnectionInfo(ch) { info ->
                                val ownerIp = info.groupOwnerAddress?.hostAddress
                                _p2pStatus.update {
                                    it.copy(
                                        isGroupFormed = info.groupFormed,
                                        groupOwnerIp = ownerIp
                                    )
                                }
                            }
                            manager?.requestGroupInfo(ch) { group ->
                                if (group != null) {
                                    _p2pStatus.update {
                                        it.copy(
                                            isGroupFormed = true,
                                            groupName = group.networkName
                                        )
                                    }
                                    val firstClient = group.clientList.firstOrNull()
                                    if (firstClient != null) {
                                        onGroupClientJoined(
                                            firstClient.deviceName.ifBlank { firstClient.deviceAddress },
                                            firstClient.deviceAddress
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

    fun hasWifiDirectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscoveryAndAdvertising(
        receiverName: String,
        rtspPort: Int,
        uibcPort: Int,
        tcpVideoPort: Int = 19001
    ) {
        val permGranted = hasWifiDirectPermission()
        val (ip, isCgnatOrVpn) = resolveLocalWifiIp()
        val wifiRadioOn = runCatching { wifiManager?.isWifiEnabled ?: true }.getOrDefault(true)

        _endpoints.update {
            it.copy(
                wifiIpAddress = ip,
                isCgnatOrVpnIp = isCgnatOrVpn,
                rtspPort = rtspPort,
                uibcPort = uibcPort,
                tcpVideoPort = tcpVideoPort
            )
        }
        _p2pStatus.update {
            it.copy(
                hasPermission = permGranted,
                wifiEnabled = wifiRadioOn
            )
        }

        registerNsdService(receiverName, rtspPort, uibcPort, tcpVideoPort)

        if (!receiverRegistered) {
            runCatching {
                ContextCompat.registerReceiver(
                    context,
                    p2pReceiver,
                    intentFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            }
        }

        val p2pManager = manager
        if (p2pManager == null) {
            _p2pStatus.update { it.copy(statusNote = "Wi-Fi Direct hardware not present; LAN mode active") }
            return
        }

        if (channel == null) {
            channel = p2pManager.initialize(context, Looper.getMainLooper(), null)
        }

        if (!permGranted) {
            _p2pStatus.update { it.copy(statusNote = "Waiting for NEARBY_WIFI_DEVICES permission") }
            return
        }

        val ch = channel ?: return

        // Set the P2P broadcast name so nearby devices see MiraBox-TV instead of Android_xxxx
        trySetP2pDeviceName(p2pManager, ch, receiverName)

        // Attempt WFD Sink IE (will report clearly if OS denies CONFIGURE_WIFI_DISPLAY)
        tryEnableWfdSinkMode(p2pManager, ch, rtspPort)

        // Register Wi-Fi Direct DNS-SD service for Miracast Sink discovery
        runCatching {
            val txt = mapOf(
                "wfd_device_type" to "1",
                "wfd_rtsp_port" to rtspPort.toString(),
                "wfd_uibc_port" to uibcPort.toString(),
                "device_name" to receiverName
            )
            val serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(receiverName, "_miracast._tcp", txt)
            p2pManager.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    p2pManager.addLocalService(ch, serviceInfo, null)
                }

                override fun onFailure(reason: Int) {
                    p2pManager.addLocalService(ch, serviceInfo, null)
                }
            })
        }

        // Start passive P2P listening (API 33+) or createGroup without disrupting it with discoverPeers()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                p2pManager.startListening(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        _p2pStatus.update { it.copy(statusNote = "P2P Passive Listen Active") }
                    }

                    override fun onFailure(reason: Int) {
                        createP2pGroupFallback(p2pManager, ch)
                    }
                })
            }.onFailure {
                createP2pGroupFallback(p2pManager, ch)
            }
        } else {
            createP2pGroupFallback(p2pManager, ch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun createP2pGroupFallback(p2pManager: WifiP2pManager, ch: WifiP2pManager.Channel) {
        p2pManager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _p2pStatus.update {
                    it.copy(isGroupFormed = true, statusNote = "P2P Group Owner ready")
                }
            }

            override fun onFailure(reason: Int) {
                _p2pStatus.update {
                    it.copy(statusNote = "P2P standby (code $reason) • LAN Active")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        val ch = channel ?: return
        if (!hasWifiDirectPermission()) return
        manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _p2pStatus.update { it.copy(statusNote = "Scanning nearby Wi-Fi Direct phones") }
            }

            override fun onFailure(reason: Int) {
                _p2pStatus.update { it.copy(statusNote = "LAN Discovery active (P2P code $reason)") }
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToPeer(deviceAddress: String) {
        val ch = channel ?: return
        if (!hasWifiDirectPermission()) return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
        }
        manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _p2pStatus.update { it.copy(statusNote = "Invitation sent to $deviceAddress") }
            }

            override fun onFailure(reason: Int) {
                _p2pStatus.update { it.copy(statusNote = "Invite failed ($reason)") }
            }
        })
    }

    private fun registerNsdService(
        serviceName: String,
        rtspPort: Int,
        uibcPort: Int,
        tcpVideoPort: Int
    ) {
        val nsd = nsdManager ?: return
        unregisterNsdService()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_miracast._tcp."
            port = rtspPort
            setAttribute("uibc_port", uibcPort.toString())
            setAttribute("tcp_video_port", tcpVideoPort.toString())
            setAttribute("role", "sink")
            setAttribute("dex", "supported")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                _endpoints.update {
                    it.copy(nsdRegistered = true, nsdServiceName = nsdServiceInfo.serviceName)
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _endpoints.update { it.copy(nsdRegistered = false) }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                _endpoints.update { it.copy(nsdRegistered = false) }
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdListener = listener
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun unregisterNsdService() {
        val listener = nsdListener ?: return
        runCatching { nsdManager?.unregisterService(listener) }
        nsdListener = null
    }

    private fun trySetP2pDeviceName(
        p2pManager: WifiP2pManager,
        p2pChannel: WifiP2pManager.Channel,
        name: String
    ) {
        runCatching {
            val method = WifiP2pManager::class.java.getMethod(
                "setDeviceName",
                WifiP2pManager.Channel::class.java,
                String::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            method.invoke(
                p2pManager,
                p2pChannel,
                name.take(24),
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        _p2pStatus.update { it.copy(p2pDeviceName = name) }
                    }

                    override fun onFailure(reason: Int) = Unit
                }
            )
        }
    }

    /**
     * Attempts to configure hidden Android WifiP2pWfdInfo (PRIMARY_SINK) and records exact OS result.
     */
    private fun tryEnableWfdSinkMode(
        p2pManager: WifiP2pManager,
        p2pChannel: WifiP2pManager.Channel,
        rtspPort: Int
    ) {
        try {
            val wfdClass = Class.forName("android.net.wifi.p2p.WifiP2pWfdInfo")
            val wfdInfo = wfdClass.getDeclaredConstructor().newInstance()
            wfdClass.getMethod("setWfdEnabled", Boolean::class.javaPrimitiveType)
                .invoke(wfdInfo, true)
            wfdClass.getMethod("setDeviceType", Int::class.javaPrimitiveType)
                .invoke(wfdInfo, 1) // PRIMARY_SINK = 1
            wfdClass.getMethod("setSessionAvailable", Boolean::class.javaPrimitiveType)
                .invoke(wfdInfo, true)
            wfdClass.getMethod("setControlPort", Int::class.javaPrimitiveType)
                .invoke(wfdInfo, rtspPort)
            wfdClass.getMethod("setMaxThroughput", Int::class.javaPrimitiveType)
                .invoke(wfdInfo, 50)
            val setWfdMethod = WifiP2pManager::class.java.getMethod(
                "setWFDInfo",
                WifiP2pManager.Channel::class.java,
                wfdClass,
                WifiP2pManager.ActionListener::class.java
            )
            setWfdMethod.invoke(
                p2pManager,
                p2pChannel,
                wfdInfo,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        _p2pStatus.update {
                            it.copy(
                                wfdHardwareSupported = true,
                                wfdBlockedByOs = false,
                                wfdDiagnosticNote = "Native WFD Sink IE Active"
                            )
                        }
                    }

                    override fun onFailure(reason: Int) {
                        _p2pStatus.update {
                            it.copy(
                                wfdHardwareSupported = false,
                                wfdBlockedByOs = true,
                                wfdDiagnosticNote = "WFD rejected by ROM (code $reason)"
                            )
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            val root = t.cause ?: t
            val isSecurity = root is SecurityException ||
                root.message?.contains("CONFIGURE_WIFI_DISPLAY", ignoreCase = true) == true
            _p2pStatus.update {
                it.copy(
                    wfdHardwareSupported = false,
                    wfdBlockedByOs = true,
                    wfdDiagnosticNote = if (isSecurity) {
                        "CONFIGURE_WIFI_DISPLAY blocked by Android ROM"
                    } else {
                        "WFD unavailable (${root.javaClass.simpleName})"
                    }
                )
            }
        }
    }

    private fun isWfdCapable(device: WifiP2pDevice): Boolean {
        return runCatching {
            val field = WifiP2pDevice::class.java.getField("wfdInfo")
            field.get(device) != null
        }.getOrDefault(false)
    }

    private fun peerStatusLabel(status: Int): String = when (status) {
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }

    /**
     * Resolves the best local LAN IPv4 address (192.168.x.x / 10.x.x.x / 172.16-31.x.x)
     * avoiding VPN (tun*) and cellular/CGNAT (100.64.0.0/10) unless no LAN IP exists.
     */
    fun resolveLocalWifiIp(): Pair<String, Boolean> {
        val candidates = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { iface ->
                    val name = iface.name.lowercase()
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .map { addr -> name to (addr.hostAddress ?: "") }
                }
                .filter { (_, ip) -> ip.isNotBlank() }
        }.getOrDefault(emptyList())

        val nonTunnel = candidates.filterNot { (ifName, _) ->
            ifName.startsWith("tun") || ifName.startsWith("ppp") ||
                ifName.startsWith("rmnet") || ifName.startsWith("ccmni") ||
                ifName.startsWith("wg")
        }

        // 1. Prefer wlan* or eth* with standard RFC1918 private LAN IP
        val preferredLan = nonTunnel
            .sortedBy { (ifName, _) ->
                when {
                    ifName.startsWith("wlan") -> 0
                    ifName.startsWith("eth") -> 1
                    ifName.startsWith("ap") -> 2
                    else -> 3
                }
            }
            .firstOrNull { (_, ip) -> isRfc1918PrivateIp(ip) }
        if (preferredLan != null) {
            return preferredLan.second to false
        }

        // 2. Fallback to any non-tunnel IPv4 (flag if CGNAT 100.64.0.0/10)
        val fallback = nonTunnel.firstOrNull()?.second
            ?: candidates.firstOrNull()?.second
            ?: "127.0.0.1"
        return fallback to isCgnatIp(fallback)
    }

    private fun isRfc1918PrivateIp(ip: String): Boolean {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        val a = parts[0]
        val b = parts[1]
        return a == 192 && b == 168 ||
            a == 10 ||
            (a == 172 && b in 16..31)
    }

    private fun isCgnatIp(ip: String): Boolean {
        val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return false
        return parts[0] == 100 && parts[1] in 64..127
    }

    fun stop() {
        unregisterNsdService()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(p2pReceiver) }
            receiverRegistered = false
        }
        channel?.let { ch ->
            runCatching { manager?.removeGroup(ch, null) }
            runCatching { manager?.stopPeerDiscovery(ch, null) }
        }
    }
}
