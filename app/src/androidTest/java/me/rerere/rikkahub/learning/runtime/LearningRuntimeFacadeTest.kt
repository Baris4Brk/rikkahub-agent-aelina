package me.rerere.rikkahub.learning.runtime

import android.content.Context
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.storage.restore.LearningRestoreFailureReason
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Emulator/disposable-device only. Never run this instrumentation test on the primary phone. */
@RunWith(AndroidJUnit4::class)
class LearningRuntimeFacadeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var facade: LearningRuntimeFacade? = null

    @After
    fun tearDown() = runBlocking {
        facade?.close()
        context.deleteDatabase("learning_runtime.db")
    }

    @Test
    fun restoreLatchCannotBeClearedByFlagToggleOrClose() = runBlocking {
        var enabled = true
        val subject = LearningRuntimeFacade(
            context = context,
            isEnabled = { enabled },
            retryBackoffMs = 1_000,
            isMainProcess = { true },
        ).also { facade = it }

        assertTrue(subject.withDatabase { Unit } is LearningRuntimeAccess.Ready)
        val fencedGeneration = subject.beginRestore()
        enabled = false
        assertEquals(
            LearningRuntimeAccess.Unavailable(LearningRuntimeErrorCode.RESTORE_IN_PROGRESS),
            subject.withDatabase { error("restore must remain latched") },
        )
        subject.close()
        enabled = true
        assertEquals(
            LearningRuntimeAccess.Unavailable(LearningRuntimeErrorCode.RESTORE_IN_PROGRESS),
            subject.withDatabase { error("close must not clear restore latch") },
        )
        assertEquals(fencedGeneration, subject.currentGeneration())
        assertEquals(LearningRuntimeState.RESTORING, subject.state.value)
    }

    @Test
    fun domainConflictPropagatesWithoutMarkingDatabaseCorrupt() = runBlocking {
        val subject = LearningRuntimeFacade(
            context = context,
            isEnabled = { true },
            retryBackoffMs = 1_000,
            isMainProcess = { true },
        ).also { facade = it }
        val failure = runCatching {
            subject.withDatabase { throw DomainConflict() }
        }.exceptionOrNull()
        assertTrue(failure is DomainConflict)
        assertEquals(LearningRuntimeState.READY, subject.state.value)
        assertTrue(subject.withDatabase { Unit } is LearningRuntimeAccess.Ready)
    }

    @Test
    fun initializerDomainConflictPropagatesWithoutEnteringDatabaseBackoff() = runBlocking {
        var initializeAttempts = 0
        val subject = LearningRuntimeFacade(
            context = context,
            isEnabled = { true },
            initializer = LearningRuntimeInitializer { _, _, _ ->
                initializeAttempts += 1
                if (initializeAttempts == 1) throw DomainConflict()
            },
            retryBackoffMs = 1_000,
            isMainProcess = { true },
        ).also { facade = it }

        val failure = runCatching { subject.withDatabase { Unit } }.exceptionOrNull()
        assertTrue(failure is DomainConflict)
        assertEquals(LearningRuntimeState.CLOSED, subject.state.value)
        assertTrue(subject.withDatabase { Unit } is LearningRuntimeAccess.Ready)
        assertEquals(2, initializeAttempts)
    }

    @Test
    fun restoreFenceIsVisibleBeforeCurrentOperationFinishes() = runBlocking {
        val operationStarted = CompletableDeferred<Unit>()
        val allowOperationToFinish = CompletableDeferred<Unit>()
        lateinit var session: LearningRuntimeSession
        val subject = LearningRuntimeFacade(
            context = context,
            isEnabled = { true },
            retryBackoffMs = 1_000,
            isMainProcess = { true },
        ).also { facade = it }

        val running = async {
            subject.withDatabase { current ->
                session = current
                operationStarted.complete(Unit)
                allowOperationToFinish.await()
            }
        }
        operationStarted.await()
        val previousGeneration = session.generation
        val restoring = async { subject.beginRestore() }

        while (subject.state.value != LearningRuntimeState.RESTORING) {
            kotlinx.coroutines.yield()
        }
        assertTrue(subject.currentGeneration() > previousGeneration)
        assertTrue(!session.isCurrent())

        allowOperationToFinish.complete(Unit)
        assertEquals(
            LearningRuntimeAccess.Unavailable(LearningRuntimeErrorCode.RESTORE_IN_PROGRESS),
            running.await(),
        )
        assertEquals(subject.currentGeneration(), restoring.await())
    }

    @Test
    fun structuredUnitOperationExpiresSessionAndSessionCarriesNoRoomHandle() = runBlocking {
        lateinit var escapedSession: LearningRuntimeSession
        val subject = LearningRuntimeFacade(
            context = context,
            isEnabled = { true },
            retryBackoffMs = 1_000,
            isMainProcess = { true },
        ).also { facade = it }

        assertTrue(
            subject.withDatabase { session -> escapedSession = session } is
                LearningRuntimeAccess.Ready,
        )
        assertFalse(escapedSession.isCurrent())
        assertTrue(
            LearningRuntimeSession::class.java.declaredFields.none { field ->
                RoomDatabase::class.java.isAssignableFrom(field.type)
            },
        )
        val facadeMethod = LearningRuntimeFacade::class.java.declaredMethods
            .single { it.name == "withDatabase" }
        assertEquals(0, facadeMethod.typeParameters.size)
    }

    @Test
    fun restorePortFailureKeepsProcessFencedAndDegraded() = runBlocking {
        val subject = LearningRuntimeFacade(
            context = context,
            isEnabled = { true },
            retryBackoffMs = 1_000,
            isMainProcess = { true },
        ).also { facade = it }
        val port = LearningRuntimeFacadeRestorePort(subject)

        assertTrue(subject.withDatabase { Unit } is LearningRuntimeAccess.Ready)
        val fence = port.beginIrreversibleRestore()
        port.remainDegradedUntilProcessRestart(
            fence,
            LearningRestoreFailureReason.MAIN_DATABASE_RESTORE_FAILED,
        )

        assertEquals(LearningRuntimeState.DEGRADED, subject.state.value)
        assertEquals(
            LearningRuntimeAccess.Unavailable(
                LearningRuntimeErrorCode.RESTORE_FAILED_RESTART_REQUIRED,
            ),
            subject.withDatabase { error("degraded restore latch must never reopen Room") },
        )
        subject.close()
        assertEquals(LearningRuntimeState.DEGRADED, subject.state.value)
    }

    private class DomainConflict : IllegalStateException("synthetic domain conflict")
}
