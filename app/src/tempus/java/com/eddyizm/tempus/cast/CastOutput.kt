package com.eddyizm.tempus.cast

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.output.PlaybackOutput
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

@UnstableApi
class CastOutput(
    private val context: Context,
    private val initializePlayerListener: (Player) -> Unit,
) : PlaybackOutput, SessionAvailabilityListener {

    override val pausesPhoneOnDrop = false

    private var listener: PlaybackOutput.Listener? = null
    private var castContext: CastContext? = null
    private var castPlayer: CastPlayer? = null
    private var released = false

    @Suppress("DEPRECATION")
    override fun start(listener: PlaybackOutput.Listener) {
        this.listener = listener
        if (GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS
        ) return
        CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context))
            .addOnSuccessListener { castContext ->
                if (released) return@addOnSuccessListener
                this.castContext = castContext
                castContext.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
                val player = CastPlayer(castContext, CastMediaItemConverter())
                castPlayer = player
                player.setSessionAvailabilityListener(this)
                initializePlayerListener(player)
                if (player.isCastSessionAvailable) listener.onConnected(this, player)
            }
    }

    override fun beforeLoad(player: Player, playWhenReady: Boolean) {
        player.playWhenReady = playWhenReady
    }

    override fun release() {
        released = true
        castContext?.sessionManager?.removeSessionManagerListener(sessionListener, CastSession::class.java)
        castContext = null
        castPlayer?.let {
            it.setSessionAvailabilityListener(null)
            it.release()
        }
        castPlayer = null
    }

    override fun onCastSessionAvailable() {
        castPlayer?.let { listener?.onConnected(this, it) }
    }

    override fun onCastSessionUnavailable() {
        castPlayer?.let { listener?.onDropped(this, it, false) }
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            listener?.onConnectFailed(this@CastOutput)
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }
}
