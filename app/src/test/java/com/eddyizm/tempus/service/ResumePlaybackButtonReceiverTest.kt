package com.eddyizm.tempus.service

import android.content.Context
import android.content.Intent
import com.eddyizm.tempus.repository.QueueRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ResumePlaybackButtonReceiverTest {

    private fun shouldStartWithQueueCount(count: Int): Boolean {
        mockConstruction(QueueRepository::class.java) { repository, _ ->
            whenever(repository.count()).thenReturn(count)
        }.use {
            return ResumePlaybackButtonReceiver()
                .shouldStartForegroundService(mock<Context>(), mock<Intent>())
        }
    }

    @Test
    fun doesNotStartTheServiceWhenNothingIsSaved() {
        assertFalse(shouldStartWithQueueCount(0))
    }

    @Test
    fun doesNotStartTheServiceWhenTheCountCouldNotBeRead() {
        assertFalse(shouldStartWithQueueCount(-1))
    }

    @Test
    fun startsTheServiceWhenAQueueIsSaved() {
        assertTrue(shouldStartWithQueueCount(13))
    }
}
