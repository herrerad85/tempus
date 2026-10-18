package com.eddyizm.tempus.service

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadIndex
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.eddyizm.tempus.repository.DownloadRepository
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.MusicUtil
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

@UnstableApi
class DownloaderManager(
    context: Context,
    private val dataSourceFactory: DataSource.Factory,
    downloadManager: DownloadManager,
) {
    private val context: Context = context.applicationContext
    private val downloadIndex: DownloadIndex = downloadManager.downloadIndex

    init {
        downloads = HashMap()
        loadDownloads()
    }

    private fun buildDownloadRequest(mediaItem: MediaItem): DownloadRequest {
        return DownloadHelper
            .forMediaItem(
                context,
                mediaItem,
                DownloadUtil.buildRenderersFactory(context, false),
                dataSourceFactory
            )
            .getDownloadRequest(Util.getUtf8Bytes(mediaItem.mediaId))
            .copyWithId(mediaItem.mediaId)
    }

    fun isDownloaded(mediaId: String?): Boolean {
        val download = downloads[mediaId]
        return download != null && download.state != Download.STATE_FAILED
    }

    fun isDownloaded(mediaItem: MediaItem): Boolean = isDownloaded(mediaItem.mediaId)

    fun areDownloaded(mediaItems: List<MediaItem>): Boolean = mediaItems.any { isDownloaded(it) }

    fun isRequested(mediaId: String): Boolean = requested.contains(mediaId)

    /**
     * The id is marked requested before the send, so a completion racing this call cannot leave it behind.
     */
    fun download(mediaItem: MediaItem, download: com.eddyizm.tempus.model.Download): Boolean {
        MusicUtil.applyTranscodedDownloadMetadata(download)
        download.downloadUri = mediaItem.requestMetadata.mediaUri!!.toString()
        val request = buildDownloadRequest(mediaItem)
        requested.add(mediaItem.mediaId)
        insertDatabase(download)
        try {
            DownloadService.sendAddDownload(context, DownloaderService::class.java, request, false)
        } catch (e: IllegalStateException) {
            requested.remove(mediaItem.mediaId)
            deleteDatabase(mediaItem.mediaId)
            Log.w(TAG, "Download service not started for " + mediaItem.mediaId, e)
            return false
        }
        return true
    }

    fun download(mediaItems: List<MediaItem>, downloads: List<com.eddyizm.tempus.model.Download>): Int {
        var sent = 0
        for (counter in mediaItems.indices) {
            if (download(mediaItems[counter], downloads[counter])) sent++
        }
        return sent
    }

    fun remove(mediaItem: MediaItem, download: com.eddyizm.tempus.model.Download) {
        DownloadService.sendRemoveDownload(context, DownloaderService::class.java, buildDownloadRequest(mediaItem).id, false)
        deleteDatabase(download.id)
        downloads.remove(download.id)
    }

    fun remove(mediaItems: List<MediaItem>, downloads: List<com.eddyizm.tempus.model.Download>) {
        for (counter in mediaItems.indices) {
            remove(mediaItems[counter], downloads[counter])
        }
    }

    fun removeAll() {
        DownloadService.sendRemoveAllDownloads(context, DownloaderService::class.java, false)
        deleteAllDatabase()
        DownloadUtil.eraseDownloadFolder(context)
    }

    private fun loadDownloads() {
        try {
            downloadIndex.getDownloads().use { loadedDownloads ->
                while (loadedDownloads.moveToNext()) {
                    val download = loadedDownloads.download
                    downloads[download.request.id] = download
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to query downloads", e)
            return
        }

        for (orphan in getDownloadRepository().getFailedDownloads()) {
            if (!downloads.containsKey(orphan.id) && !requested.contains(orphan.id)) {
                getDownloadRepository().deleteFailed(orphan.id)
            }
        }
    }

    companion object {
        private const val TAG = "DownloaderManager"

        private lateinit var downloads: HashMap<String, Download>
        private val requested: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @JvmStatic
        fun getDownloadNotificationMessage(id: String): String? {
            return getDownloadRepository().getDownload(id)?.title
        }

        @JvmStatic
        fun updateRequestDownload(download: Download) {
            updateDatabase(download.request.id)
            downloads[download.request.id] = download
            requested.remove(download.request.id)
        }

        @JvmStatic
        fun removeRequestDownload(download: Download) {
            deleteDatabase(download.request.id)
            downloads.remove(download.request.id)
            requested.remove(download.request.id)
        }

        @JvmStatic
        fun failRequestDownload(download: Download?) {
            if (download == null) return
            getDownloadRepository().deleteFailed(download.request.id)
            downloads.remove(download.request.id)
            requested.remove(download.request.id)
        }

        private fun getDownloadRepository(): DownloadRepository = DownloadRepository()

        private fun insertDatabase(download: com.eddyizm.tempus.model.Download) {
            getDownloadRepository().insert(download)
        }

        private fun deleteDatabase(id: String) {
            getDownloadRepository().delete(id)
        }

        private fun deleteAllDatabase() {
            getDownloadRepository().deleteAll()
        }

        private fun updateDatabase(id: String) {
            getDownloadRepository().update(id)
        }
    }
}
