package com.eddyizm.tempus.output

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class OutputManagerTest {

    private class FakeSession(override var player: Player) : OutputManager.Session {
        val changes = mutableListOf<Pair<Player, Player>>()

        override fun onPlayerChanged(oldPlayer: Player, newPlayer: Player) {
            changes += oldPlayer to newPlayer
        }
    }

    private class FakeOutput(
        override val pausesPhoneOnDrop: Boolean,
        private val events: MutableList<String> = mutableListOf(),
    ) : PlaybackOutput {
        lateinit var listener: PlaybackOutput.Listener
        val loads = mutableListOf<Pair<Player, Boolean>>()
        val stoodDown = mutableListOf<Player>()
        var releases = 0

        override fun start(listener: PlaybackOutput.Listener) {
            this.listener = listener
        }

        override fun beforeLoad(player: Player, playWhenReady: Boolean) {
            events += "beforeLoad"
            loads += player to playWhenReady
        }

        override fun standDown(player: Player) {
            stoodDown += player
            player.release()
        }

        override fun release() {
            releases++
        }

        fun connect(player: Player) = listener.onConnected(this, player)

        fun drop(player: Player) = listener.onDropped(this, player)
    }

    private val first = mock<MediaItem>()
    private val second = mock<MediaItem>()

    private fun queuedPlayer(playing: Boolean): Player {
        var stopped = false
        val player = mock<Player>()
        whenever(player.mediaItemCount).thenAnswer { if (stopped) 0 else 2 }
        whenever(player.getMediaItemAt(0)).thenReturn(first)
        whenever(player.getMediaItemAt(1)).thenReturn(second)
        whenever(player.currentMediaItemIndex).thenAnswer { if (stopped) 0 else 1 }
        whenever(player.currentPosition).thenAnswer { if (stopped) 0L else 5_000L }
        whenever(player.playWhenReady).thenAnswer { !stopped && playing }
        doAnswer { stopped = true }.whenever(player).stop()
        return player
    }

    @Test
    fun connectFromThePhoneMovesQueuePositionAndPlayState() {
        val phone = queuedPlayer(playing = true)
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val upnp = FakeOutput(pausesPhoneOnDrop = true).also(manager::register)
        val renderer = mock<Player>()

        upnp.connect(renderer)

        inOrder(phone, renderer) {
            verify(phone).stop()
            verify(renderer).setMediaItems(listOf(first, second), 1, 5_000L)
            verify(renderer).playWhenReady = true
            verify(renderer).prepare()
        }
        assertSame(renderer, session.player)
        assertEquals(listOf(phone to renderer), session.changes)
    }

    @Test
    fun beforeLoadRunsBeforeTheQueueIsLoaded() {
        val events = mutableListOf<String>()
        val phone = queuedPlayer(playing = true)
        val manager = OutputManager(phone, FakeSession(phone))
        val cast = FakeOutput(pausesPhoneOnDrop = false, events).also(manager::register)
        val receiver = mock<Player>()
        doAnswer { events += "setMediaItems" }.whenever(receiver)
            .setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>())

        cast.connect(receiver)

        assertEquals(listOf("beforeLoad", "setMediaItems"), events)
        assertEquals(listOf(receiver to true), cast.loads)
    }

    @Test
    fun dropOfAPausingOutputBringsPlaybackHomePaused() {
        val phone = mock<Player>()
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val upnp = FakeOutput(pausesPhoneOnDrop = true).also(manager::register)
        val renderer = queuedPlayer(playing = true)
        upnp.connect(renderer)

        upnp.drop(renderer)

        verify(phone).setMediaItems(listOf(first, second), 1, 5_000L)
        verify(phone).playWhenReady = false
        verify(phone, never()).playWhenReady = true
        assertSame(phone, session.player)
        assertEquals(renderer to phone, session.changes.last())
        upnp.connect(mock())
        assertEquals(emptyList<Player>(), upnp.stoodDown)
    }

    @Test
    fun dropOfACarryingOutputKeepsThePlayState() {
        val phone = queuedPlayer(playing = false)
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val cast = FakeOutput(pausesPhoneOnDrop = false).also(manager::register)
        val receiver = queuedPlayer(playing = true)
        cast.connect(receiver)

        cast.drop(receiver)

        verify(phone).setMediaItems(listOf(first, second), 1, 5_000L)
        verify(phone).playWhenReady = true
        verify(receiver, never()).release()
        assertSame(phone, session.player)
    }

    @Test
    fun dropOfAPausedCarryingOutputLeavesThePhonePaused() {
        val phone = queuedPlayer(playing = true)
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val cast = FakeOutput(pausesPhoneOnDrop = false).also(manager::register)
        val receiver = queuedPlayer(playing = false)
        cast.connect(receiver)

        cast.drop(receiver)

        verify(phone).setMediaItems(listOf(first, second), 1, 5_000L)
        verify(phone).playWhenReady = false
        verify(phone, never()).playWhenReady = true
        assertSame(phone, session.player)
    }

    @Test
    fun castDropAfterUpnpTookOverIsIgnored() {
        val phone = queuedPlayer(playing = true)
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val cast = FakeOutput(pausesPhoneOnDrop = false).also(manager::register)
        val upnp = FakeOutput(pausesPhoneOnDrop = true).also(manager::register)
        val receiver = queuedPlayer(playing = true)
        val renderer = mock<Player>()
        cast.connect(receiver)
        upnp.connect(renderer)

        cast.drop(receiver)

        assertSame(renderer, session.player)
        assertEquals(listOf(phone to receiver, receiver to renderer), session.changes)
        assertEquals(listOf(receiver), cast.stoodDown)
        assertEquals(emptyList<Player>(), upnp.stoodDown)
        verify(phone, never()).setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>())
    }

    @Test
    fun switchingRenderersKeepsTheSessionOnTheNewOne() {
        val phone = queuedPlayer(playing = true)
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val upnp = FakeOutput(pausesPhoneOnDrop = true).also(manager::register)
        val rendererA = queuedPlayer(playing = true)
        val rendererB = mock<Player>()
        upnp.connect(rendererA)
        upnp.connect(rendererB)

        upnp.drop(rendererA)

        assertSame(rendererB, session.player)
        assertEquals(listOf(rendererA), upnp.stoodDown)
        inOrder(rendererA) {
            verify(rendererA).stop()
            verify(rendererA).release()
        }
        verify(phone, never()).setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>())
    }

    @Test
    fun connectOfThePlayerHoldingTheSessionDoesNothing() {
        val phone = queuedPlayer(playing = true)
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val cast = FakeOutput(pausesPhoneOnDrop = false).also(manager::register)
        val receiver = mock<Player>()
        cast.connect(receiver)

        cast.connect(receiver)

        verify(receiver, times(1)).setMediaItems(any<List<MediaItem>>(), any<Int>(), any<Long>())
        verify(receiver, never()).stop()
        assertEquals(1, session.changes.size)
        assertEquals(1, cast.loads.size)
    }

    @Test
    fun nothingMovesAfterRelease() {
        val phone = queuedPlayer(playing = true)
        val session = FakeSession(phone)
        val manager = OutputManager(phone, session)
        val upnp = FakeOutput(pausesPhoneOnDrop = true).also(manager::register)
        val cast = FakeOutput(pausesPhoneOnDrop = false).also(manager::register)
        val renderer = queuedPlayer(playing = true)
        upnp.connect(renderer)

        manager.release()
        upnp.drop(renderer)
        cast.connect(mock())

        assertSame(renderer, session.player)
        assertEquals(1, session.changes.size)
        assertEquals(1, upnp.releases)
        assertEquals(1, cast.releases)
    }
}
