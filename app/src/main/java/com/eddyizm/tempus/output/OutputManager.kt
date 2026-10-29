package com.eddyizm.tempus.output

import androidx.media3.common.Player

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

    fun register(output: PlaybackOutput) {
        outputs += output
        output.start(this)
    }

    override fun onConnected(output: PlaybackOutput, player: Player) {
        val from = session.player
        if (shuttingDown || from === player) return
        val previous = active
        val playWhenReady = from.playWhenReady
        output.beforeLoad(player, playWhenReady)
        move(from, player, playWhenReady)
        active = output
        previous?.standDown(from)
    }

    override fun onDropped(output: PlaybackOutput, player: Player) {
        if (shuttingDown || player !== session.player) return
        val wasPlaying = player.playWhenReady
        move(player, phone, !output.pausesPhoneOnDrop && wasPlaying)
        active = null
    }

    fun release() {
        shuttingDown = true
        outputs.forEach { it.release() }
        outputs.clear()
    }

    private fun move(from: Player, to: Player, playWhenReady: Boolean) {
        val queue = (0 until from.mediaItemCount).map(from::getMediaItemAt)
        val index = from.currentMediaItemIndex
        val position = from.currentPosition
        from.stop()
        to.setMediaItems(queue, index, position)
        to.playWhenReady = playWhenReady
        to.prepare()
        session.player = to
        session.onPlayerChanged(from, to)
    }
}
