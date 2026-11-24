package com.example.sender

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.example.miracast.DiscoveredReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

/**
 * Discovers MiraBox TV Receivers on the same Wi-Fi LAN using Android NsdManager (_miracast._tcp.).
 */
class NsdReceiverBrowser(context: Context) {

    private val nsdManager: NsdManager? =
        context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _receivers = MutableStateFlow<List<DiscoveredReceiver>>(emptyList())
    val receivers: StateFlow<List<DiscoveredReceiver>> = _receivers.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolveExecutor = Executors.newSingleThreadExecutor()

    fun startDiscovery() {
        stopDiscovery()
        val nsd = nsdManager ?: return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.contains("_miracast")) {
                    resolveService(nsd, serviceInfo)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _receivers.update { list ->
                    list.filterNot { it.serviceName == serviceInfo.serviceName }
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching {
            nsd.discoverServices("_miracast._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    private fun resolveService(nsd: NsdManager, serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                nsd.registerServiceInfoCallback(
                    serviceInfo,
                    resolveExecutor,
                    object : NsdManager.ServiceInfoCallback {
                        override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                        override fun onServiceUpdated(info: NsdServiceInfo) {
                            val host = info.hostAddresses.firstOrNull()?.hostAddress ?: return
                            addOrUpdate(info, host)
                        }

                        override fun onServiceLost() = Unit

                        override fun onServiceInfoCallbackUnregistered() = Unit
                    }
                )
            }
        } else {
            @Suppress("DEPRECATION")
            nsd.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val host = info.host?.hostAddress ?: return
                        addOrUpdate(info, host)
                    }
                }
            )
        }
    }

    private fun addOrUpdate(info: NsdServiceInfo, host: String) {
        val uibcPort = info.attributes["uibc_port"]
            ?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: 7237
        val tcpVideoPort = info.attributes["tcp_video_port"]
            ?.let { String(it, Charsets.UTF_8).toIntOrNull() } ?: 19001
        val item = DiscoveredReceiver(
            serviceName = info.serviceName,
            hostAddress = host,
            rtspPort = info.port,
            uibcPort = uibcPort,
            tcpVideoPort = tcpVideoPort
        )
        _receivers.update { list ->
            (list.filterNot { it.hostAddress == item.hostAddress && it.rtspPort == item.rtspPort } + item)
        }
    }

    fun stopDiscovery() {
        val listener = discoveryListener ?: return
        runCatching { nsdManager?.stopServiceDiscovery(listener) }
        discoveryListener = null
    }
}
