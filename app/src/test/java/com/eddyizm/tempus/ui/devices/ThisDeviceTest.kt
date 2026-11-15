package com.eddyizm.tempus.ui.devices

import android.media.AudioDeviceInfo
import androidx.mediarouter.media.MediaControlIntent
import androidx.mediarouter.media.MediaRouter
import androidx.mediarouter.media.MediaRouter.RouteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ThisDeviceTest {

    private fun route(
        isDefault: Boolean = false,
        deviceType: Int = RouteInfo.DEVICE_TYPE_UNKNOWN,
        packageName: String = "com.google.android.gms",
        categories: Set<String> = setOf(MediaControlIntent.CATEGORY_LIVE_AUDIO),
    ): RouteInfo {
        val provider = mock<MediaRouter.ProviderInfo> { on { this.packageName } doReturn packageName }
        return mock {
            on { this.isDefault } doReturn isDefault
            on { this.deviceType } doReturn deviceType
            on { this.provider } doReturn provider
            on { supportsControlCategory(any()) } doReturn false
            categories.forEach { category -> on { supportsControlCategory(category) } doReturn true }
        }
    }

    @Test
    fun defaultAndBluetoothRoutesPlayHere() {
        assertTrue(route(isDefault = true).playsOnThisDevice)
        assertTrue(route(deviceType = RouteInfo.DEVICE_TYPE_BLUETOOTH_A2DP).playsOnThisDevice)
    }

    @Test
    fun systemAudioRoutePlaysHereButSystemVideoRouteDoesNot() {
        assertTrue(route(packageName = "android").playsOnThisDevice)
        assertFalse(route(packageName = "android",
            categories = setOf(MediaControlIntent.CATEGORY_LIVE_AUDIO, MediaControlIntent.CATEGORY_LIVE_VIDEO)).playsOnThisDevice)
    }

    @Test
    fun castOrUpnpRoutePlaysElsewhere() {
        assertFalse(route(deviceType = RouteInfo.DEVICE_TYPE_TV).playsOnThisDevice)
    }

    private fun output(type: Int, name: String = "Output"): AudioDeviceInfo = mock {
        on { this.type } doReturn type
        on { productName } doReturn name
    }

    @Test
    fun bluetoothOutputGivesItsName() {
        val outputs = listOf(output(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Buds"))
        assertEquals(ActiveDevice(DeviceKind.BLUETOOTH, "Buds"), mediaOutput(outputs))
        listOf(
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEARING_AID,
        ).forEach { assertEquals(DeviceKind.BLUETOOTH, mediaOutput(listOf(output(it))).kind) }
    }

    @Test
    fun speakerOutputIsThisDevice() {
        assertEquals(ActiveDevice(DeviceKind.THIS_DEVICE, null), mediaOutput(listOf(output(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))))
        assertEquals(ActiveDevice(DeviceKind.THIS_DEVICE, null), mediaOutput(emptyList()))
    }
}
