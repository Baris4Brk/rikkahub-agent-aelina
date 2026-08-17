package me.rerere.rikkahub.learning.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single process-local lane shared by Learning operations, derived reset/erase and restore.
 * Keeping this small type independently testable makes the cross-database retention race fence
 * executable in JVM tests instead of relying only on a source-level claim.
 */
internal class LearningRuntimeOperationFence {
    private val mutex = Mutex()

    suspend fun <T> withLock(operation: suspend () -> T): T = mutex.withLock {
        operation()
    }
}
