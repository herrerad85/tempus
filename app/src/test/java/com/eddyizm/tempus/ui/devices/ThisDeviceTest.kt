package com.eddyizm.tempus.ui.devices

import android.media.AudioDeviceInfo
import androidx.mediarouter.media.MediaControlIntent
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
        isBluetooth: Boolean = false,
        isSystemRoute: Boolean = false,
        categories: Set<String> = setOf(MediaControlIntent.CATEGORY_LIVE_AUDIO),
    ): RouteInfo = mock {
        on { this.isDefault } doReturn isDefault
        on { this.isBluetooth } doReturn isBluetooth
        on { this.isSystemRoute } doReturn isSystemRoute
        on { supportsControlCategory(any()) } doReturn false
        categories.forEach { category -> on { supportsControlCategory(category) } doReturn true }
    }

    @Test
    fun defaultAndBluetoothRoutesPlayHere() {
        assertTrue(route(isDefault = true).playsOnThisDevice)
        assertTrue(route(isBluetooth = true).playsOnThisDevice)
    }

    @Test
    fun systemAudioRoutePlaysHereButSystemVideoRouteDoesNot() {
        assertTrue(route(isSystemRoute = true).playsOnThisDevice)
        assertFalse(route(isSystemRoute = true,
            categories = setOf(MediaControlIntent.CATEGORY_LIVE_AUDIO, MediaControlIntent.CATEGORY_LIVE_VIDEO)).playsOnThisDevice)
    }

    @Test
    fun castOrUpnpRoutePlaysElsewhere() {
        assertFalse(route().playsOnThisDevice)
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
