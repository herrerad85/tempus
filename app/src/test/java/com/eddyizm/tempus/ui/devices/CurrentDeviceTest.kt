package com.eddyizm.tempus.ui.devices

import android.content.Context
import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class CurrentDeviceTest {

    private class FakeSource(var device: ActiveDevice?) : DeviceSource {
        var onChange: (() -> Unit)? = null
        override fun isOffered() = true
        override fun active() = device
        override fun watch(onChange: () -> Unit): () -> Unit {
            this.onChange = onChange
            return { this.onChange = null }
        }
        override fun stop() = Unit
        @Composable override fun Section(sheet: DevicesSheetScope) = Unit
    }

    private val context = mock<Context>()
    private val tv = ActiveDevice(DeviceKind.TV, "Bravia")
    private val kitchen = ActiveDevice(DeviceKind.TEMPUS_PLAYER, "Kitchen")

    @Test
    fun firstSourcePlayingWins() {
        assertEquals(tv, currentDevice(context, listOf(FakeSource(tv), FakeSource(kitchen))))
        assertEquals(kitchen, currentDevice(context, listOf(FakeSource(null), FakeSource(kitchen))))
    }

    @Test
    fun reportsOnlyAChangeOfDevice() {
        val network = FakeSource(tv)
        val seen = mutableListOf<ActiveDevice>()
        watchCurrentDevice(context, listOf(network, FakeSource(kitchen))) { seen += it }

        network.onChange!!()
        network.device = ActiveDevice(DeviceKind.SPEAKER, "Den")
        network.onChange!!()
        network.onChange!!()

        assertEquals(listOf(ActiveDevice(DeviceKind.SPEAKER, "Den")), seen)
    }

    @Test
    fun stoppingEndsEveryWatch() {
        val sources = listOf(FakeSource(tv), FakeSource(kitchen))
        val stop = watchCurrentDevice(context, sources) { }
        stop()
        sources.forEach { assertEquals(null, it.onChange) }
    }
}
