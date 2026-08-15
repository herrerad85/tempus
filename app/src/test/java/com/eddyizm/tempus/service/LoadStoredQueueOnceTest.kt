package com.eddyizm.tempus.service

import androidx.media3.common.MediaItem
import androidx.media3.session.MediaSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LoadStoredQueueOnceTest {

    private fun stored() = MediaSession.MediaItemsWithStartPosition(
        listOf(MediaItem.Builder().setMediaId("song-1").build()),
        0,
        42_000L
    )

    @Test
    fun twoCallersAtOnceShareOneLoad() {
        val service = org.mockito.Mockito.mock(BaseMediaService::class.java, CALLS_REAL_METHODS)
        val loads = AtomicInteger()
        val firstIsLoading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = stored()

        doAnswer {
            loads.incrementAndGet()
            firstIsLoading.countDown()
            release.await()
            result
        }.`when`(service).loadStoredQueue()

        val results = ConcurrentLinkedQueue<MediaSession.MediaItemsWithStartPosition?>()
        val first = thread { results.add(service.loadStoredQueueOnce()) }
        assertTrue("the first load never started", firstIsLoading.await(5, TimeUnit.SECONDS))

        val second = thread { results.add(service.loadStoredQueueOnce()) }
        val blocked = System.currentTimeMillis() + 5_000
        while (second.state != Thread.State.WAITING && second.state != Thread.State.TIMED_WAITING) {
            assertTrue("the second caller never blocked", System.currentTimeMillis() < blocked)
            Thread.sleep(5)
        }

        release.countDown()
        first.join(5_000)
        second.join(5_000)

        assertEquals("the queue was loaded more than once", 1, loads.get())
        assertEquals(2, results.size)
        results.forEach { assertSame(result, it) }
    }

    @Test
    fun aLaterCallerLoadsAgainOnceTheFirstLoadIsDone() {
        val service = org.mockito.Mockito.mock(BaseMediaService::class.java, CALLS_REAL_METHODS)
        val loads = AtomicInteger()

        doAnswer {
            loads.incrementAndGet()
            stored()
        }.`when`(service).loadStoredQueue()

        service.loadStoredQueueOnce()
        service.loadStoredQueueOnce()

        assertEquals(2, loads.get())
    }

    @Test
    fun aFailedLoadDoesNotStickInTheSlot() {
        val service = org.mockito.Mockito.mock(BaseMediaService::class.java, CALLS_REAL_METHODS)
        val loads = AtomicInteger()

        doAnswer {
            loads.incrementAndGet()
            throw IllegalStateException("database is gone")
        }.`when`(service).loadStoredQueue()

        repeat(2) {
            try {
                service.loadStoredQueueOnce()
                throw AssertionError("expected the load to throw")
            } catch (e: IllegalStateException) {
                assertEquals("database is gone", e.message)
            }
        }

        assertEquals("the failed task was left in the slot", 2, loads.get())
    }
}
