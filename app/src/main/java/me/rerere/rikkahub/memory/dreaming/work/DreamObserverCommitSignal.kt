package me.rerere.rikkahub.memory.dreaming.work

import android.util.Log
import androidx.room.InvalidationTracker
import me.rerere.rikkahub.data.db.AppDatabase

/**
 * Low-latency post-commit hint for authority epoch changes.
 *
 * Room invalidation callbacks are delivered for committed table invalidations, so nested source
 * mutations never enqueue WorkManager before their outer transaction commits. The callback is a
 * hint only: startup and periodic scans recover a commit if this process dies before enqueueing.
 */
class DreamObserverCommitSignal(
    database: AppDatabase,
    private val scheduler: DreamObserverWorkScheduler,
    private val synthesisSignal: (() -> Unit)? = null,
) {
    // Retain a strong reference for the lifetime of this created-at-start singleton.
    private val observer = object : InvalidationTracker.Observer("memory_scope_state") {
        override fun onInvalidated(tables: Set<String>) {
            try {
                scheduler.enqueueDirtyScan()
            } catch (error: Exception) {
                Log.w(TAG, "Unable to signal Observer commit", error)
            }
            try {
                synthesisSignal?.invoke()
            } catch (error: Exception) {
                // This remains only a latency hint; startup/periodic scans reconstruct dirtiness.
                Log.w(TAG, "Unable to signal synthesis commit", error)
            }
        }
    }

    init {
        database.invalidationTracker.addObserver(observer)
    }

    private companion object {
        const val TAG = "DreamObserverSignal"
    }
}
