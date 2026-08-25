package com.eddyizm.tempus.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.TaskStackBuilder
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.media3.common.*
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.*
import androidx.media3.session.MediaSession.ControllerInfo
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.eddyizm.tempus.R
import com.eddyizm.tempus.equalizer.BuiltinBackend
import com.eddyizm.tempus.equalizer.EqualizerBackend
import com.eddyizm.tempus.equalizer.EqualizerManager
import com.eddyizm.tempus.equalizer.ExternalBackend
import com.eddyizm.tempus.equalizer.DefaultBackend
import com.eddyizm.tempus.repository.QueueRepository
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.*
import com.eddyizm.tempus.util.SleepTimerManager
import com.eddyizm.tempus.widget.WidgetUpdateManager
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val TAG = "BaseMediaService"

private const val RESUME_SHUTDOWN_CHANNEL_ID = "resume_shutdown"
private const val RESUME_SHUTDOWN_NOTIFICATION_ID = 1014

/**
 * The keys a media button start can arrive on that oblige a startForeground call. From API 26 up,
 * media3 forwards only these three to a media button receiver and drops the rest, and its own
 * notification builds a foreground service start only for play pause while paused, so next,
 * previous and stop from that notification oblige nothing, read from
 * DefaultActionFactory.createMediaActionPendingIntent in 1.9.2. Below API 31 media3 aims the
 * session's own media button intent at this service instead of at the receiver, and what the
 * platform sends there has not been established.
 */
private val FOREGROUND_START_KEY_CODES = intArrayOf(
    KeyEvent.KEYCODE_HEADSETHOOK,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY
)

/**
 * Whether a start of this service carries a promise of a startForeground call. media3 starts it with
 * the media button intent, and from API 26 up such a start is a real startForegroundService, which
 * obliges the process to call startForeground. Below 26 ContextCompat.startForegroundService is a
 * plain startService and obliges nothing, read from the core 1.18.0 bytecode.
 *
 * Play pause pressed while already playing is the one start that records a promise it does not owe,
 * because media3's notification sends keycode 85 whatever the player is doing. It is harmless. The
 * player has content, so keepForegroundPromise leaves it alone, and media3 clears the record on the
 * pause itself, through the grace window described on owesForegroundStart.
 */
