package com.eddyizm.tempus.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

internal object LanDiscovery {
    fun discover(context: Context, onChange: (List<NsdServiceInfo>) -> Unit, onFailed: () -> Unit): () -> Unit {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val main = Handler(Looper.getMainLooper())
        val found = linkedMapOf<String, NsdServiceInfo>()
        var closed = false
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if (!closed) onFailed() } }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { if (!closed) { found[info.serviceName] = info; onChange(found.values.toList()) } }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (!closed) { found.remove(info.serviceName); onChange(found.values.toList()) } }
            }
        }
        val started = runCatching { nsd.discoverServices(LanWire.TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isSuccess
        if (!started) onFailed()
        return {
            closed = true
            if (started) runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    @Suppress("DEPRECATION")
    fun resolve(context: Context, info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit, onFailed: () -> Unit) {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val main = Handler(Looper.getMainLooper())
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, code: Int) { main.post(onFailed) }
                override fun onServiceResolved(service: NsdServiceInfo) { main.post { onResolved(service) } }
            })
        } catch (e: Exception) {
            LanDiagnostics.record("Controller could not resolve device", e)
            onFailed()
        }
    }
}
