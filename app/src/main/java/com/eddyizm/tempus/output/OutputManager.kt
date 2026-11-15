package com.eddyizm.tempus.output

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

class OutputManager(
    private val phone: Player,
    private val session: Session,
) : PlaybackOutput.Listener {

    interface Session {
        var player: Player
        fun onPlayerChanged(oldPlayer: Player, newPlayer: Player)
    }

    private val outputs = mutableListOf<PlaybackOutput>()
    private var shuttingDown = false
    private var active: PlaybackOutput? = null
    private var resumeOnConnect = false

    private val phoneListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady) resumeOnConnect = false
        }
    }

    init {
        phone.addListener(phoneListener)
    }

    fun register(output: PlaybackOutput) {
        outputs += output
        output.start(this)
    }

    override fun onConnected(output: PlaybackOutput, player: Player) {
        val from = session.player
        if (shuttingDown || from === player) return
        val previous = active
        val playWhenReady = from.playWhenReady || resumeOnConnect
        resumeOnConnect = false
        output.beforeLoad(player, playWhenReady)
        move(from, player, playWhenReady, output.carriesRepeatMode || previous?.carriesRepeatMode == true)
        active = output
        previous?.standDown(from)
    }

    override fun onDropped(output: PlaybackOutput, player: Player, switching: Boolean) {
        if (shuttingDown || player !== session.player) return
        val wasPlaying = player.playWhenReady
        move(player, phone, !output.pausesPhoneOnDrop && wasPlaying, output.carriesRepeatMode)
        resumeOnConnect = switching && wasPlaying
        active = null
    }

    override fun onConnectFailed(output: PlaybackOutput) {
        resumeOnConnect = false
    }

    fun release() {
        shuttingDown = true
        phone.removeListener(phoneListener)
        outputs.forEach { it.release() }
        outputs.clear()
    }

    @OptIn(UnstableApi::class)
    private fun move(from: Player, to: Player, playWhenReady: Boolean, carryRepeatMode: Boolean) {
        val queue = (0 until from.mediaItemCount).map(from::getMediaItemAt)
        val index = from.currentMediaItemIndex
        val position = from.currentPosition
        from.stop()
        if (carryRepeatMode) {
            val repeatFrom = if (from.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE)) from else phone
            to.repeatMode = repeatFrom.repeatMode
        }
        to.setMediaItems(queue, index, position)
        to.playWhenReady = playWhenReady
        to.prepare()
        session.player = to
        session.onPlayerChanged(from, to)
    }
}
