package com.eddyizm.tempus.ui.devices

import androidx.annotation.DrawableRes
import androidx.mediarouter.media.MediaRouter
import com.eddyizm.tempus.R

internal enum class DeviceKind(@get:DrawableRes val icon: Int, val playsElsewhere: Boolean) {
    THIS_DEVICE(R.drawable.ic_devices, false),
    BLUETOOTH(R.drawable.ic_bluetooth, false),
    SPEAKER(R.drawable.ic_speaker, true),
    TV(R.drawable.ic_tv, true),
    SPEAKER_GROUP(R.drawable.ic_speaker_group, true),
    CAST(R.drawable.ic_cast, true),
    TEMPUS_PLAYER(R.drawable.ic_lan_remote, true),
}

internal data class ActiveDevice(val kind: DeviceKind, val name: String?)

internal fun routeKind(isUpnp: Boolean, deviceType: Int): DeviceKind = when {
    isUpnp -> DeviceKind.SPEAKER
    deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> DeviceKind.SPEAKER_GROUP
    deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_TV -> DeviceKind.TV
    deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER -> DeviceKind.SPEAKER
    else -> DeviceKind.CAST
}

internal fun phoneDevice(isBluetooth: Boolean, name: String?): ActiveDevice =
    if (isBluetooth) ActiveDevice(DeviceKind.BLUETOOTH, name?.takeIf { it.isNotBlank() })
    else ActiveDevice(DeviceKind.THIS_DEVICE, null)

internal fun tempusPlayerName(serviceName: String): String = serviceName.removePrefix("Tempus ")
