package com.eddyizm.tempus.service;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.offline.Download;
import androidx.media3.exoplayer.offline.DownloadCursor;
import androidx.media3.exoplayer.offline.DownloadHelper;
import androidx.media3.exoplayer.offline.DownloadIndex;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.DownloadService;

import com.eddyizm.tempus.repository.DownloadRepository;
import com.eddyizm.tempus.subsonic.models.Child;
import com.eddyizm.tempus.util.DownloadUtil;
import com.eddyizm.tempus.util.MappingUtil;
import com.eddyizm.tempus.util.MusicUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@UnstableApi
public class DownloaderManager {
    private static final String TAG = "DownloaderManager";

    private final Context context;
    private final DataSource.Factory dataSourceFactory;
    private final DownloadIndex downloadIndex;

    // Read from the bulk thread below while the download service writes it on main.
    private static Map<String, Download> downloads;
    // Handed to the service and not yet finished, the map above only learns of a download once it completes.
    private static final Set<String> requested = ConcurrentHashMap.newKeySet();
    // One thread, so two bulk requests for the same tracks queue in order and the second sees the first's marks.
    private static final ExecutorService bulk = Executors.newSingleThreadExecutor();
    // Bumped by removeAll, so a bulk request made before a delete stops sending once it lands.
    private static final Object sending = new Object();
    private static volatile int generation;

    public DownloaderManager(Context context, DataSource.Factory dataSourceFactory, DownloadManager downloadManager) {
        this.context = context.getApplicationContext();
        this.dataSourceFactory = dataSourceFactory;

        downloads = new ConcurrentHashMap<>();
        downloadIndex = downloadManager.getDownloadIndex();

        loadDownloads();
    }

    private DownloadRequest buildDownloadRequest(MediaItem mediaItem) {
        return DownloadHelper
                .forMediaItem(
                        context,
                        mediaItem,
                        null,
                        dataSourceFactory)
                .getDownloadRequest(Util.getUtf8Bytes(checkNotNull(mediaItem.mediaId)))
                .copyWithId(mediaItem.mediaId);
    }

    public boolean isDownloaded(String mediaId) {
        // ConcurrentHashMap throws on a null key, and a podcast episode can have no stream id.
        if (mediaId == null) return false;
        @Nullable Download download = downloads.get(mediaId);
        return download != null && download.state != Download.STATE_FAILED;
    }

    public boolean isDownloaded(MediaItem mediaItem) {
        return isDownloaded(mediaItem.mediaId);
    }

    public boolean areDownloaded(List<MediaItem> mediaItems) {
        return mediaItems.stream().anyMatch(this::isDownloaded);
    }

    public boolean isRequested(String mediaId) {
        return requested.contains(mediaId);
    }

    public boolean download(MediaItem mediaItem, com.eddyizm.tempus.model.Download download) {
        MusicUtil.applyTranscodedDownloadMetadata(download);
        download.setDownloadUri(mediaItem.requestMetadata.mediaUri.toString());
        // Marked before the send so a completion racing this call cannot leave the id behind.
        DownloadRequest request = buildDownloadRequest(mediaItem);
        requested.add(mediaItem.mediaId);
        try {
            DownloadService.sendAddDownload(context, DownloaderService.class, request, false);
        } catch (IllegalStateException e) {
            requested.remove(mediaItem.mediaId);
            Log.w(TAG, "Download service not started for " + mediaItem.mediaId, e);
            return false;
        }
        insertDatabase(download);
        return true;
    }

    public int download(List<MediaItem> mediaItems, List<com.eddyizm.tempus.model.Download> downloads) {
        int sent = 0;
        for (int counter = 0; counter < mediaItems.size(); counter++) {
            if (download(mediaItems.get(counter), downloads.get(counter))) sent++;
        }
        return sent;
    }

    /**
     * Maps and queues the songs off the main thread, where the cost grows with the list. Reports on the main
     * thread how many reached the download service, or a negative number when every start was refused.
     */
    public void download(List<Child> songs, Function<Child, com.eddyizm.tempus.model.Download> toDownload, @Nullable IntConsumer onQueued) {
        queue(songs, song -> true, toDownload, onQueued);
    }

