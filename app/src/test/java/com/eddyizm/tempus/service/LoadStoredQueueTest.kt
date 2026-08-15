package com.eddyizm.tempus.service

import androidx.media3.common.MediaItem
import com.eddyizm.tempus.model.Queue
import com.eddyizm.tempus.repository.QueueRepository
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.MappingUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class LoadStoredQueueTest {

    // indexOfMediaId runs for real, so these tests exercise the lookup instead of a copy of it.
    // Only mapMediaItems is stubbed, because it does a blocking database read per song.
    private fun load(
        storedRows: Int,
        lastPlayed: Queue?,
        mappedIds: List<String> = List(storedRows) { "song-$it" }
    ) = mockStatic(MappingUtil::class.java, CALLS_REAL_METHODS).use { mappingUtil ->
        val rows = List(storedRows) { mock<Child>() }
        val items = mappedIds.map { MediaItem.Builder().setMediaId(it).build() }
        mappingUtil.`when`<List<MediaItem>> { MappingUtil.mapMediaItems(any()) }.thenReturn(items)

        mockConstruction(QueueRepository::class.java) { repository, _ ->
            whenever(repository.media).thenReturn(rows)
            whenever(repository.lastPlayedMedia).thenReturn(lastPlayed)
        }.use {
            org.mockito.Mockito.mock(BaseMediaService::class.java, CALLS_REAL_METHODS)
                .loadStoredQueue()
        }
    }

    private fun row(id: String, position: Long) = Queue(id, playingChanged = position)

    @Test
    fun keepsTheResumePointWhenTheRowIsFound() {
        val result = load(storedRows = 5, lastPlayed = row("song-3", 42_000L))
        assertEquals(3, result!!.startIndex)
        assertEquals(42_000L, result.startPositionMs)
    }

    @Test
    fun startsFromTheBeginningWhenTheRowCouldNotBeRead() {
        val result = load(storedRows = 5, lastPlayed = null)
        assertEquals(0, result!!.startIndex)
        assertEquals(0L, result.startPositionMs)
    }

    @Test
    fun keepsTheTrackWhenThePositionIsNegative() {
        val result = load(storedRows = 5, lastPlayed = row("song-3", -1L))
        assertEquals(3, result!!.startIndex)
        assertEquals(0L, result.startPositionMs)
    }

    // The defect this replaces the clamp test for. song-2 did not map, so the stored ordinal 3
    // now sits at index 2, and indexing by ordinal sent playback to song-4.
    @Test
    fun findsTheTrackByIdentityWhenAnEarlierRowWasDropped() {
        val result = load(
            storedRows = 5,
            lastPlayed = row("song-3", 42_000L),
            mappedIds = listOf("song-0", "song-1", "song-3", "song-4")
        )
        assertEquals(2, result!!.startIndex)
        assertEquals(42_000L, result.startPositionMs)
    }

    @Test
    fun startsFromTheBeginningWhenTheRowIsNotInTheMappedList() {
        val result = load(
            storedRows = 5,
            lastPlayed = row("song-9", 42_000L),
            mappedIds = listOf("song-0", "song-1", "song-2")
        )
        assertEquals(0, result!!.startIndex)
        assertEquals(0L, result.startPositionMs)
    }

    @Test
    fun returnsNullWhenNothingIsStored() {
        assertNull(load(storedRows = 0, lastPlayed = null))
    }

    @Test
    fun returnsNullWhenNoStoredRowMaps() {
        assertNull(
            load(storedRows = 5, lastPlayed = row("song-3", 42_000L), mappedIds = emptyList())
        )
    }
}
