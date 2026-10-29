package com.eddyizm.tempus.upnp

import android.content.Context
import android.content.SharedPreferences
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouter
import com.eddyizm.tempus.App
import com.eddyizm.tempus.output.PlaybackOutput
import com.eddyizm.tempus.util.Preferences

@UnstableApi
class UpnpOutput(
    private val context: Context,
    private val initializePlayerListener: (Player) -> Unit,
) : PlaybackOutput {

    override val pausesPhoneOnDrop = true

    private var listener: PlaybackOutput.Listener? = null
    private var routeProvider: UpnpRouteProvider? = null
    private var upnpPlayer: UpnpPlayer? = null

    private val controlPoint by lazy { UpnpControlPoint() }

    private val switchListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Preferences.UPNP_ENABLED) applySwitch()
    }

    override fun start(listener: PlaybackOutput.Listener) {
        this.listener = listener
        applySwitch()
        App.getInstance().preferences.registerOnSharedPreferenceChangeListener(switchListener)
    }

    override fun standDown(player: Player) {
        if (player === upnpPlayer) upnpPlayer = null
        player.release()
    }

    override fun release() {
        App.getInstance().preferences.unregisterOnSharedPreferenceChangeListener(switchListener)
        releaseRoutes()
    }

    private fun applySwitch() {
        when (UpnpRoutesGate.change(Preferences.isUpnpEnabled(), routeProvider != null)) {
            UpnpRoutesGate.Change.REGISTER -> registerRoutes()
            UpnpRoutesGate.Change.RELEASE -> {
                upnpPlayer?.let { listener?.onDropped(this, it) }
                releaseRoutes()
            }
            UpnpRoutesGate.Change.NONE -> Unit
        }
    }

    private fun registerRoutes() {
        val provider = UpnpRouteProvider(context, controlPoint)
        provider.selectionListener = object : UpnpRouteProvider.SelectionListener {
            override fun onRendererSelected(device: UpnpDevice) {
                val player = UpnpPlayer(controlPoint, device, context.mainLooper, context = context)
                upnpPlayer = player
                initializePlayerListener(player)
                listener?.onConnected(this@UpnpOutput, player)
            }

            override fun onRendererUnselected(device: UpnpDevice) {
                val player = upnpPlayer
                if (player == null || player.device != device) return
                upnpPlayer = null
                listener?.onDropped(this@UpnpOutput, player)
                player.release()
            }
        }
        MediaRouter.getInstance(context).addProvider(provider)
        routeProvider = provider
    }

    private fun releaseRoutes() {
        routeProvider?.let { provider ->
            provider.selectionListener = null
            MediaRouter.getInstance(context).removeProvider(provider)
            provider.release()
        }
        routeProvider = null
        upnpPlayer?.release()
        upnpPlayer = null
    }
}