    /** Same as {@link #download(List, Function, IntConsumer)}, skipping songs already downloaded or queued. */
    public void downloadMissing(List<Child> songs, Function<Child, com.eddyizm.tempus.model.Download> toDownload, @Nullable IntConsumer onQueued) {
        // Requested first, since a finished download is added to the map before it leaves requested.
        queue(songs, song -> !isRequested(song.getId()) && !isDownloaded(song.getId()), toDownload, onQueued);
    }

    private void queue(List<Child> songs, Predicate<Child> wanted, Function<Child, com.eddyizm.tempus.model.Download> toDownload, @Nullable IntConsumer onQueued) {
        List<Child> copy = new ArrayList<>(songs);
        Handler main = new Handler(Looper.getMainLooper());
        int requestedIn = generation;
        bulk.execute(() -> {
            List<Child> picked = copy.stream().filter(wanted).collect(Collectors.toList());
            int sent = 0;
            for (Child song : picked) {
                // Mapped one at a time, so the first download starts and the service goes to the foreground while the app is still on screen.
                MediaItem mediaItem = MappingUtil.mapDownload(song);
                com.eddyizm.tempus.model.Download row = toDownload.apply(song);
                synchronized (sending) {
                    if (generation != requestedIn) break;
                    if (download(mediaItem, row)) sent++;
                }
            }
            final int result = sent;
            if (onQueued != null) main.post(() -> onQueued.accept(result == 0 && !picked.isEmpty() ? -1 : result));
        });
    }

    public void remove(MediaItem mediaItem, com.eddyizm.tempus.model.Download download) {
        DownloadService.sendRemoveDownload(context, DownloaderService.class, buildDownloadRequest(mediaItem).id, false);
        deleteDatabase(download.getId());
        downloads.remove(download.getId());
    }

    public void remove(List<MediaItem> mediaItems, List<com.eddyizm.tempus.model.Download> downloads) {
        for (int counter = 0; counter < mediaItems.size(); counter++) {
            remove(mediaItems.get(counter), downloads.get(counter));
        }
    }

    public void removeAll() {
        synchronized (sending) {
            DownloadService.sendRemoveAllDownloads(context, DownloaderService.class, false);
            // The service drops these entries only once each removal finishes, and a sync in between would skip them.
            downloads.clear();
            requested.clear();
            generation++;
        }
        deleteAllDatabase();
        DownloadUtil.eraseDownloadFolder(context);
    }

    private void loadDownloads() {
        try (DownloadCursor loadedDownloads = downloadIndex.getDownloads()) {
            while (loadedDownloads.moveToNext()) {
                Download download = loadedDownloads.getDownload();
                downloads.put(download.request.id, download);
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to query downloads", e);
        }
    }

    public static String getDownloadNotificationMessage(String id) {
        com.eddyizm.tempus.model.Download download = getDownloadRepository().getDownload(id);
        return download != null ? download.getTitle() : null;
    }

    public static void updateRequestDownload(Download download) {
        updateDatabase(download.request.id);
        downloads.put(download.request.id, download);
        requested.remove(download.request.id);
    }

    public static void removeRequestDownload(Download download) {
        deleteDatabase(download.request.id);
        downloads.remove(download.request.id);
        requested.remove(download.request.id);
    }

    public static void forgetRequest(Download download) {
        requested.remove(download.request.id);
    }

    private static DownloadRepository getDownloadRepository() {
        return new DownloadRepository();
    }

    private static void insertDatabase(com.eddyizm.tempus.model.Download download) {
        getDownloadRepository().insert(download);
    }

    private static void deleteDatabase(String id) {
        getDownloadRepository().delete(id);
    }

    private static void deleteAllDatabase() {
        getDownloadRepository().deleteAll();
    }

    private static void updateDatabase(String id) {
        getDownloadRepository().update(id);
    }
}