package me.rerere.rikkahub.learning.curator

import java.security.MessageDigest

object CuratorV1Canonicalizer {
    fun fieldSha256(field: CuratorPolicyField, value: String): String = digest(
        domain = "curator-policy-field-v1",
        fields = listOf(field.name, value),
    )

    fun documentSha256(document: CuratorPolicyDocument): String = digest(
        domain = "curator-policy-document-v1",
        fields = listOf(
            document.trigger,
            document.procedure,
            document.verification,
            document.boundary,
            document.failureMode,
        ) + document.applicableToolSchemaSha256,
    )

    fun planId(
        candidate: CuratorDeltaCandidate,
        mutations: List<CuratorPlannedMutation>,
        lineage: List<CuratorLineageEdge>,
    ): String = "curator-plan-v1:" + digest(
        domain = "curator-delta-apply-plan-v1",
        fields = listOf(candidate.candidateId, candidate.operation.name) +
            candidate.sources.sortedBy(CuratorSourceFence::policyId).flatMap { source ->
                listOf(
                    source.policyId,
                    source.scope.kind.name,
                    source.scope.storageId,
                    source.expectedRevision.toString(),
                    source.baseHash,
                )
            } + candidate.evidence.sortedBy(CuratorEvidenceRef::evidenceId).flatMap { evidence ->
                listOf(
                    evidence.evidenceId,
                    evidence.scope.kind.name,
                    evidence.scope.storageId,
                    evidence.sourceRevision.toString(),
                    evidence.integritySha256,
                )
            } + candidate.diffs.sortedBy(CuratorTargetDiff::targetPolicyId).flatMap { target ->
                listOf(target.targetPolicyId) + target.fields.flatMap { diff ->
                    listOf(diff.field.name, diff.beforeSha256, diff.afterSha256)
                }
            } + mutations.flatMap(::mutationIdentity) +
            lineage.sortedWith(
                compareBy(CuratorLineageEdge::parentPolicyId)
                    .thenBy(CuratorLineageEdge::childPolicyId)
                    .thenBy { it.relation.ordinal },
            ).flatMap { edge ->
                listOf(edge.parentPolicyId, edge.childPolicyId, edge.relation.name)
            },
    )

    fun digest(domain: String, fields: List<String>): String {
        require(domain.isNotBlank())
        val digest = MessageDigest.getInstance("SHA-256")
        update(digest, domain)
        fields.forEach { update(digest, it) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun mutationIdentity(mutation: CuratorPlannedMutation): List<String> = listOf(
        mutation.kind.name,
        mutation.before?.policyId.orEmpty(),
        mutation.before?.revision?.toString().orEmpty(),
        mutation.before?.artifactSha256.orEmpty(),
        mutation.before?.state?.name.orEmpty(),
        mutation.before?.storageStateCode.orEmpty(),
        mutation.after?.policyId.orEmpty(),
        mutation.after?.revision?.toString().orEmpty(),
        mutation.after?.artifactSha256.orEmpty(),
        mutation.after?.state?.name.orEmpty(),
        mutation.after?.storageStateCode.orEmpty(),
    )

    private fun update(digest: MessageDigest, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
    }
}
