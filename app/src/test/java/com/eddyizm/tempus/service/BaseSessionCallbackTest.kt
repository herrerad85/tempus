package com.eddyizm.tempus.service

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.MediaSession.ControllerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify
import org.mockito.kotlin.times
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.never
import org.mockito.Mockito.mockConstruction

class BaseSessionCallbackTest {

    @Test
    fun updateMediaNotificationCustomLayout_doesNotCrashWhenControllerInfoIsNull() {
        val context = mock<Context>()
        val service = mock<BaseMediaService>()
        val session = mock<MediaSession>()
        val player = mock<Player>()
        val mediaMetadata = mock<MediaMetadata>()

        whenever(context.getString(anyInt())).thenReturn("mock_string")
        whenever(session.player).thenReturn(player)
        whenever(player.mediaMetadata).thenReturn(mediaMetadata)
        whenever(session.mediaNotificationControllerInfo).thenReturn(null)

        mockConstruction(SessionCommand::class.java).use {
            val callback = object : BaseSessionCallback(context, service) {
                fun triggerUpdate() {
                    updateMediaNotificationCustomLayout(session)
                }
            }
            callback.triggerUpdate()
        }
    }

    @Test
    fun onConnect_registersListenerOnlyOnce() {
        val context = mock<Context>()
        val service = mock<BaseMediaService>()
        val session = mock<MediaSession>()
        val controller = mock<ControllerInfo>()
        val player = mock<Player>()

        whenever(context.getString(anyInt())).thenReturn("mock_string")
        whenever(session.player).thenReturn(player)
        // Assume NOT a notification controller for simplicity in this test
        whenever(session.isMediaNotificationController(any())).thenReturn(false)
        whenever(session.isAutomotiveController(any())).thenReturn(false)
        whenever(session.isAutoCompanionController(any())).thenReturn(false)

        mockConstruction(SessionCommand::class.java).use {
            val callback = BaseSessionCallback(context, service)
            
            callback.onConnect(session, controller)
            callback.onConnect(session, controller)
            
            // Should be called only once because of currentSession check
            verify(player, times(1)).addListener(any())
        }
    }

    @Test
    fun handlePlayerChanged_beforeOnConnect_doesNotRegisterListener() {
        val context = mock<Context>()
        val service = mock<BaseMediaService>()
        val session = mock<MediaSession>()
        val controller = mock<ControllerInfo>()
        val player = mock<Player>()

        whenever(context.getString(anyInt())).thenReturn("mock_string")
        whenever(session.player).thenReturn(player)
        whenever(session.isMediaNotificationController(any())).thenReturn(false)
        whenever(session.isAutomotiveController(any())).thenReturn(false)
        whenever(session.isAutoCompanionController(any())).thenReturn(false)

        mockConstruction(SessionCommand::class.java).use {
            val callback = BaseSessionCallback(context, service)
            
            // 1. Player changes before any controller connects (currentSession is null)
            callback.handlePlayerChanged(null, player)
            
            // 2. Controller connects
            callback.onConnect(session, controller)
            
            // Should be registered ONLY ONCE (by onConnect)
            verify(player, times(1)).addListener(any())
        }
    }

    @Test
    fun onPlaybackResumption_failsWhenThereIsNothingToResume() {
        val context = mock<Context>()
        val service = mock<BaseMediaService>()
        val session = mock<MediaSession>()
        val controller = mock<ControllerInfo>()

        whenever(context.getString(anyInt())).thenReturn("mock_string")
        whenever(service.loadStoredQueueOnce()).thenReturn(null)

        mockConstruction(SessionCommand::class.java).use {
            val callback = BaseSessionCallback(context, service)

            val future = callback.onPlaybackResumption(session, controller, true)

            val thrown = try {
                future.get(5, TimeUnit.SECONDS)
                null
            } catch (e: ExecutionException) {
                e.cause
            }

            assertTrue(
                "expected the future to fail, got ${thrown?.javaClass?.simpleName ?: "a result"}",
                thrown is IllegalStateException
            )

            // The service was started on a promise of a startForeground call and there is nothing
            // to play, so this path owes the platform one. Without it the process is killed with
            // ForegroundServiceDidNotStartInTimeException.
            verify(service).keepForegroundPromise(anyString())
        }
    }

    @Test
    fun onPlaybackResumption_completesTheFutureWhenTheQueueReadThrows() {
        val context = mock<Context>()
        val service = mock<BaseMediaService>()
        val session = mock<MediaSession>()
        val controller = mock<ControllerInfo>()

        whenever(context.getString(anyInt())).thenReturn("mock_string")
        whenever(service.loadStoredQueueOnce()).thenThrow(RuntimeException("database is gone"))

        mockConstruction(SessionCommand::class.java).use {
            val callback = BaseSessionCallback(context, service)

            val future = callback.onPlaybackResumption(session, controller, true)

            val thrown = try {
                future.get(5, TimeUnit.SECONDS)
                null
            } catch (e: ExecutionException) {
                e.cause
            }

            assertTrue(
                "expected the future to fail, got ${thrown?.javaClass?.simpleName ?: "a result"}",
                thrown is IllegalStateException
            )

            verify(service).keepForegroundPromise(anyString())
        }
    }

    @Test
    fun onPlaybackResumption_returnsTheStoredQueue() {
        val context = mock<Context>()
        val service = mock<BaseMediaService>()
        val session = mock<MediaSession>()
        val controller = mock<ControllerInfo>()

        val stored = MediaSession.MediaItemsWithStartPosition(
            listOf(MediaItem.Builder().setMediaId("song-1").build()),
            0,
            42_000L
        )

        whenever(context.getString(anyInt())).thenReturn("mock_string")
        whenever(service.loadStoredQueueOnce()).thenReturn(stored)

        mockConstruction(SessionCommand::class.java).use {
            val callback = BaseSessionCallback(context, service)

            val result = callback.onPlaybackResumption(session, controller, true)
                .get(5, TimeUnit.SECONDS)

            assertEquals(1, result.mediaItems.size)
            assertEquals("song-1", result.mediaItems[0].mediaId)
            assertEquals(0, result.startIndex)
            assertEquals(42_000L, result.startPositionMs)

            // The content guard runs later, on the main thread, so whether it would see the items
            // media3 is about to set is a race. Only not calling it on this path is safe.
            verify(service, never()).keepForegroundPromise(anyString())
        }
    }

    @Test
    fun handlePlayerChanged_afterOnConnect_movesListener() {
        val context = mock<Context>()
        val service = mock<BaseMediaService>()
        val session = mock<MediaSession>()
        val controller = mock<ControllerInfo>()
        val oldPlayer = mock<Player>()
        val newPlayer = mock<Player>()

        whenever(context.getString(anyInt())).thenReturn("mock_string")
        whenever(session.player).thenReturn(oldPlayer)
        whenever(session.isMediaNotificationController(any())).thenReturn(false)
        whenever(session.isAutomotiveController(any())).thenReturn(false)
        whenever(session.isAutoCompanionController(any())).thenReturn(false)

        mockConstruction(SessionCommand::class.java).use {
            val callback = BaseSessionCallback(context, service)
            
            // 1. Initial connection
            callback.onConnect(session, controller)
            verify(oldPlayer, times(1)).addListener(any())
            
            // 2. Player changes (e.g. switch to Cast)
            callback.handlePlayerChanged(oldPlayer, newPlayer)
            
            // Should move the listener
            verify(oldPlayer).removeListener(any())
            verify(newPlayer).addListener(any())
        }
    }
}
