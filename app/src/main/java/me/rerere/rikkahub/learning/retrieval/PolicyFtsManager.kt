package me.rerere.rikkahub.learning.retrieval

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.text.Normalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.learning.model.LearningScope
import me.rerere.rikkahub.learning.storage.LearningDatabase
import me.rerere.rikkahub.learning.storage.LearningPolicyEntity
import me.rerere.rikkahub.learning.storage.VALID_POLICY_EVIDENCE_PREDICATE

data class BoundedPolicyQuery(
    val normalized: String,
    val terms: List<String>,
) {
    init {
        require(normalized.length <= PolicyFtsManager.MAX_QUERY_CHARS)
        require(terms.size <= PolicyFtsManager.MAX_QUERY_TERMS)
        require(terms.all { it.length in 1..64 })
    }

    override fun toString(): String = "BoundedPolicyQuery(terms=${terms.size}, text=<redacted>)"
}

/**
 * P1 FTS5 projection. It is rebuildable from `learning_policies`; triggers keep deletes, retention
 * and exact-scope erase synchronized without making the projection authoritative.
 */
class PolicyFtsManager(
    private val database: LearningDatabase,
) {
    suspend fun searchEligible(
        scope: LearningScope,
        query: String,
        freshAfterMs: Long,
        limit: Int,
    ): List<LearningPolicyEntity> = withContext(Dispatchers.IO) {
        require(limit in 1..MAX_POLICY_FTS_DB_CANDIDATES)
        require(freshAfterMs >= 0L)
        val prepared = prepareQuery(query)
        if (prepared.terms.isEmpty()) return@withContext emptyList()
        val ids = mutableListOf<String>()
        database.openHelper.readableDatabase.query(
            POLICY_FTS_SEARCH_SQL,
            arrayOf<Any?>(
                prepared.normalized,
                scope.kind.name,
                scope.storageId,
                freshAfterMs,
                limit,
            ),
        ).use { cursor ->
            while (cursor.moveToNext()) ids += cursor.getString(0)
        }
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return@withContext emptyList()
        val byId = database.policyDao().findEligibleShadowPoliciesByIds(
            scopeKind = scope.kind.name,
            scopeId = scope.storageId,
            policyIds = distinctIds,
            freshAfterMs = freshAfterMs,
        ).associateBy(LearningPolicyEntity::id)
        distinctIds.mapNotNull(byId::get)
    }

    /** Privacy erase must remove the rebuildable text projection in the same derived-DB fence. */
    suspend fun eraseScope(scope: LearningScope) = withContext(Dispatchers.IO) {
        val writable = database.openHelper.writableDatabase
        // Direct Room test/erase callers do not necessarily pass through the runtime onOpen
        // callback. If the rebuildable projection was never created, there is no FTS payload to
        // erase. Do not try to create it here: the default framework SQLite used by small Room
        // tests does not load the production `simple`/jieba extension.
        val projectionExists = writable.query(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'policy_fts' LIMIT 1",
        ).use { it.moveToFirst() }
        if (!projectionExists) return@withContext
        writable.execSQL(
            "DELETE FROM policy_fts WHERE scope_kind = ? AND scope_id = ?",
            arrayOf(scope.kind.name, scope.storageId),
        )
    }
    companion object {
        const val MAX_QUERY_CHARS = 2_048
        const val MAX_QUERY_TERMS = 64

        fun prepareQuery(raw: String): BoundedPolicyQuery {
            require(raw.length <= MAX_POLICY_RAW_QUERY_CHARS) { "Policy query is too large" }
            val normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .lowercase()
                .replace(CONTROL, " ")
                .replace(WHITESPACE, " ")
                .trim()
                .take(MAX_QUERY_CHARS)
            val lexicalTerms = TOKEN.findAll(normalized).map { it.value }.toMutableList()
            // CJK runs use deterministic bigrams because tokenizer availability varies by build.
            CJK_RUN.findAll(normalized).forEach { match ->
                val chars = match.value.toList()
                if (chars.size == 1) lexicalTerms += match.value
                else chars.windowed(2).forEach { lexicalTerms += it.joinToString("") }
            }
            return BoundedPolicyQuery(
                normalized = normalized,
                terms = lexicalTerms.filter { it.isNotBlank() }.distinct().take(MAX_QUERY_TERMS),
            )
        }

        fun lexicalScore(query: BoundedPolicyQuery, searchableText: String): Double {
            if (query.terms.isEmpty()) return 0.0
            val candidate = prepareQuery(searchableText.take(MAX_POLICY_RAW_QUERY_CHARS))
            val candidateTerms = candidate.terms.toSet()
            val matched = query.terms.count { it in candidateTerms }
            return matched.toDouble() / query.terms.size.toDouble()
        }

        private val CONTROL = Regex("[\\p{Cc}\\p{Cf}]")
        private val WHITESPACE = Regex("\\s+")
        private val TOKEN = Regex("[\\p{L}\\p{N}_-]{2,64}")
        private val CJK_RUN = Regex("[\\u3400-\\u9fff]{1,32}")
    }
}

