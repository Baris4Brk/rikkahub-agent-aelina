package me.rerere.rikkahub.service.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandCompletionContractTest {
    @Test
    fun `wire names are owned by command authority and unknown future values fail closed`() {
        CommandCompletionKind.entries.forEach { kind ->
            assertEquals(kind, CommandCompletionKind.parseOrNull(kind.name))
        }
        assertNull(CommandCompletionKind.parseOrNull(null))
        assertNull(CommandCompletionKind.parseOrNull("GENERATION_SAVED_V9"))
        assertNull(CommandCompletionKind.parseOrNull("generation_final_saved"))
    }

    @Test
    fun `waiting requires exact assistant source and is not terminal`() {
        val result = message()
        val authority = CommandCompletionAuthority(
            kind = CommandCompletionKind.GENERATION_WAITING_APPROVAL,
            phase = CommandCompletionPhase.WAITING,
            commandState = DurableCommandState.WAITING_APPROVAL,
            resultMessage = result,
        )
        assertEquals(CommandCompletionKind.GENERATION_WAITING_APPROVAL, authority.kind)

        assertEquals(
            CommandCompletionViolation.RESULT_MESSAGE_REQUIRED,
            CommandCompletionContract.violationOrNull(
                kind = CommandCompletionKind.GENERATION_WAITING_APPROVAL,
                phase = CommandCompletionPhase.WAITING,
                commandState = DurableCommandState.WAITING_APPROVAL,
                resultMessage = null,
            ),
        )
        assertEquals(
            CommandCompletionViolation.WAITING_KIND_REQUIRES_WAITING_PHASE,
            CommandCompletionContract.violationOrNull(
                kind = CommandCompletionKind.GENERATION_WAITING_APPROVAL,
                phase = CommandCompletionPhase.TERMINAL,
                commandState = DurableCommandState.COMPLETED,
                resultMessage = result,
            ),
        )
    }

    @Test
    fun `only committed final and fast path may retain result pair`() {
        val result = message()
        CommandCompletionAuthority(
            kind = CommandCompletionKind.GENERATION_FINAL_SAVED,
            phase = CommandCompletionPhase.TERMINAL,
            commandState = DurableCommandState.COMPLETED,
            resultMessage = result,
        )
        CommandCompletionAuthority(
            kind = CommandCompletionKind.FAST_PATH_HANDLED,
            phase = CommandCompletionPhase.TERMINAL,
            commandState = DurableCommandState.COMPLETED,
            resultMessage = result,
        )
        assertEquals(
            CommandCompletionViolation.RESULT_MESSAGE_FORBIDDEN,
            CommandCompletionContract.violationOrNull(
                kind = CommandCompletionKind.FAILED_FINAL_SAVE,
                phase = CommandCompletionPhase.TERMINAL,
                commandState = DurableCommandState.FAILED,
                resultMessage = result,
            ),
        )
        assertEquals(
            CommandCompletionViolation.RESULT_MESSAGE_FORBIDDEN,
            CommandCompletionContract.violationOrNull(
                kind = CommandCompletionKind.CENSORED_CANCELLED,
                phase = CommandCompletionPhase.TERMINAL,
                commandState = DurableCommandState.CANCELLED,
                resultMessage = result,
            ),
        )
    }

    @Test
    fun `timestamps hashes and zero are not accepted as message revision`() {
        assertThrows(IllegalArgumentException::class.java) {
            CommandResultMessageAuthority(MESSAGE_ID, 0L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandResultMessageAuthority("$MESSAGE_ID#${"a".repeat(64)}", 1L)
        }
    }

    private fun message() = CommandResultMessageAuthority(MESSAGE_ID, 7L)

    private companion object {
        const val MESSAGE_ID = "00000000-0000-0000-0000-000000000101"
    }
}
