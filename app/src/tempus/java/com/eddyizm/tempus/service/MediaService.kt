package com.eddyizm.tempus.service

import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.cast.CastOutput
import com.eddyizm.tempus.output.PlaybackOutput
import com.eddyizm.tempus.repository.AutomotiveRepository

@UnstableApi
class MediaService : BaseMediaService() {
    private val automotiveRepository = AutomotiveRepository()

    override fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        if (sessionCallback == null) {
            sessionCallback = MediaLibrarySessionCallback(baseContext, this, automotiveRepository)
        }
        return sessionCallback!!
    }

    override fun buildOutputs(): List<PlaybackOutput> =
        super.buildOutputs() + CastOutput(this, ::initializePlayerListener)

    override fun releasePlayers() {
        automotiveRepository.deleteMetadata()
        super.releasePlayers()
    }
}