internal const val POLICY_FTS_CREATE_SQL = """
    CREATE VIRTUAL TABLE IF NOT EXISTS policy_fts USING fts5(
        trigger_summary,
        procedure_summary,
        verification_summary,
        boundary_summary,
        failure_mode_summary,
        policy_id UNINDEXED,
        scope_kind UNINDEXED,
        scope_id UNINDEXED,
        tokenize = 'simple'
    )
"""

internal val POLICY_FTS_TRIGGER_SQL: List<String> = listOf(
    """
    CREATE TRIGGER policy_fts_ai AFTER INSERT ON learning_policies
    WHEN new.status IN ('CANDIDATE', 'SHADOW')
      AND new.source_valid = 1 AND new.schema_valid = 1
      AND substr(new.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:'
      AND substr(new.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:'
      AND new.applicable_template_identity IS NOT NULL
      AND new.applicable_configuration_identity IS NOT NULL
      AND new.applicable_configuration_generation > 0 BEGIN
        INSERT INTO policy_fts(
            trigger_summary, procedure_summary, verification_summary, boundary_summary,
            failure_mode_summary, policy_id, scope_kind, scope_id
        ) VALUES (
            new.trigger_summary, new.procedure_summary, new.verification_summary,
            new.boundary_summary, new.failure_mode_summary, new.id, new.scope_kind, new.scope_id
        );
    END
    """.trimIndent(),
    """
    CREATE TRIGGER policy_fts_au AFTER UPDATE OF
        trigger_summary, procedure_summary, verification_summary, boundary_summary,
        failure_mode_summary, scope_kind, scope_id, status, source_valid, schema_valid,
        applicable_model_identity_wire, applicable_provider_identity_wire,
        applicable_template_identity, applicable_configuration_identity,
        applicable_configuration_generation
    ON learning_policies BEGIN
        DELETE FROM policy_fts WHERE policy_id = old.id;
        INSERT INTO policy_fts(
            trigger_summary, procedure_summary, verification_summary, boundary_summary,
            failure_mode_summary, policy_id, scope_kind, scope_id
        ) SELECT
            new.trigger_summary, new.procedure_summary, new.verification_summary,
            new.boundary_summary, new.failure_mode_summary, new.id, new.scope_kind, new.scope_id
        WHERE new.status IN ('CANDIDATE', 'SHADOW')
            AND new.source_valid = 1 AND new.schema_valid = 1
            AND substr(new.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:'
            AND substr(new.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:'
            AND new.applicable_template_identity IS NOT NULL
            AND new.applicable_configuration_identity IS NOT NULL
            AND new.applicable_configuration_generation > 0;
    END
    """.trimIndent(),
    """
    CREATE TRIGGER policy_fts_ad AFTER DELETE ON learning_policies BEGIN
        DELETE FROM policy_fts WHERE policy_id = old.id;
    END
    """.trimIndent(),
)

internal const val POLICY_FTS_BACKFILL_MISSING_SQL = """
    INSERT INTO policy_fts(
        trigger_summary, procedure_summary, verification_summary, boundary_summary,
        failure_mode_summary, policy_id, scope_kind, scope_id
    )
    SELECT p.trigger_summary, p.procedure_summary, p.verification_summary, p.boundary_summary,
           p.failure_mode_summary, p.id, p.scope_kind, p.scope_id
    FROM learning_policies AS p
    WHERE p.status IN ('CANDIDATE', 'SHADOW')
      AND p.source_valid = 1 AND p.schema_valid = 1
      AND substr(p.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:'
      AND substr(p.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:'
      AND p.applicable_template_identity IS NOT NULL
      AND p.applicable_configuration_identity IS NOT NULL
      AND p.applicable_configuration_generation > 0
      AND NOT EXISTS (SELECT 1 FROM policy_fts AS f WHERE f.policy_id = p.id)
"""

internal const val POLICY_FTS_DELETE_ORPHANS_SQL = """
    DELETE FROM policy_fts
    WHERE NOT EXISTS (SELECT 1 FROM learning_policies AS p WHERE p.id = policy_fts.policy_id)
"""