internal fun startCommandPromisesForeground(action: String?, keyCode: Int, sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.O &&
        action == Intent.ACTION_MEDIA_BUTTON &&
        keyCode in FOREGROUND_START_KEY_CODES

/**
 * Whether removing the task stops this service. Playback that is running keeps it alive; anything
 * else is a service the user has dismissed.
 */
internal fun stopsOnTaskRemoved(playWhenReady: Boolean, playerHasContent: Boolean): Boolean =
    !playWhenReady || !playerHasContent

/**
 * Whether this service has to keep that promise itself, instead of leaving it to media3.
 *
 * A destroyed service cannot call startForeground, and one holding media items is left alone,
 * because stopping a service that is playing takes the media notification with it and media3 goes
 * foreground for it once playback starts. It wants playWhenReady and a state of READY or BUFFERING
 * for that, and then holds the service foreground for ten more minutes after playback stops, both
 * read from MediaNotificationManager in 1.9.2. So a paused queue is still covered by media3 for a
 * while, and a queue that never plays at all leaves the promise outstanding, which this does not
 * cover.
 */
internal fun owesForegroundStart(
    promised: Boolean,
    serviceDestroyed: Boolean,
    playerHasContent: Boolean
): Boolean = promised && !serviceDestroyed && !playerHasContent

@UnstableApi
open class BaseMediaService : MediaLibraryService(), MediaManager.QueueTarget {
    companion object {
        const val ACTION_BIND_EQUALIZER = "com.eddyizm.tempus.service.BIND_EQUALIZER"
        const val ACTION_EQUALIZER_UPDATED = "com.eddyizm.tempus.service.EQUALIZER_UPDATED"
        const val ACTION_RELOAD_EQUALIZER = "com.eddyizm.tempus.service.ACTION_RELOAD_EQUALIZER"
        var activeBrowserCount = 0
    }

    protected lateinit var exoplayer: ExoPlayer
    protected lateinit var mediaLibrarySession: MediaLibrarySession
    protected var sessionCallback: MediaLibrarySession.Callback? = null
    private lateinit var bitmapLoader: SyncBitmapLoader
    private lateinit var networkCallback: CustomNetworkCallback
    private lateinit var equalizerManager: EqualizerManager
    private val widgetUpdateHandler = Handler(Looper.getMainLooper())
    private var widgetUpdateScheduled = false
    // Set in onDestroy. restorePlayerFromQueue maps the saved queue on a background thread and
    // posts the player calls back to this handler; if the service is destroyed mid map (the app
    // swiped away during launch), that post must not touch the now released player.
    @Volatile private var serviceDestroyed = false
    private var storedQueueLoad: FutureTask<MediaSession.MediaItemsWithStartPosition?>? = null
    private val widgetUpdateRunnable = object : Runnable {
        override fun run() {
            val player = mediaLibrarySession.player
            if (!player.isPlaying) {
                widgetUpdateScheduled = false
                return
            }
            updateWidget(player)
            widgetUpdateHandler.postDelayed(this, WIDGET_UPDATE_INTERVAL_MS)
        }
    }

    private val radioHeaderCheckExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var radioHeaderCheckScheduled = false
    private var radioHeaderCheckFuture: ScheduledFuture<*>? = null
    private val radioHeaderCheckRunnable = Runnable {
        checkRadioHttpHeaders()
    }

    private val binder = LocalBinder()

    /**
     * True while this service owes the platform the startForeground call its own start promised.
     * Per instance, because the obligation belongs to one service record: a process wide flag is
     * either inherited by a service that owes nothing or cleared out from under one that does.
     */
    @Volatile
    private var foregroundStartPromised = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val keyEvent = intent?.let {
            IntentCompat.getParcelableExtra(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        }

        if (startCommandPromisesForeground(
                intent?.action,
                keyEvent?.keyCode ?: KeyEvent.KEYCODE_UNKNOWN,
                Build.VERSION.SDK_INT
            )
        ) {
            foregroundStartPromised = true
        }

        when (intent?.action) {
            ACTION_RELOAD_EQUALIZER -> reloadEqualizer()
        }

        return super.onStartCommand(intent, flags, startId)
    }

    open fun playerInitHook() {
        initializeExoPlayer()
        initializeMediaLibrarySession(exoplayer)
        initializePlayerListener(exoplayer)
        initializeSleepTimer()
        setPlayer(null, exoplayer)
    }

    open fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        return BaseSessionCallback(baseContext, this)
    }

    fun updateMediaItems(player: Player) {
        Log.d(TAG, "update items")
        // Re-resolve per-network stream URLs (maxBitRate/format) for the queue WITHOUT
        // interrupting the currently-playing track. The previous implementation called
        // clearMediaItems() + setMediaItems() over the live player, which discards the
        // active item's forward buffer and forces a re-prepare on every WiFi<->cellular
        // switch — an audible ~0.5s gap (and, on some devices, the failed re-prepare that
        // #682 recovers from). Instead, replace only the non-current items, and only when
        // the resolved URI actually changed, so the active item is never touched while
        // upcoming tracks still pick up the new network's transcoding settings.

        // Threading: the heavy computation (MappingUtil + isDownloaded) runs on a background
        // thread to avoid blocking the main thread. Only items from current+1 onward are
        // processed — already-played items are skipped. replaceMediaItem() is dispatched back
        // to the main thread via widgetUpdateHandler. The guard i < player.mediaItemCount protects
        // against queue changes during the background computation.

        val current = player.currentMediaItemIndex
        if (current == C.INDEX_UNSET) return

        // read all items
        val itemsToProcess = (current + 1 until player.mediaItemCount).map { i ->
            Pair(i, player.getMediaItemAt(i))
        }
        if (itemsToProcess.isEmpty()) return

        val delegate = Executors.newSingleThreadExecutor()
        val executor = MoreExecutors.listeningDecorator(delegate)
        val future: ListenableFuture<List<Pair<Int, MediaItem>>> = executor.submit(Callable {
            itemsToProcess.mapNotNull { (i, old) ->
                val mapped = MappingUtil.mapMediaItem(old)
                if (mapped.requestMetadata.mediaUri != old.requestMetadata.mediaUri) {
                    Pair(i, mapped)
                } else null
            }
        })
        delegate.shutdown()

        Futures.addCallback(future, object : FutureCallback<List<Pair<Int, MediaItem>>> {
            override fun onSuccess(updates: List<Pair<Int, MediaItem>>) {
                widgetUpdateHandler.post {
                    // Same hazard as restorePlayerFromQueue: the mapping runs on a background
                    // thread, so this post can land after onDestroy released the player.
                    if (serviceDestroyed) return@post
                    updates.forEach { (i, mapped) ->
                        if (i > player.currentMediaItemIndex
                            && i < player.mediaItemCount
                            && player.getMediaItemAt(i).mediaId == mapped.mediaId) {
                            player.replaceMediaItem(i, mapped)
                        }
                    }
                }
            }
            override fun onFailure(t: Throwable) {
                Log.e(TAG, "updateMediaItems failed", t)
            }
        }, MoreExecutors.directExecutor())
    }

    // MediaManager.QueueTarget: null once the service is destroyed, so a queue edit that
    // arrives after that is dropped instead of reaching a released player.
    override fun livePlayer(): Player? = if (serviceDestroyed) null else mediaLibrarySession.player

    // "Play next" under shuffle: items are inserted at current+1 on the timeline, and the
    // service splices them into shuffle position current+1. Inserts land asynchronously,
    // so requests are queued and applied from onTimelineChanged once each target count is visible.
    private data class PlayNextRequest(val insertPos: Int, val count: Int, val target: Int)
    private val playNextQueue = ArrayDeque<PlayNextRequest>()

    override fun requestPlayNextFixup(insertPos: Int, count: Int, target: Int) {
        if (insertPos < 0 || count <= 0 || target < 0) return
        playNextQueue.addLast(PlayNextRequest(insertPos, count, target))
        tryApplyPlayNextFixup()
    }

    private fun tryApplyPlayNextFixup() {
        val player = exoplayer
        while (playNextQueue.isNotEmpty()) {
            val req = playNextQueue.first()
            if (player.mediaItemCount < req.target) return  // insert not visible yet — wait for onTimelineChanged
            if (player.mediaItemCount != req.target) {       // count drifted — drop the stale request
                playNextQueue.removeFirst()
                continue
            }
            if (!player.shuffleModeEnabled) {                // timeline == play order; nothing to fix
                playNextQueue.removeFirst()
                continue
            }
            val current = player.currentMediaItemIndex
            if (current == C.INDEX_UNSET || req.insertPos + req.count > req.target) {
                playNextQueue.removeFirst()
                continue
            }

            // Build the current shuffle order minus the new items, then splice them in after current.
            val timeline = player.currentTimeline
            val base = ArrayList<Int>(req.target)
            var w = timeline.getFirstWindowIndex(true)
            while (w != C.INDEX_UNSET) {
                if (w < req.insertPos || w >= req.insertPos + req.count) base.add(w)
                w = timeline.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, true)
            }
            val curPos = base.indexOf(current)
            if (curPos < 0) {
                playNextQueue.removeFirst()
                continue
            }

            val newOrder = ArrayList<Int>(req.target)
            newOrder.addAll(base)
            for (j in 0 until req.count) {
                newOrder.add(curPos + 1 + j, req.insertPos + j)
            }
            player.shuffleOrder = DefaultShuffleOrder(newOrder.toIntArray(), Random.nextLong())
            Log.d(TAG, "playNextFixup: ${req.count} item(s) moved to shuffle position ${curPos + 1}")
            playNextQueue.removeFirst()
        }
    }

    /**
     * An unreadable resume point starts the queue from the beginning instead of refusing, because
     * on the resumption path media3 presses play on the empty player when this returns nothing.
     *
     * Use loadStoredQueueOnce wherever two loads can overlap.
     */
    fun loadStoredQueue(): MediaSession.MediaItemsWithStartPosition? {
        val queueRepository = QueueRepository()
        val storedQueue = queueRepository.media
        if (storedQueue.isNullOrEmpty()) return null

        val mediaItems = MappingUtil.mapMediaItems(storedQueue)
        if (mediaItems.isEmpty()) return null

        val lastPlayed = try {
            queueRepository.lastPlayedMedia
        } catch (_: Exception) {
            null
        }

        // The row carries its own position, so an identified track always keeps the offset that
        // was stored with it. A row we cannot place in the mapped list starts from the top.
        var lastIndex = 0
        var lastPosition = 0L

        if (lastPlayed != null) {
            val found = MappingUtil.indexOfMediaId(mediaItems, lastPlayed.id)
            if (found >= 0) {
                lastIndex = found
                lastPosition = lastPlayed.playingChanged.coerceAtLeast(0L)
            }
        }

        return MediaSession.MediaItemsWithStartPosition(mediaItems, lastIndex, lastPosition)
    }

    /**
     * Runs one load for however many callers want it at the same time. A cold start from a media
     * button has two: onCreate always starts one, and the resumption callback starts another when
     * the play command finds an empty player. Two loads are slow, since each one reads the queue
     * table and then, when a download directory is configured, asks it about every song, and one
     * of them comes back wrong: ExternalAudioReader's cache refresh returns early for a second
     * caller and leaves it resolving downloaded songs to server stream urls instead of local
     * files.
     */
    fun loadStoredQueueOnce(): MediaSession.MediaItemsWithStartPosition? {
        val task: FutureTask<MediaSession.MediaItemsWithStartPosition?>
        var mine = false

        // Only the bookkeeping is under the monitor, never the load itself. Holding it across the
        // load would put one whole read in front of the other on the path that has to reach
        // startForeground before the platform gives up on it.
        synchronized(this) {
            val running = storedQueueLoad
            if (running != null) {
                task = running
            } else {
                task = FutureTask(Callable { loadStoredQueue() })
                storedQueueLoad = task
                mine = true
            }
        }

        try {
            if (mine) task.run()
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            if (mine) {
                synchronized(this) {
                    if (storedQueueLoad === task) storedQueueLoad = null
                }
            }
        }
    }

    /**
     * Only playback keeps the promise, because media3 goes foreground for a session that is playing
     * or buffering, so a restore that finds nothing to play has to keep it here instead.
     *
     * Stopping without keeping it is not a way out. ActiveServices treats a service brought down
     * before it has shown a notification as the same violation and crashes the process at once,
     * where sitting out the timeout crashes it a few seconds later.
     *
     * Both callers reach this from a background load, so the whole decision is made on the main
     * thread. Reading the record here would be wrong as well as racy, restorePlayerFromQueue starts
     * from onCreate, which runs before onStartCommand has recorded anything.
     */
    fun keepForegroundPromise(reason: String) {
        widgetUpdateHandler.post {
            // Not read when the service is destroyed: isInitialized stays true after onDestroy and
            // a released player still reports the timeline it held.
            val hasContent = !serviceDestroyed &&
                this::mediaLibrarySession.isInitialized &&
                mediaLibrarySession.player.mediaItemCount > 0

            if (!owesForegroundStart(foregroundStartPromised, serviceDestroyed, hasContent)) {
                return@post
            }

            dischargeForegroundPromiseAndStop(reason)
        }
    }

    /**
     * Takes the service foreground on a throwaway notification, drops it and stops. Main thread
     * only.
     *
     * Two callers reach it. keepForegroundPromise, when this service owes a startForeground it will
     * never make by playing, and onTaskRemoved, which is stopping anyway and so discharges whatever
     * the player holds. The cost of the second one on a promise that was not owed is a notification
     * posted and pulled on the way out.
     */
    @SuppressLint("PrivateResource")
    private fun dischargeForegroundPromiseAndStop(reason: String) {
        foregroundStartPromised = false

        Log.w(TAG, "dischargeForegroundPromise: $reason, going foreground so the service can stop")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                    NotificationChannel(
                        RESUME_SHUTDOWN_CHANNEL_ID,
                        getString(R.string.app_name),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }

            val notification = NotificationCompat.Builder(this, RESUME_SHUTDOWN_CHANNEL_ID)
                .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
                .setContentTitle(getString(R.string.app_name))
                .setSilent(true)
                .build()

            startForeground(RESUME_SHUTDOWN_NOTIFICATION_ID, notification)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // Stopping anyway is not worse. The promise is already broken at this point, and
            // sitting here until the timeout ends in the same crash.
            Log.e(TAG, "dischargeForegroundPromise: could not go foreground", t)
        }

        stopSelf()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        super.onUpdateNotification(session, startInForegroundRequired)

        // media3 takes the service foreground itself on the ordinary path, which keeps the
        // promise. Cleared after super, not before it, so a call that throws leaves the promise
        // standing.
        if (startInForegroundRequired) {
            foregroundStartPromised = false
        }
    }

    fun restorePlayerFromQueue(player: Player) {
        if (player.mediaItemCount > 0) return

        // Load off the main thread: a large saved queue froze the UI on launch (#600).
        Thread {
            val stored = try {
                loadStoredQueueOnce()
            } catch (t: Throwable) {
                Log.e(TAG, "restorePlayerFromQueue: could not read the saved queue", t)
                null
            }

            if (stored == null) {
                keepForegroundPromise("restorePlayerFromQueue found nothing to play")
                return@Thread
            }

            widgetUpdateHandler.post {
                // onDestroy may have released the player while this queue was still mapping, and
                // the mediaItemCount check below cannot detect a released player, so bail first.
                if (serviceDestroyed) return@post
                if (player.mediaItemCount > 0) return@post
                player.setMediaItems(stored.mediaItems, stored.startIndex, stored.startPositionMs)
                player.prepare()
                updateWidget(player)
            }
        }.start()
    }

    private var lastRadioArtist: String? = null
    private var lastRadioTitle: String? = null

    // Throttle for onPlayerError re-prepare recovery (see #682).
    private var lastPlayerErrorRecoveryMs = 0L
    private val playerErrorRecoveryThrottleMs = 5_000L

    fun initializePlayerListener(player: Player) {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // A network switch (WiFi <-> mobile) surfaces here as a source/network
                // error. Without recovery the player goes idle and stays silent until the
                // app is restarted (issue #682). Re-prepare to resume from the current
                // position, but only for recoverable IO errors and throttled so a permanent
                // failure (bad URL, auth) can't spin in an endless prepare loop.
                Log.w(TAG, "onPlayerError: ${error.errorCodeName}", error)

                val recoverable = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> true
                    else -> false
                }
                if (!recoverable) return

                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPlayerErrorRecoveryMs >= playerErrorRecoveryThrottleMs) {
                    lastPlayerErrorRecoveryMs = now
                    player.prepare()
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                Log.d(TAG, "onMediaItemTransition" + player.currentMediaItemIndex)
                if (mediaItem == null) return
                ReplayGainUtil.applyGain(player, mediaItem)

                // --- Add for AA : Constants.AA_START_INDEX if présent ---
                val extras = mediaItem.mediaMetadata.extras
                val startIndex = extras?.getInt(Constants.AA_START_INDEX, -1) ?: -1
                if (startIndex >= 0 ) {
                    val cleanExtras = Bundle(extras).apply {
                        remove(Constants.AA_START_INDEX)
                    }
                    val newMetadata = mediaItem.mediaMetadata.buildUpon()
                        .setExtras(cleanExtras)
                        .build()
                    val currentIdx = player.currentMediaItemIndex
                    if (player is ExoPlayer && currentIdx != C.INDEX_UNSET) {
                        player.replaceMediaItem(
                            currentIdx,
                            mediaItem.buildUpon().setMediaMetadata(newMetadata).build()
                        )
                    }
                    if (startIndex in 0 until player.mediaItemCount && startIndex != currentIdx) {
                        player.seekTo(startIndex, 0L)
                    }
                }
                // --- End add for AA ---
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK || reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    MediaManager.setLastPlayedTimestamp(mediaItem)
                }

                // Safety net: if a track transition fires while end-of-track is armed
                // (e.g. stream with unknown duration that ended before the poller could
                // trigger the fade), abort any in-progress fade and pause immediately.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO &&
                    SleepTimerManager.getInstance().isEndOfTrack) {
                    SleepTimerManager.getInstance().stopEndOfTrackPoller()
                    SleepTimerManager.getInstance().cancelTimer()
                    player.volume = 1f
                    player.pause()
                }

                // Restart header checks for radio streams when media item changes
                val mediaType = mediaItem.mediaMetadata.extras?.getString("type")
                if (mediaType == Constants.MEDIA_TYPE_RADIO && player.isPlaying) {
                    stopRadioHeaderChecks()
                    scheduleRadioHeaderChecks()
                } else if (mediaType != Constants.MEDIA_TYPE_RADIO) {
                    stopRadioHeaderChecks()
                }

                updateWidget(player)
                QueuePreloader.preload(this@BaseMediaService, player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                Log.d(TAG, "onTimelineChanged reason=$reason")
                tryApplyPlayNextFixup()
                try {
                    ReplayGainUtil.prefetchQueueGains(player)
                } catch (t: Throwable) {
                    Log.w(TAG, "prefetchQueueGains failed: $t")
                }
                QueuePreloader.preload(this@BaseMediaService, player)
                if (timeline.isEmpty) return
                val window = Timeline.Window()
                for (i in 0 until timeline.windowCount) {
                    timeline.getWindow(i, window)
                    window.mediaItem.mediaMetadata.artworkUri?.let { bitmapLoader.prewarm(it) }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                Log.d(TAG, "onTracksChanged: " + player.currentMediaItemIndex)
                ReplayGainUtil.setReplayGain(player, tracks)
                val currentMediaItem = player.currentMediaItem
                if (currentMediaItem != null) {
                    val item = MappingUtil.mapMediaItem(currentMediaItem)
                    if (item.mediaMetadata.extras != null)
                        MediaManager.scrobble(item, false)

                    val handled = MediaServiceExtensionRegistry.handler
                        ?.handle(player, currentMediaItem, this@BaseMediaService)
                        ?: false

                    if (player.nextMediaItemIndex == C.INDEX_UNSET) {
                        if (!handled && Preferences.isContinuousPlayEnabled()) {
                            MediaManager.continuousPlay(currentMediaItem, this@BaseMediaService)
                        }
                    }
                }

                if (player is ExoPlayer) {
                    // https://stackoverflow.com/questions/56937283/exoplayer-shuffle-doesnt-reproduce-all-the-songs
                    if (MediaManager.justStarted.get()) {
                        Log.d(TAG, "update shuffle order")
                        MediaManager.justStarted.set(false)
                        val shuffledList = IntArray(player.mediaItemCount) { i -> i }
                        shuffledList.shuffle()
                        val index = shuffledList.indexOf(player.currentMediaItemIndex)
                        // swap current media index to the first index
                        if (index > -1 && shuffledList.isNotEmpty()) {
                            val tmp = shuffledList[0]
                            shuffledList[0] = shuffledList[index]
                            shuffledList[index] = tmp
                        }
                        player.shuffleOrder =
                            DefaultShuffleOrder(shuffledList, Random.nextLong())
                    }
                }
            }

            override fun onMetadata(metadata: Metadata) {
                // Handle streaming metadata (ICY, ID3) for radio / streaming content
                val currentItem = player.currentMediaItem ?: return
                val extras = currentItem.mediaMetadata.extras
                if (extras?.getString("type") != Constants.MEDIA_TYPE_RADIO) return

                var artist: String? = null
                var title: String? = null

                // Extract metadata from ICY/ID3/Vorbis
                for (i in 0 until metadata.length()) {
                    when (val entry = metadata[i]) {
                        is IcyInfo -> {
                            entry.title?.let { icyTitle ->
                                val parts = icyTitle.split(" - ", limit = 2)
                                if (parts.size == 2) {
                                    artist = parts[0].trim().ifEmpty { null }
                                    title = parts[1].trim().ifEmpty { null }
                                } else {
                                    title = icyTitle.trim().ifEmpty { null }
                                }
                            }
                        }
                        is TextInformationFrame -> {
                            @Suppress("DEPRECATION")
                            val value = entry.value
                            when (entry.id) {
                                "TPE1" -> if (!value.isNullOrBlank()) artist = value
                                "TIT2" -> if (!value.isNullOrBlank()) title = value
                            }
                        }
                        is VorbisComment -> {
                            @Suppress("DEPRECATION")
                            val value = entry.value
                            when (entry.key) {
                                "ARTIST" -> if (!value.isNullOrBlank()) artist = value
                                "TITLE" -> if (!value.isNullOrBlank()) title = value
                            }
                        }
                    }
                }

                if (artist.isNullOrBlank() && title.isNullOrBlank()) return
                if (artist == lastRadioArtist && title == lastRadioTitle) return // Deduplicate
                
                lastRadioArtist = artist
                lastRadioTitle = title

                // Stop HTTP header checks since we have embedded metadata
                stopRadioHeaderChecks()

                val currentIndex = player.currentMediaItemIndex
                if (currentIndex == C.INDEX_UNSET) return

                val metadataBuilder = currentItem.mediaMetadata.buildUpon()
                val newExtras = Bundle(extras ?: Bundle())

                // Store individual values in extras for UI
                artist?.let { newExtras.putString("radioArtist", it) }
                title?.let { newExtras.putString("radioTitle", it) }

                // Get station name (preserve if already set)
                val stationName = extras?.getString("stationName")
                    ?: currentItem.mediaMetadata.title?.toString()
                    ?: ""
                if (stationName.isNotBlank()) {
                    newExtras.putString("stationName", stationName)
                }

                // Format for notification/player: Title = "Artist - Song", Artist = "Station Name"
                val formattedTitle = when {
                    !artist.isNullOrBlank() && !title.isNullOrBlank() -> "$artist - $title"
                    !title.isNullOrBlank() -> title
                    !artist.isNullOrBlank() -> artist
                    else -> stationName
                }

                metadataBuilder.setTitle(formattedTitle)
                if (stationName.isNotBlank()) {
                    metadataBuilder.setArtist(stationName)
                }

                (player as? ExoPlayer)?.let { exo ->
                    exo.replaceMediaItem(currentIndex, currentItem.buildUpon()
                        .setMediaMetadata(metadataBuilder.setExtras(newExtras).build())
                        .build())
                    updateWidget(exo)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "onIsPlayingChanged " + player.currentMediaItemIndex)
                if (!isPlaying) {
                    MediaManager.setResumePoint(
                        player.currentMediaItem,
                        player.currentPosition
                    )
                } else {
                    MediaManager.scrobble(player.currentMediaItem, false)
                }
                if (isPlaying) {
                    scheduleWidgetUpdates()
                    scheduleRadioHeaderChecks()
                } else {
                    stopWidgetUpdates()
                    stopRadioHeaderChecks()
                }
                updateWidget(player)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "onPlaybackStateChanged")
                super.onPlaybackStateChanged(playbackState)
                if (!player.hasNextMediaItem() &&
                    playbackState == Player.STATE_ENDED &&
                    player.mediaMetadata.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC
                ) {
                    MediaManager.scrobble(player.currentMediaItem, true)
                    MediaManager.saveChronology(player.currentMediaItem)
                }
                updateWidget(player)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                Log.d(TAG, "onPositionDiscontinuity reason=$reason old=${oldPosition.mediaItemIndex} new=${newPosition.mediaItemIndex}")
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)

                // Re-apply gain whenever we stay on the same track for any reason
                // except an automatic transition to the next track.
                if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION &&
                    oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                    // Clear pending gain immediately (main thread) before reapplying.
                    // This pre-empts the same-format gapless promotion in onFlush: if
                    // the decoder ran ahead (endOfStreamPending=true) before the seek,
                    // hasPendingFlushGain being false when onFlush fires ensures we
                    // restore to the correct current-track baseline instead of applying
                    // the next track's gain mid-track.
                    ReplayGainUtil.getAudioProcessor().clearPendingGain()
                    ReplayGainUtil.reapplyCurrentTrackGain(player)
                }

                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.scrobble(oldPosition.mediaItem, true)
                        MediaManager.saveChronology(oldPosition.mediaItem)
                    }

                    if (newPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        MediaManager.setLastPlayedTimestamp(newPosition.mediaItem)
                    }
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK && oldPosition.mediaItemIndex != newPosition.mediaItemIndex) {
                    // SEEK only: scrobble a genuine user skip, not other index changes such as removing the currently playing track (REMOVE).
                    if (oldPosition.mediaItem?.mediaMetadata?.extras?.getString("type") == Constants.MEDIA_TYPE_MUSIC) {
                        val durationMs = ((oldPosition.mediaItem?.mediaMetadata?.extras?.getInt("duration") ?: 0).toLong()) * 1000L
                        if (MediaManager.meetsScrobbleThreshold(oldPosition.positionMs, durationMs)) {
                            MediaManager.scrobble(oldPosition.mediaItem, true)
                            MediaManager.saveChronology(oldPosition.mediaItem)
                        }
                    }
                }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                Preferences.setShuffleModeEnabled(shuffleModeEnabled)
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                Preferences.setRepeatMode(repeatMode)
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                Log.d(TAG, "onAudioSessionIdChanged")
                equalizerManager.attach(audioSessionId)
                sendBroadcast(Intent(ACTION_EQUALIZER_UPDATED))
            }
        })
        if (player.isPlaying) {
            scheduleWidgetUpdates()
        }
    }

    // -------------------------------------------------------------------------
    // Sleep timer
    // -------------------------------------------------------------------------

    /**
     * Registers a [SleepTimerManager.ServiceActionListener] on the singleton so
     * that fade-out and pause happen in the service regardless of whether the
     * Fragment is attached. Call once after the player is ready.
     */
    private fun initializeSleepTimer() {
        SleepTimerManager.getInstance().setServiceActionListener(object : SleepTimerManager.ServiceActionListener {
            override fun onTick(expired: Boolean) {
                if (expired) SleepTimerManager.getInstance().startFadeOutThenPause(mediaLibrarySession.player)
            }
            override fun onEndOfTrackArmed() {
                SleepTimerManager.getInstance().armEndOfTrackFadePoller(mediaLibrarySession.player)
            }
        })
        // If end-of-track was already armed when the service restarted (state
        // restored from SharedPreferences), re-arm the poller against the live player.
        if (SleepTimerManager.getInstance().isActive &&
                SleepTimerManager.getInstance().isEndOfTrack) {
            SleepTimerManager.getInstance().armEndOfTrackFadePoller(mediaLibrarySession.player)
        }
    }

    open fun onInstantMix(session: MediaSession, onComplete: Runnable? = null) {
        val player = session.player
        val currentMediaItem = player.currentMediaItem
        val currentIndex = player.currentMediaItemIndex
        val lastIndex = player.mediaItemCount - 1

        if (currentIndex in 0 until lastIndex) {
            Log.d(TAG, "onInstantMix: remove range from $currentIndex to $lastIndex")
            MediaManager.removeRange(this, currentIndex + 1, lastIndex + 1)
        }

        Log.d(TAG, "onInstantMix: start Continuous Play with $currentMediaItem")
        MediaManager.continuousPlay(currentMediaItem, this) {
            Handler(Looper.getMainLooper()).post { onComplete?.run() }
        }
    }

    fun setPlayer(oldPlayer: Player?, newPlayer: Player) {
        if (oldPlayer === newPlayer) return
        if (oldPlayer != null) {
            val currentQueue = getQueueFromPlayer(oldPlayer)
            val currentIndex = oldPlayer.currentMediaItemIndex
            val currentPosition = oldPlayer.currentPosition
            val isPlaying = oldPlayer.playWhenReady
            oldPlayer.stop()
            newPlayer.setMediaItems(currentQueue, currentIndex, currentPosition)
            newPlayer.playWhenReady = isPlaying
            newPlayer.prepare()
        }
        mediaLibrarySession.player = newPlayer
        (sessionCallback as? BaseSessionCallback)?.handlePlayerChanged(oldPlayer, newPlayer)
    }

    open fun releasePlayers() {
        exoplayer.release()
    }

    fun getQueueFromPlayer(player: Player): List<MediaItem> {
        return (0..player.mediaItemCount - 1).map(player::getMediaItemAt)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaLibrarySession.player

        if (!stopsOnTaskRemoved(player.playWhenReady, player.mediaItemCount > 0)) return

        // Stopping while the platform is still owed a startForeground is the same violation as
        // never making one, so the promise is kept on the way out. This already runs on the main
        // thread, and the discharge ends in stopSelf itself.
        if (foregroundStartPromised && !serviceDestroyed) {
            dischargeForegroundPromiseAndStop("the task was removed")
        } else {
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()

        playerInitHook()
        initializeEqualizer()
        initializeNetworkListener()
        restorePlayerFromQueue(mediaLibrarySession.player)
    }

    override fun onGetSession(controllerInfo: ControllerInfo): MediaLibrarySession {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        QueuePreloader.cancel()
        serviceDestroyed = true
        // Process scoped, so it outlives the service unless it is cleared here.
        MediaServiceExtensionRegistry.handler = null
        releaseNetworkCallback()
        equalizerManager.release(exoplayer.audioSessionId)
        ReplayGainUtil.release()
        stopWidgetUpdates()
        stopRadioHeaderChecks()
        SleepTimerManager.getInstance().stopEndOfTrackPoller()
        SleepTimerManager.getInstance().setServiceActionListener(null)
        radioHeaderCheckExecutor.shutdown()
        if (::bitmapLoader.isInitialized) bitmapLoader.shutdown()
        releasePlayers()
        mediaLibrarySession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Check if the intent is for our custom equalizer binder
        if (intent?.action == ACTION_BIND_EQUALIZER) {
            return binder
        }
        // Otherwise, handle it as a normal MediaLibraryService connection
        return super.onBind(intent)
    }

    private fun initializeExoPlayer() {
        exoplayer = ExoPlayer.Builder(this)
            .setRenderersFactory(getRenderersFactory())
            .setMediaSourceFactory(getMediaSourceFactory())
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setLoadControl(initializeLoadControl())
            .build()

        exoplayer.shuffleModeEnabled = Preferences.isShuffleModeEnabled()
        exoplayer.repeatMode = Preferences.getRepeatMode()
        exoplayer.playbackParameters = getPlaybackParameters(Preferences.getPlaybackSpeed())
    }

    private fun getPlaybackParameters(speed: Float): PlaybackParameters {
        val pitch = if (Preferences.isPlaybackSpeedPitchEnabled()) getAdjustedPitch(speed) else 1.0f
        return PlaybackParameters(speed, pitch)
    }

    private fun getAdjustedPitch(speed: Float): Float {
        return if (Preferences.isPlaybackSpeedManualPitchEnabled()) {
            Preferences.getPlaybackSpeedManualPitch()
        } else {
            speed
        }
    }

    private fun initializeEqualizer() {

        val equalizerBackend: EqualizerBackend =
            when (Preferences.getSelectedEqualizer()) {
            1 -> BuiltinBackend()
            2 -> ExternalBackend()
            else -> DefaultBackend()
        }

        equalizerManager = EqualizerManager(equalizerBackend, baseContext)
        equalizerManager.attach(exoplayer.audioSessionId)
        sendBroadcast(Intent(ACTION_EQUALIZER_UPDATED))
    }

    fun reloadEqualizer() {
        equalizerManager.release(exoplayer.audioSessionId)

        val backend: EqualizerBackend = when (Preferences.getSelectedEqualizer()) {
            1 -> BuiltinBackend()
            2 -> ExternalBackend()
            else -> DefaultBackend()
        }

        equalizerManager = EqualizerManager(backend, baseContext)
        equalizerManager.attach(exoplayer.audioSessionId)
        sendBroadcast(Intent(ACTION_RELOAD_EQUALIZER))
    }

    private fun initializeMediaLibrarySession(player: Player) {
        Log.d(TAG, "initializeMediaLibrarySession")
        val sessionActivityPendingIntent =
            TaskStackBuilder.create(this).run {
                addNextIntent(Intent(baseContext, MainActivity::class.java))
                getPendingIntent(0, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)
            }

        bitmapLoader = SyncBitmapLoader(applicationContext)

        mediaLibrarySession =
            MediaLibrarySession.Builder(this, player, getMediaLibrarySessionCallback())
                .setSessionActivity(sessionActivityPendingIntent)
                .setPeriodicPositionUpdateEnabled(false)
                .setBitmapLoader(bitmapLoader)
                .build()
    }

    private fun initializeNetworkListener() {
        networkCallback = CustomNetworkCallback()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(
            networkCallback
        )
        updateMediaItems(mediaLibrarySession.player)
    }

    private fun initializeLoadControl(): DefaultLoadControl {
        val preloadSec = Preferences.getSongPreloadBuffer().toLong()
        val preloadMs = TimeUnit.SECONDS.toMillis(preloadSec).toInt()
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                preloadMs,
                preloadMs,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
    }

    private fun releaseNetworkCallback() {
        getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
    }

    private fun updateWidget(player: Player) {
        val mi = player.currentMediaItem
        val title = mi?.mediaMetadata?.title?.toString()
            ?: mi?.mediaMetadata?.extras?.getString("title")
        val artist = mi?.mediaMetadata?.artist?.toString()
            ?: mi?.mediaMetadata?.extras?.getString("artist")
        val album = mi?.mediaMetadata?.albumTitle?.toString()
            ?: mi?.mediaMetadata?.extras?.getString("album")
        val extras = mi?.mediaMetadata?.extras
        val coverId = extras?.getString("coverArtId")
        val songLink = extras?.getString("assetLinkSong")
            ?: AssetLinkUtil.buildLink(AssetLinkUtil.TYPE_SONG, extras?.getString("id"))
        val albumLink = extras?.getString("assetLinkAlbum")
            ?: AssetLinkUtil.buildLink(AssetLinkUtil.TYPE_ALBUM, extras?.getString("albumId"))
        val artistLink = extras?.getString("assetLinkArtist")
            ?: AssetLinkUtil.buildLink(AssetLinkUtil.TYPE_ARTIST, extras?.getString("artistId"))
        val position = player.currentPosition.takeIf { it != C.TIME_UNSET } ?: 0L
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        WidgetUpdateManager.updateFromState(
            this,
            title ?: "",
            artist ?: "",
            album ?: "",
            coverId,
            player.isPlaying,
            player.shuffleModeEnabled,
            player.repeatMode,
            position,
            duration,
            songLink,
            albumLink,
            artistLink
        )
    }

    private fun scheduleWidgetUpdates() {
        if (widgetUpdateScheduled) return
        widgetUpdateHandler.postDelayed(widgetUpdateRunnable, WIDGET_UPDATE_INTERVAL_MS)
        widgetUpdateScheduled = true
    }

    private fun stopWidgetUpdates() {
        if (!widgetUpdateScheduled) return
        widgetUpdateHandler.removeCallbacks(widgetUpdateRunnable)
        widgetUpdateScheduled = false
    }

    private fun scheduleRadioHeaderChecks() {
        val player = mediaLibrarySession.player
        val currentItem = player.currentMediaItem ?: return
        val mediaType = currentItem.mediaMetadata.extras?.getString("type")
        if (mediaType != Constants.MEDIA_TYPE_RADIO) return
        
        if (radioHeaderCheckScheduled) return
        
        // Check immediately, then periodically
        checkRadioHttpHeaders()
        radioHeaderCheckFuture = radioHeaderCheckExecutor.scheduleWithFixedDelay(
            radioHeaderCheckRunnable,
            RADIO_HEADER_CHECK_INTERVAL_SECONDS,
            RADIO_HEADER_CHECK_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
        radioHeaderCheckScheduled = true
    }

    private fun stopRadioHeaderChecks() {
        if (!radioHeaderCheckScheduled) return
        radioHeaderCheckFuture?.cancel(false)
        radioHeaderCheckFuture = null
        radioHeaderCheckScheduled = false
    }

    private fun checkRadioHttpHeaders() {
        val player = mediaLibrarySession.player
        val currentItem = player.currentMediaItem ?: return
        val extras = currentItem.mediaMetadata.extras
        val mediaType = extras?.getString("type")
        if (mediaType != Constants.MEDIA_TYPE_RADIO) return
        
        // Skip if we already have embedded metadata (ICY/ID3) - HTTP headers are only fallback
        val hasEmbeddedMetadata = !currentItem.mediaMetadata.artist.isNullOrBlank() ||
                !currentItem.mediaMetadata.title.isNullOrBlank() ||
                (extras != null && !extras.getString("radioArtist").isNullOrBlank()) ||
                (extras != null && !extras.getString("radioTitle").isNullOrBlank())
        if (hasEmbeddedMetadata) return
        
        val streamUrl = extras?.getString("uri") ?: currentItem.requestMetadata.mediaUri?.toString()
        if (streamUrl.isNullOrBlank()) return

        try {
            val url = URL(streamUrl)
            val connection = url.openConnection() as? HttpURLConnection ?: return
            
            // Only try HEAD request (lightweight) - skip GET fallback as it's unreliable
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("Icy-MetaData", "1")
            connection.setRequestProperty("User-Agent", "Tempus/1.0")
            connection.connectTimeout = 3000 // Reduced timeout
            connection.readTimeout = 3000
            
            connection.connect()
            
            if (connection.responseCode >= 400) {
                connection.disconnect()
                return
            }
            
            // Check for metadata in HTTP headers
            val streamTitle = connection.getHeaderField("icy-name")
                ?: connection.getHeaderField("StreamTitle")
                ?: connection.getHeaderField("stream-title")
            
            connection.disconnect()
            
            if (!streamTitle.isNullOrBlank()) {
                processStreamTitle(streamTitle, player)
            }
        } catch (e: Exception) {
            // Silently fail - this is a fallback mechanism, ICY metadata is primary
        }
    }
    
    private fun processStreamTitle(streamTitle: String, player: Player) {
        // Parse "Artist - Title" format
        val parts = streamTitle.split(" - ", limit = 2)
        val artist = if (parts.size == 2) parts[0].trim().ifEmpty { null } else null
        val title = if (parts.size == 2) parts[1].trim().ifEmpty { null } else streamTitle.trim().ifEmpty { null }
        
        if (artist.isNullOrBlank() && title.isNullOrBlank()) return
        if (artist == lastRadioArtist && title == lastRadioTitle) return // Deduplicate
        
        lastRadioArtist = artist
        lastRadioTitle = title
        
        // Update on main thread
        widgetUpdateHandler.post {
            val currentItemNow = player.currentMediaItem ?: return@post
            val currentIndex = player.currentMediaItemIndex
            if (currentIndex == C.INDEX_UNSET) return@post
            
            val currentExtras = currentItemNow.mediaMetadata.extras
            if (currentExtras?.getString("type") != Constants.MEDIA_TYPE_RADIO) return@post
            
            // Double-check we still don't have embedded metadata (might have arrived since check)
            val hasEmbeddedMetadata = !currentItemNow.mediaMetadata.artist.isNullOrBlank() ||
                    !currentItemNow.mediaMetadata.title.isNullOrBlank() ||
                    (currentExtras != null && !currentExtras.getString("radioArtist").isNullOrBlank()) ||
                    (currentExtras != null && !currentExtras.getString("radioTitle").isNullOrBlank())
            if (hasEmbeddedMetadata) return@post
            
            val metadataBuilder = currentItemNow.mediaMetadata.buildUpon()
            val newExtras = Bundle(currentExtras ?: Bundle())
            
            // Store individual values in extras for UI
            artist?.let { newExtras.putString("radioArtist", it) }
            title?.let { newExtras.putString("radioTitle", it) }
            
            // Get station name (preserve if already set)
            val stationName = currentExtras?.getString("stationName")
                ?: currentItemNow.mediaMetadata.title?.toString()
                ?: ""
            if (stationName.isNotBlank()) {
                newExtras.putString("stationName", stationName)
            }
            
            // Format for notification/player: Title = "Artist - Song", Artist = "Station Name"
            val formattedTitle = when {
                !artist.isNullOrBlank() && !title.isNullOrBlank() -> "$artist - $title"
                !title.isNullOrBlank() -> title
                !artist.isNullOrBlank() -> artist
                else -> stationName
            }
            
            metadataBuilder.setTitle(formattedTitle)
            if (stationName.isNotBlank()) {
                metadataBuilder.setArtist(stationName)
            }
            metadataBuilder.setExtras(newExtras)
            
            (player as? ExoPlayer)?.let { exo ->
                exo.replaceMediaItem(currentIndex, currentItemNow.buildUpon()
                    .setMediaMetadata(metadataBuilder.build())
                    .build())
                updateWidget(exo)
            }
        }
    }

    private fun getRenderersFactory(): DefaultRenderersFactory {
        val extensionRendererMode = if (DownloadUtil.useExtensionRenderers())
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        else
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF

        return object : DefaultRenderersFactory(this) {
            init {
                setExtensionRendererMode(extensionRendererMode)
            }

            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(ReplayGainUtil.getAudioProcessor()))
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .build()
            }
        }
    }

    private fun getMediaSourceFactory(): MediaSource.Factory = DynamicMediaSourceFactory(this)

    private inner class CustomNetworkCallback : ConnectivityManager.NetworkCallback() {
        // The transport the queue's stream URLs were last resolved for. Tracked as the transport
        // rather than as a wifi flag, which cannot separate a handover gap from cellular. Issue 198.
        private var lastTransport = MusicUtil.getActiveTransport()

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val transport = MusicUtil.transportOf(networkCapabilities)
            if (transport == lastTransport) return
            lastTransport = transport

            // Only wifi and cellular have bitrate and format settings of their own. Anything else
            // is left alone, so a handover cannot resolve the queue against the network it leaves.
            val resolvable = transport == NetworkCapabilities.TRANSPORT_WIFI ||
                    transport == NetworkCapabilities.TRANSPORT_CELLULAR
            if (resolvable) MusicUtil.primeActiveTransport(transport)

            widgetUpdateHandler.post {
                if (serviceDestroyed) return@post
                if (resolvable) updateMediaItems(mediaLibrarySession.player)
                // preload() evaluates the network itself, and runs on every transport change,
                // including one the queue is not resolved against, since it gates on metered.
                QueuePreloader.preload(this@BaseMediaService, mediaLibrarySession.player)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getEqualizerManager(): EqualizerManager {
            return equalizerManager
        }

        fun getPlayer(): ExoPlayer {
            return exoplayer
        }
    }
}

private const val WIDGET_UPDATE_INTERVAL_MS = 1000L
private const val RADIO_HEADER_CHECK_INTERVAL_SECONDS = 30L // Reduced frequency - only fallback when ICY fails
