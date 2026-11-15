package com.eddyizm.tempus.ui.devices

import android.content.Context
import androidx.compose.runtime.Composable

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
    private val sources: List<DeviceSource>,
    val close: () -> Unit,
) {
    fun stopAllExcept(keep: DeviceSource?) = sources.filter { it !== keep }.forEach { it.stop() }
}

internal fun deviceSources(context: Context): List<DeviceSource> = listOf(MediaRouteDevices(context))
