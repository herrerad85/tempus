package com.eddyizm.tempus.cast

import android.content.Context
import androidx.media3.common.Player
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

class CastOutputTest {

    private val output = CastOutput(mock<Context>()) {}

    @Test
    fun beforeLoadSetsPausedWhenGivenFalse() {
        val player = mock<Player>()
        output.beforeLoad(player, false)
        verify(player).playWhenReady = false
        verifyNoMoreInteractions(player)
    }

    @Test
    fun beforeLoadSetsPlayingWhenGivenTrue() {
        val player = mock<Player>()
        output.beforeLoad(player, true)
        verify(player).playWhenReady = true
        verifyNoMoreInteractions(player)
    }
}