internal const val POLICY_FTS_DELETE_INELIGIBLE_SQL = """
    DELETE FROM policy_fts
    WHERE policy_id IN (
        SELECT id FROM learning_policies
        WHERE status NOT IN ('CANDIDATE', 'SHADOW')
           OR source_valid != 1 OR schema_valid != 1
           OR substr(applicable_model_identity_wire, 1, 9) != 'EXACT_V1:'
           OR substr(applicable_provider_identity_wire, 1, 9) != 'EXACT_V1:'
           OR applicable_template_identity IS NULL
           OR applicable_configuration_identity IS NULL
           OR applicable_configuration_generation <= 0
    )
"""

internal const val POLICY_FTS_SEARCH_SQL = """
    SELECT f.policy_id
    FROM policy_fts AS f
    INNER JOIN learning_policies AS p ON p.id = f.policy_id
    WHERE policy_fts MATCH jieba_query(?)
      AND p.scope_kind = ? AND p.scope_id = ?
      AND p.status IN ('CANDIDATE', 'SHADOW')
      AND p.updated_at_ms >= ?
      AND p.source_valid = 1 AND p.schema_valid = 1
      AND substr(p.applicable_model_identity_wire, 1, 9) = 'EXACT_V1:'
      AND substr(p.applicable_provider_identity_wire, 1, 9) = 'EXACT_V1:'
      AND p.applicable_template_identity IS NOT NULL
      AND p.applicable_configuration_identity IS NOT NULL
      AND p.applicable_configuration_generation > 0
      AND EXISTS (
          SELECT 1 FROM policy_evidence pe
          JOIN learning_episodes ep ON ep.id = pe.episode_id
          LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id
              AND l.lesson_version = pe.lesson_version
          LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id
              AND sv.replay_generation = ep.replay_generation
              AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id
              AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id
              AND sv.source_revision = pe.source_revision
          WHERE pe.policy_id = p.id
            AND ($VALID_POLICY_EVIDENCE_PREDICATE)
      )
      AND NOT EXISTS (
          SELECT 1 FROM policy_evidence pe
          JOIN learning_episodes ep ON ep.id = pe.episode_id
          LEFT JOIN learning_episode_lessons l ON l.episode_id = pe.episode_id
              AND l.lesson_version = pe.lesson_version
          LEFT JOIN learning_source_validity sv ON sv.stream_id = ep.stream_id
              AND sv.replay_generation = ep.replay_generation
              AND sv.scope_kind = ep.scope_kind AND sv.scope_id = ep.scope_id
              AND sv.source_type = pe.source_type AND sv.source_id = pe.source_id
              AND sv.source_revision = pe.source_revision
          WHERE pe.policy_id = p.id
            AND NOT ($VALID_POLICY_EVIDENCE_PREDICATE)
      )
    ORDER BY bm25(policy_fts), p.updated_at_ms DESC, p.id ASC
    LIMIT ?
"""

/** Called once when the derived database is opened; never placed on the retrieval latency path. */
internal fun ensurePolicyFtsSchema(db: SupportSQLiteDatabase) {
    db.execSQL(POLICY_FTS_CREATE_SQL.trimIndent())
    POLICY_FTS_TRIGGER_NAMES.forEach { name -> db.execSQL("DROP TRIGGER IF EXISTS $name") }
    POLICY_FTS_TRIGGER_SQL.forEach(db::execSQL)
    db.execSQL(POLICY_FTS_DELETE_ORPHANS_SQL.trimIndent())
    db.execSQL(POLICY_FTS_DELETE_INELIGIBLE_SQL.trimIndent())
    db.execSQL(POLICY_FTS_DELETE_DUPLICATES_SQL.trimIndent())
    db.execSQL(POLICY_FTS_BACKFILL_MISSING_SQL.trimIndent())
}

/**
 * Initializes the exact bundled SQLite/Jieba surface before installing the derived Policy FTS
 * projection. Both the production LearningRuntimeFacade and disposable-emulator evaluation call
 * this function; a test-only tokenizer substitute would not prove the production retrieval path.
 */
internal fun initializePolicyFtsRuntime(
    context: Context,
    db: SupportSQLiteDatabase,
) {
    val dictionary = SimpleDictManager.extractDict(context.applicationContext)
    db.query("SELECT jieba_dict(?)", arrayOf(dictionary.absolutePath)).use { cursor ->
        check(cursor.moveToFirst()) { "policy_fts_dictionary_result_missing" }
        check(
            cursor.getString(0)?.trimEnd('/') == dictionary.absolutePath.trimEnd('/'),
        ) { "policy_fts_dictionary_initialization_failed" }
    }
    ensurePolicyFtsSchema(db)
}

private val POLICY_FTS_TRIGGER_NAMES = listOf("policy_fts_ai", "policy_fts_au", "policy_fts_ad")

internal const val POLICY_FTS_DELETE_DUPLICATES_SQL = """
    DELETE FROM policy_fts
    WHERE rowid NOT IN (SELECT MIN(rowid) FROM policy_fts GROUP BY policy_id)
"""
