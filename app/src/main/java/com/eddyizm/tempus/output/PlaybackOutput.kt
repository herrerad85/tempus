package com.eddyizm.tempus.output

import androidx.media3.common.Player

interface PlaybackOutput {

    val pausesPhoneOnDrop: Boolean

    fun start(listener: Listener)

    fun beforeLoad(player: Player, playWhenReady: Boolean) {}

    /**
     * Notice that the session has moved off [player], a player this output built, onto the
     * player of a later connect. Not sent on shutdown, which [release] covers.
     */
    fun standDown(player: Player) {}

    fun release()

    interface Listener {
        fun onConnected(output: PlaybackOutput, player: Player)
        fun onDropped(output: PlaybackOutput, player: Player, switching: Boolean)
        fun onConnectFailed(output: PlaybackOutput)
    }
}
