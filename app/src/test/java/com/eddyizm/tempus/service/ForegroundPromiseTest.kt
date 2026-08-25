package com.eddyizm.tempus.service

import android.content.Intent
import android.os.Build
import android.view.KeyEvent
import androidx.media3.common.Player
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.timeout
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class ForegroundPromiseTest {

    @Test
    fun aPlayKeyStartPromisesTheForegroundCall() {
        for (keyCode in intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK
        )) {
            assertTrue(
                "keyCode=$keyCode",
                startCommandPromisesForeground(
                    Intent.ACTION_MEDIA_BUTTON,
                    keyCode,
                    Build.VERSION_CODES.O
                )
            )
        }
    }

    /**
     * Next, previous and stop from media3's notification reach the service through a plain
     * startService that obliges nothing. Play pause is not in this list, media3's notification
     * sends keycode 85 for it whatever the player is doing, and BaseMediaService records why that
     * one is left alone.
     */
    @Test
    fun anyOtherTransportKeyDoesNot() {
        for (keyCode in intArrayOf(
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_UNKNOWN
        )) {
            assertFalse(
                "keyCode=$keyCode",
                startCommandPromisesForeground(
                    Intent.ACTION_MEDIA_BUTTON,
                    keyCode,
                    Build.VERSION_CODES.O
                )
            )
        }
    }

    /**
     * Below API 26 the start is a plain startService, which obliges nothing. A promise recorded
     * there would be discharged by the next restore that finds nothing to play, flashing a
     * notification and stopping a service the platform never asked anything of.
     */
    @Test
    fun aPlayKeyStartBelowApi26DoesNot() {
        assertFalse(
            startCommandPromisesForeground(
                Intent.ACTION_MEDIA_BUTTON,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                Build.VERSION_CODES.N_MR1
            )
        )
    }

    @Test
    fun anyOtherActionDoesNot() {
        assertFalse(
            startCommandPromisesForeground(
                BaseMediaService.ACTION_RELOAD_EQUALIZER,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                Build.VERSION_CODES.O
            )
        )
        assertFalse(
            startCommandPromisesForeground(null, KeyEvent.KEYCODE_MEDIA_PLAY, Build.VERSION_CODES.O)
        )
    }

    /**
     * Removing the task stops the service unless playback is running, and a stop with the promise
     * outstanding is the same violation as never keeping it, which is why the caller routes that
     * case through the discharge.
     */
    @Test
    fun removingTheTaskStopsEverythingButRunningPlayback() {
        assertFalse(stopsOnTaskRemoved(playWhenReady = true, playerHasContent = true))
        assertTrue(stopsOnTaskRemoved(playWhenReady = false, playerHasContent = true))
        assertTrue(stopsOnTaskRemoved(playWhenReady = true, playerHasContent = false))
        assertTrue(stopsOnTaskRemoved(playWhenReady = false, playerHasContent = false))
    }

    @Test
    fun aServiceThatPromisedAndHasNothingToPlayOwesTheCall() {
        assertTrue(
            owesForegroundStart(
                promised = true,
                serviceDestroyed = false,
                playerHasContent = false
            )
        )
    }

    @Test
    fun aServiceThatPromisedNothingOwesNothing() {
        assertFalse(
            owesForegroundStart(
                promised = false,
                serviceDestroyed = false,
                playerHasContent = false
            )
        )
    }

    /**
     * Stopping a service that is playing takes the media notification with it, and media3 goes
     * foreground for the playback itself.
     */
    @Test
    fun aServiceWithContentIsLeftAlone() {
        assertFalse(
            owesForegroundStart(
                promised = true,
                serviceDestroyed = false,
                playerHasContent = true
            )
        )
    }

    /**
     * A destroyed service cannot call startForeground, and the discharge that lands on it must not
     * try. The caller reports no content for a destroyed service, so both spellings are covered.
     */
    @Test
    fun aDestroyedServiceOwesNothing() {
        assertFalse(
            owesForegroundStart(
                promised = true,
                serviceDestroyed = true,
                playerHasContent = false
            )
        )
        assertFalse(
            owesForegroundStart(
                promised = true,
                serviceDestroyed = true,
                playerHasContent = true
            )
        )
    }

    /**
     * The cold start route into the discharge. media3 calls the other one, onPlaybackResumption,
     * and BaseSessionCallbackTest covers that.
     */
    @Test
    fun restoringFromAnEmptyQueueKeepsThePromise() {
        val service = mock<BaseMediaService>()
        val player = mock<Player>()

        whenever(player.mediaItemCount).thenReturn(0)
        whenever(service.loadStoredQueueOnce()).thenReturn(null)
        doCallRealMethod().`when`(service).restorePlayerFromQueue(any())

        service.restorePlayerFromQueue(player)

        verify(service, timeout(3_000)).keepForegroundPromise(anyString())
    }

    @Test
    fun restoringWithAPlayerThatAlreadyHasItemsDoesNothing() {
        val service = mock<BaseMediaService>()
        val player = mock<Player>()

        whenever(player.mediaItemCount).thenReturn(3)
        doCallRealMethod().`when`(service).restorePlayerFromQueue(any())

        service.restorePlayerFromQueue(player)

        verify(service, never()).keepForegroundPromise(anyString())
    }
}
