package me.rerere.rikkahub.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractionParserTest {
    @Test
    fun `versioned create proposal parses from a fenced model response`() {
        val parsed = MemoryExtractionParser().parse(
            """
            ```json
            {
              "version": 1,
              "proposals": [{
                "action": "create",
                "targetIds": [],
                "expectedRevisions": [],
                "title": "Coffee preference",
                "content": "The user consistently prefers sugar-free latte.",
                "kind": "preference",
                "tags": ["coffee", "preference"],
                "importance": 0.7,
                "confidence": 0.94,
                "expiresAtMs": null,
                "evidenceMessageIds": ["message-1"],
                "reason": "The user stated a durable preference."
              }]
            }
            ```
            """.trimIndent(),
        )

        assertTrue(parsed is MemoryExtractionParseResult.Success)
        val proposal = (parsed as MemoryExtractionParseResult.Success).envelope.proposals.single()
        assertEquals(MemoryCandidateAction.CREATE, proposal.action)
        assertEquals(MemoryKind.PREFERENCE, proposal.kind)
        assertEquals(listOf("message-1"), proposal.evidenceMessageIds)
        assertEquals(0.94f, proposal.confidence)
    }

    @Test
    fun `unknown explanatory fields do not discard a valid proposal`() {
        val parsed = MemoryExtractionParser().parse(
            """
            {
              "version": 1,
              "summary": "One durable preference was found.",
              "proposals": [{
                "action": "create",
                "targetIds": [],
                "expectedRevisions": [],
                "title": "Reply language",
                "content": "The user prefers concise replies in Chinese.",
                "kind": "preference",
                "tags": ["language"],
                "importance": 0.7,
                "confidence": 0.95,
                "expiresAtMs": null,
                "evidenceMessageIds": ["message-2"],
                "reason": "The user stated a stable preference.",
                "comment": "This field is explanatory only."
              }]
            }
            """.trimIndent(),
        )

        assertTrue(parsed is MemoryExtractionParseResult.Success)
        val proposal = (parsed as MemoryExtractionParseResult.Success).envelope.proposals.single()
        assertEquals("Reply language", proposal.title)
        assertEquals(listOf("message-2"), proposal.evidenceMessageIds)
    }

    @Test
    fun `unknown action remains invalid when explanatory fields are ignored`() {
        val parsed = MemoryExtractionParser().parse(
            """
            {
              "version": 1,
              "proposals": [{
                "action": "delete",
                "title": "Unsafe operation",
                "content": "A model must never create a delete operation.",
                "kind": "other",
                "confidence": 1.0
              }]
            }
            """.trimIndent(),
        )

        assertEquals(
            MemoryExtractionParseResult.Failure("memory_extraction_json_invalid"),
            parsed,
        )
    }

    @Test
    fun `missing required envelope fields remain invalid`() {
        listOf(
            """{"proposals":[]}""",
            """{"version":1}""",
        ).forEach { payload ->
            assertEquals(
                MemoryExtractionParseResult.Failure("memory_extraction_json_invalid"),
                MemoryExtractionParser().parse(payload),
            )
        }
    }

    @Test
    fun `unsupported schema version remains distinguishable`() {
        val parsed = MemoryExtractionParser().parse(
            """{"version":3,"proposals":[]}""",
        )

        assertEquals(
            MemoryExtractionParseResult.Failure("memory_extraction_version_unsupported"),
            parsed,
        )
    }

    @Test
    fun `version two parses a shared episode and its relation`() {
        val parsed = MemoryExtractionParser().parse(
            """
            {
              "version": 2,
              "proposals": [{
                "proposalKey": "p1",
                "action": "create",
                "title": "Memory V2 repair completed",
                "content": "The user and assistant completed the Memory V2 repair together.",
                "kind": "episode",
                "attribution": "shared",
                "truthStatus": "confirmed",
                "participants": ["USER", "ASSISTANT"],
                "outcome": "The automatic memory flow works again.",
                "importance": 0.8,
                "confidence": 0.96,
                "evidenceMessageIds": ["m1"]
              }],
              "relations": [{
                "sourceProposalKey": "p1",
                "targetMemoryId": 12,
                "type": "CORRECTS",
                "weight": 0.9,
                "description": "The repair corrects the former failure.",
                "evidenceMessageIds": ["m1"]
              }]
            }
            """.trimIndent(),
        )

        assertTrue(parsed is MemoryExtractionParseResult.Success)
        val envelope = (parsed as MemoryExtractionParseResult.Success).envelope
        assertEquals(MemoryKind.EPISODE, envelope.proposals.single().kind)
        assertEquals(MemoryAttribution.SHARED, envelope.proposals.single().attribution)
        assertEquals(MemoryTruthStatus.CONFIRMED, envelope.proposals.single().truthStatus)
        assertEquals(MemoryRelationType.CORRECTS, envelope.relations.single().type)
    }

    @Test
    fun `proposal with fabricated evidence is rejected before it can reach storage`() {
        val proposal = MemoryProposal(
            action = MemoryCandidateAction.CREATE,
            title = "Durable preference",
            content = "The user consistently prefers concise Chinese replies.",
            kind = MemoryKind.PREFERENCE,
            tags = listOf("style"),
            importance = 0.8f,
            confidence = 0.96f,
            evidenceMessageIds = listOf("fabricated-message"),
            reason = "Preference",
        )

        val result = MemoryProposalValidator().validate(
            envelope = MemoryExtractionEnvelope(version = 1, proposals = listOf(proposal)),
            context = MemoryProposalValidationContext(
                allowedEvidenceMessageIds = setOf("real-message"),
                visibleExistingMemories = emptyMap(),
            ),
        )

        assertTrue(result.accepted.isEmpty())
        assertEquals(MemoryProposalRejectionCode.INVALID_EVIDENCE, result.rejected.single().code)
    }
}
