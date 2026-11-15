package com.eddyizm.tempus.ui.devices

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouter

internal val MediaRouter.RouteInfo.playsOnThisDevice: Boolean
    get() = isDefault || deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH_A2DP ||
        (provider.packageName == "android" &&
            supportsControlCategory(MediaControlIntent.CATEGORY_LIVE_AUDIO) &&
            !supportsControlCategory(MediaControlIntent.CATEGORY_LIVE_VIDEO))

internal fun thisDevice(context: Context): ActiveDevice =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        mediaOutput(context.getSystemService(AudioManager::class.java).getAudioDevicesForAttributes(media))
    } else phoneDevice(false, null)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun mediaOutput(devices: List<AudioDeviceInfo>): ActiveDevice {
    val bluetoothTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID,
        AudioDeviceInfo.TYPE_BLE_HEARING_AID,
    )
    val bluetooth = devices.firstOrNull { it.type in bluetoothTypes }
    return phoneDevice(bluetooth != null, bluetooth?.productName?.toString())
}
