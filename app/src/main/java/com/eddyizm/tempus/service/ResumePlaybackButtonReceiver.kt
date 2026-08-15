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
 * Reproduced on API 36 by emptying the queue table and sending a play command. Not starting the
 * service is the way out, so the queue is counted here, before media3 commits to the start.
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
