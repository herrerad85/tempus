package com.eddyizm.tempus.ui.devices

import androidx.mediarouter.media.MediaRouter.RouteInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceKindTest {

    @Test
    fun upnpRouteIsASpeakerWhateverItsType() {
        assertEquals(DeviceKind.SPEAKER, routeKind(isUpnp = true, deviceType = RouteInfo.DEVICE_TYPE_TV))
    }

    @Test
    fun castRouteFollowsItsDeviceType() {
        assertEquals(DeviceKind.TV, routeKind(isUpnp = false, deviceType = RouteInfo.DEVICE_TYPE_TV))
        assertEquals(DeviceKind.SPEAKER, routeKind(isUpnp = false, deviceType = RouteInfo.DEVICE_TYPE_REMOTE_SPEAKER))
        assertEquals(DeviceKind.CAST, routeKind(isUpnp = false, deviceType = RouteInfo.DEVICE_TYPE_UNKNOWN))
    }

    @Test
    fun castGroupIsASpeakerGroup() {
        assertEquals(DeviceKind.SPEAKER_GROUP, routeKind(isUpnp = false, deviceType = RouteInfo.DEVICE_TYPE_GROUP))
    }

    @Test
    fun thisDeviceHasNoLabel() {
        assertEquals(ActiveDevice(DeviceKind.THIS_DEVICE, null), phoneDevice(isBluetooth = false, name = "Phone"))
    }

    @Test
    fun bluetoothIsLabeledWithItsNameWhenThereIsOne() {
        assertEquals(ActiveDevice(DeviceKind.BLUETOOTH, "WH-1000XM4"), phoneDevice(isBluetooth = true, name = "WH-1000XM4"))
        assertNull(phoneDevice(isBluetooth = true, name = " ").name)
        assertNull(phoneDevice(isBluetooth = true, name = null).name)
    }

    @Test
    fun tempusPlayerLosesOnlyTheTempusPrefix() {
        assertEquals("Kitchen", tempusPlayerName("Tempus Kitchen"))
        assertEquals("Kitchen Tempus", tempusPlayerName("Kitchen Tempus"))
    }

    @Test
    fun onlyPlayingElsewhereTakesTheAccent() {
        assertFalse(DeviceKind.THIS_DEVICE.playsElsewhere)
        assertFalse(DeviceKind.BLUETOOTH.playsElsewhere)
        listOf(DeviceKind.SPEAKER, DeviceKind.TV, DeviceKind.SPEAKER_GROUP, DeviceKind.CAST, DeviceKind.TEMPUS_PLAYER)
            .forEach { assertTrue(it.name, it.playsElsewhere) }
    }
}
