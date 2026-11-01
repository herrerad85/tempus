package com.eddyizm.tempus.ui.devices

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity

/**
 * One kind of device the Devices sheet lists and the Devices button can show, such as the
 * speakers and TVs MediaRouter knows or the Tempus players on the network.
 */
internal interface DeviceSource {
    fun isOffered(): Boolean

    fun active(): ActiveDevice?

    /**
     * A subscription to changes of [active] that starts no search, ended by calling the returned function.
     */
    fun watch(onChange: () -> Unit): () -> Unit

    fun stop()

    @Composable
    fun Section(sheet: DevicesSheetScope)
}

internal class DevicesSheetScope(
    val activity: FragmentActivity,
    private val sources: List<DeviceSource>,
    val close: () -> Unit,
    val closeNow: () -> Unit,
    val isClosed: () -> Boolean,
) {
    fun stopAllExcept(keep: DeviceSource?) = sources.filter { it !== keep }.forEach { it.stop() }
}

internal fun deviceSources(context: Context): List<DeviceSource> = listOf(MediaRouteDevices(context), TempusPlayers)
