package me.rerere.rikkahub.learning.adapters

import kotlin.uuid.Uuid
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.learning.api.IdentityContextBudget
import me.rerere.rikkahub.learning.api.IdentityContextKind
import me.rerere.rikkahub.learning.api.IdentityContextRequest
import me.rerere.rikkahub.learning.api.IdentityContextResult
import me.rerere.rikkahub.learning.api.IdentityContextUnavailableReason
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.task.TaskSignatureV1
import me.rerere.rikkahub.memory.dreaming.model.DreamEpistemicType
import me.rerere.rikkahub.memory.dreaming.model.DreamScopeId
import me.rerere.rikkahub.memory.dreaming.model.DreamingFeatureFlags
import me.rerere.rikkahub.memory.dreaming.runtime.DreamRuntimeTestFixtures
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjection
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionReadRequest
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionReader
import me.rerere.rikkahub.memory.dreaming.runtime.DreamSnapshotProjectionUnavailableReason
import me.rerere.rikkahub.memory.dreaming.runtime.DreamingFeatureFlagSource
import me.rerere.rikkahub.memory.dreaming.snapshot.DreamSnapshotSection
import me.rerere.rikkahub.memory.dreaming.temporal.TemporalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamingIdentityAdapterTest {
    @Test
    fun assistantScopeMapsExactlyToPrivateDreamScopeAndUsesFrozenClock() = runBlocking {
        var observed: DreamSnapshotProjectionReadRequest? = null
        val adapter = adapter(
            reader = DreamSnapshotProjectionReader { read ->
                observed = read
                threeKindProjection()
            },
        )

        val result = adapter.queryRelevantIdentity(request())

        val capturedRequest = requireNotNull(observed)
        assertEquals(DreamScopeId.privateScope(ASSISTANT_ID), capturedRequest.scopeId)
        assertFalse(capturedRequest.scopeId.isGlobal)
        assertEquals(NOW, capturedRequest.frozenNowEpochMs)
        val items = (result as IdentityContextResult.Available).block.items
        assertEquals(
            listOf(
                IdentityContextKind.CURRENT_PROJECT,
                IdentityContextKind.ACTIVE_PLAN,
                IdentityContextKind.ACTIVE_CONSTRAINT,
            ),
            items.map { it.kind },
        )
        assertEquals(listOf(PROJECT, PLAN, CONSTRAINT), items.map { it.text })
        assertTrue(items.none { it.text.contains(DreamRuntimeTestFixtures.SNAPSHOT_ID) })
        assertTrue(items.none { it.text.contains(DreamRuntimeTestFixtures.CLAIM_A) })
    }

    @Test
    fun authoritySubjectNeverAliasesAssistantOrGlobalEvenWhenUuidShaped() = runBlocking {
        var reads = 0
        val adapter = adapter(
            reader = DreamSnapshotProjectionReader {
                reads++
                threeKindProjection()
            },
        )

        val result = adapter.queryRelevantIdentity(
            request(scope = LearningScope.AuthoritySubject(ASSISTANT_ID.toString())),
        )

        assertEquals(
            unavailable(IdentityContextUnavailableReason.SCOPE_NOT_REPRESENTABLE),
            result,
        )
        assertEquals(0, reads)
    }

    @Test
    fun disabledScopeReturnsBeforeProjectionRead() = runBlocking {
        var reads = 0
        val adapter = DreamingIdentityAdapter(
            featureFlags = DreamingFeatureFlagSource { DreamingFeatureFlags.M1AllOff },
            projectionReader = DreamSnapshotProjectionReader {
                reads++
                threeKindProjection()
            },
        )

        assertEquals(
            unavailable(IdentityContextUnavailableReason.DISABLED),
            adapter.queryRelevantIdentity(request()),
        )
        assertEquals(0, reads)
    }

    @Test
    fun wholeItemBoundsSkipOversizeWithoutTruncatingLaterItem() = runBlocking {
        val oversized = "x".repeat(8_193)
        val claims = listOf(
            DreamRuntimeTestFixtures.claim(statement = oversized),
            DreamRuntimeTestFixtures.claim(
                id = DreamRuntimeTestFixtures.CLAIM_B,
                ordinal = 1,
                statement = PROJECT,
            ),
        )
        val adapter = adapter(
            reader = DreamSnapshotProjectionReader {
                DreamRuntimeTestFixtures.projection(claims = claims)
            },
        )

        val result = adapter.queryRelevantIdentity(
            request(budget = IdentityContextBudget(maxItems = 1, maxChars = 10_000)),
        ) as IdentityContextResult.Available

        assertEquals(listOf(PROJECT), result.block.items.map { it.text })
        assertFalse(result.block.items.single().text.contains(oversized))
    }

    @Test
    fun validEmptyProjectionIsExplicitlyUnavailableNotAuthoritativeEmpty() = runBlocking {
        val adapter = adapter(
            reader = DreamSnapshotProjectionReader {
                DreamRuntimeTestFixtures.projection(claims = emptyList())
            },
        )

        assertEquals(
            unavailable(IdentityContextUnavailableReason.NO_RELEVANT_CONTEXT),
            adapter.queryRelevantIdentity(request()),
        )
    }

    @Test
    fun unsupportedDreamClaimTypesAreDroppedInsteadOfReinterpreted() = runBlocking {
        val claims = listOf(
            DreamRuntimeTestFixtures.claim(
                section = DreamSnapshotSection.OTHER_CONTEXT,
                epistemicType = DreamEpistemicType.OBSERVATION,
                statement = "不得重解释成身份摘要。",
            ),
            DreamRuntimeTestFixtures.claim(
                id = DreamRuntimeTestFixtures.CLAIM_B,
                statement = PROJECT,
            ),
        )
        val adapter = adapter(
            reader = DreamSnapshotProjectionReader {
                DreamRuntimeTestFixtures.projection(claims = claims)
            },
        )

        val result = adapter.queryRelevantIdentity(request()) as IdentityContextResult.Available

        assertEquals(listOf(IdentityContextKind.CURRENT_PROJECT), result.block.items.map { it.kind })
        assertEquals(listOf(PROJECT), result.block.items.map { it.text })
    }

    @Test
    fun mismatchedProjectionFailsClosed() = runBlocking {
        val adapter = adapter(
            reader = DreamSnapshotProjectionReader {
                DreamRuntimeTestFixtures.projection(scopeId = DreamScopeId.Global)
            },
        )

        assertEquals(
            unavailable(IdentityContextUnavailableReason.INVALID_PROJECTION),
            adapter.queryRelevantIdentity(request()),
        )
    }

    @Test
    fun unavailableAndThrowingSourcesDoNotLeakDetails() = runBlocking {
        val missing = adapter(
            reader = DreamSnapshotProjectionReader {
                DreamSnapshotProjection.Unavailable(
                    DreamSnapshotProjectionUnavailableReason.SCOPE_STATE_MISSING,
                )
            },
        ).queryRelevantIdentity(request())
        val corrupt = adapter(
            reader = DreamSnapshotProjectionReader {
                DreamSnapshotProjection.Unavailable(
                    DreamSnapshotProjectionUnavailableReason.PAYLOAD_HASH_INVALID,
                )
            },
        ).queryRelevantIdentity(request())
        val failed = adapter(
            reader = DreamSnapshotProjectionReader {
                error("raw source text and internal row id")
            },
        ).queryRelevantIdentity(request())

        assertEquals(unavailable(IdentityContextUnavailableReason.PROJECTION_UNAVAILABLE), missing)
        assertEquals(unavailable(IdentityContextUnavailableReason.INVALID_PROJECTION), corrupt)
        assertEquals(unavailable(IdentityContextUnavailableReason.SOURCE_FAILURE), failed)
        assertFalse(failed.toString().contains("raw source text"))
    }

    @Test
    fun adapterTimeoutIsExplicitAndContentFree() = runBlocking {
        val result = adapter(
            timeoutMs = 10L,
            reader = DreamSnapshotProjectionReader { awaitCancellation() },
        ).queryRelevantIdentity(request())

        assertEquals(unavailable(IdentityContextUnavailableReason.TIMEOUT), result)
    }

    @Test
    fun adapterToStringContainsNoScopeOrReaderIdentity() {
        val adapter = adapter(
            reader = DreamSnapshotProjectionReader { threeKindProjection() },
        )

        assertFalse(adapter.toString().contains(ASSISTANT_ID.toString()))
        assertFalse(adapter.toString().contains(TASK_SIGNATURE.value))
        assertEquals("DreamingIdentityAdapter(publicReadApi=ready)", adapter.toString())
    }

    private fun adapter(
        reader: DreamSnapshotProjectionReader,
        timeoutMs: Long = 2_000L,
    ) = DreamingIdentityAdapter(
        featureFlags = ENABLED_FLAGS,
        projectionReader = reader,
        timeoutMs = timeoutMs,
    )

    private fun request(
        scope: LearningScope = LearningScope.Assistant(ASSISTANT_ID),
        budget: IdentityContextBudget = IdentityContextBudget(maxItems = 4, maxChars = 16_384),
    ) = IdentityContextRequest(
        expectedScope = scope,
        taskSignature = TASK_SIGNATURE,
        budget = budget,
        frozenNowEpochMs = NOW,
    )

    private fun threeKindProjection(): DreamSnapshotProjection =
        DreamRuntimeTestFixtures.projection(
            claims = listOf(
                DreamRuntimeTestFixtures.claim(statement = PROJECT),
                DreamRuntimeTestFixtures.claim(
                    id = DreamRuntimeTestFixtures.CLAIM_B,
                    section = DreamSnapshotSection.ACTIVE_PLANS,
                    epistemicType = DreamEpistemicType.PLAN,
                    statement = PLAN,
                    temporalState = TemporalState.UPCOMING,
                ),
                DreamRuntimeTestFixtures.claim(
                    id = DreamRuntimeTestFixtures.CLAIM_C,
                    section = DreamSnapshotSection.ACTIVE_CONSTRAINTS,
                    epistemicType = DreamEpistemicType.CONSTRAINT,
                    statement = CONSTRAINT,
                    temporalState = TemporalState.TIMELESS,
                ),
            ),
        )

    private fun unavailable(reason: IdentityContextUnavailableReason) =
        IdentityContextResult.Unavailable(reason)

    private companion object {
        const val NOW = DreamRuntimeTestFixtures.NOW
        const val PROJECT = "用户当前正在实现离线 Android 记忆系统。"
        const val PLAN = "先完成受控检索，再进行审核。"
        const val CONSTRAINT = "任何身份上下文都不能授予工具权限。"
        val ASSISTANT_ID: Uuid = Uuid.parse(DreamRuntimeTestFixtures.scope.value)
        val TASK_SIGNATURE: TaskSignatureV1 = checkNotNull(
            TaskSignatureV1.parseOrNull("task-signature-v1:${"a".repeat(64)}"),
        )
        val ENABLED_FLAGS = DreamingFeatureFlagSource {
            DreamingFeatureFlags(schemaReady = true, use = true)
        }
    }
}
