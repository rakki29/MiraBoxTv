package com.example.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
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
 * NSD (_miracast._tcp) local Wi-Fi service discovery, and local IPv4 detection.
 */
class WifiDirectController(
    private val context: Context,
    private val onGroupClientJoined: (deviceName: String, address: String) -> Unit
) {

    private val _p2pStatus = MutableStateFlow(WifiDirectStatus())
    val p2pStatus: StateFlow<WifiDirectStatus> = _p2pStatus.asStateFlow()

    private val _endpoints = MutableStateFlow(NetworkEndpoints())
    val endpoints: StateFlow<NetworkEndpoints> = _endpoints.asStateFlow()

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
    fun startDiscoveryAndAdvertising(receiverName: String, rtspPort: Int, uibcPort: Int) {
        val permGranted = hasWifiDirectPermission()
        val ip = resolveLocalWifiIp()
        _endpoints.update {
            it.copy(
                wifiIpAddress = ip,
                rtspPort = rtspPort,
                uibcPort = uibcPort
            )
        }
        _p2pStatus.update { it.copy(hasPermission = permGranted) }

        registerNsdService(receiverName, rtspPort, uibcPort)

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
        val wfdSupported = tryEnableWfdSinkMode(p2pManager, ch, rtspPort)
        _p2pStatus.update { it.copy(wfdHardwareSupported = wfdSupported) }

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

        // Create P2P group so TV Box acts as Miracast Group Owner, and start peer discovery
        p2pManager.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                _p2pStatus.update {
                    it.copy(isGroupFormed = true, statusNote = "P2P Group Owner ready")
                }
            }

            override fun onFailure(reason: Int) {
                // Group may already exist or hardware busy; proceed with peer discovery
                discoverPeers()
            }
        })
        discoverPeers()
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

    private fun registerNsdService(serviceName: String, rtspPort: Int, uibcPort: Int) {
        val nsd = nsdManager ?: return
        unregisterNsdService()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = "_miracast._tcp."
            port = rtspPort
            setAttribute("uibc_port", uibcPort.toString())
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

    /**
     * Attempts to configure hidden Android WifiP2pWfdInfo (PRIMARY_SINK) if the platform permits it.
     */
    private fun tryEnableWfdSinkMode(
        p2pManager: WifiP2pManager,
        p2pChannel: WifiP2pManager.Channel,
        rtspPort: Int
    ): Boolean {
        return runCatching {
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
            setWfdMethod.invoke(p2pManager, p2pChannel, wfdInfo, null)
            true
        }.getOrDefault(false)
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

    fun resolveLocalWifiIp(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNetwork)
        val hasLocalNet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        if (hasLocalNet) {
            val linkProps = cm?.getLinkProperties(activeNetwork)
            val linkAddr = linkProps?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            if (linkAddr != null) return linkAddr.hostAddress ?: "127.0.0.1"
        }
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: "127.0.0.1"
        }.getOrDefault("127.0.0.1")
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
