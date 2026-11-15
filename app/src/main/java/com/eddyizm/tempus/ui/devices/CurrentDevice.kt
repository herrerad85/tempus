package com.eddyizm.tempus.ui.devices

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

internal fun currentDevice(context: Context, sources: List<DeviceSource>): ActiveDevice =
    sources.firstNotNullOfOrNull { it.active() } ?: thisDevice(context)

internal fun watchCurrentDevice(context: Context, sources: List<DeviceSource>, onChange: (ActiveDevice) -> Unit): () -> Unit {
    var shown = currentDevice(context, sources)
    val check = {
        val now = currentDevice(context, sources)
        if (now != shown) {
            shown = now
            onChange(now)
        }
    }
    val stops = sources.map { it.watch(check) } + watchAudioOutputs(context, check)
    return { stops.forEach { it() } }
}

private fun watchAudioOutputs(context: Context, onChange: () -> Unit): () -> Unit {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return {}
    val audio = context.getSystemService(AudioManager::class.java)
    val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onChange()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onChange()
    }
    audio.registerAudioDeviceCallback(callback, null)
    return { audio.unregisterAudioDeviceCallback(callback) }
}
