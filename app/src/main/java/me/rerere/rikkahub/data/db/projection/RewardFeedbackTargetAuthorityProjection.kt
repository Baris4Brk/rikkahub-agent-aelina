package me.rerere.rikkahub.data.db.projection

/** Content-free command projection used only to bind explicit feedback to exact authorities. */
data class RewardFeedbackTargetAuthorityProjection(
    val commandId: String,
    val state: String,
    val stateVersion: Long,
    val conversationId: String,
    val authoritySubjectId: String?,
    val assistantIdSnapshot: String?,
    val lineageId: String?,
    val branchAnchorMessageId: String?,
    val branchAnchorMessageRevision: Long?,
    val conversationSourceRevision: Long?,
    val completionKind: String?,
    val resultAssistantMessageId: String?,
    val resultAssistantMessageRevision: Long?,
) {
    override fun toString(): String =
        "RewardFeedbackTargetAuthorityProjection(state=$state, revision=$stateVersion, " +
            "scope=${authoritySubjectId != null}, ids=<redacted>)"
}
