package com.eddyizm.tempus.service

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver
import com.eddyizm.tempus.repository.QueueRepository

private const val TAG = "ResumePlaybackButtonReceiver"

/**
 * Letting media3 start the service obliges it to call startForeground. With nothing to play it
 * never will, and the platform kills the process with ForegroundServiceDidNotStartInTimeException.
 * Reproduced on API 36 by emptying the queue table and sending a play command. Counting the queue
 * here skips the start when the table is empty, which is the cheapest case to rule out.
 *
 * A row count is not the same question as a playable item, so counting cannot rule the crash out on
 * its own. A row that fails to map, or a queue read that fails after this count has already
 * succeeded, still leaves the service started with nothing to play. BaseMediaService covers that:
 * the service it starts records the promise from the media button start itself and keeps it when a
 * restore finds nothing.
 *
 * The system remembers this class by name once a session goes active, so renaming or moving it
 * stops bluetooth resume working on existing installs until the app plays once from the UI again.
 * Observed on an emulator across a rename, not traced to a platform source.
 */
@UnstableApi
class ResumePlaybackButtonReceiver : MediaButtonReceiver() {

    public override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean {
        val saved = try {
            QueueRepository().count()
        } catch (t: Throwable) {
            Log.e(TAG, "could not count the saved queue", t)
            -1
        }

        if (saved < 0) {
            Log.w(TAG, "could not read the saved queue, not starting the service")
            return false
        }

        if (saved == 0) {
            Log.d(TAG, "no saved queue, not starting the service")
            return false
        }

        return true
    }
}
