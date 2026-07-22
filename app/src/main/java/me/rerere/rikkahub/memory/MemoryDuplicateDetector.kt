package me.rerere.rikkahub.memory

import java.security.MessageDigest
import java.text.Normalizer

class MemoryDuplicateDetector {
    fun assess(
        candidate: String,
        existing: List<String>,
    ): MemoryDuplicateAssessment {
        val candidateHash = memoryContentHash(candidate)
        if (existing.any { memoryContentHash(it) == candidateHash }) {
            return MemoryDuplicateAssessment.EXACT
        }

        val candidateTerms = duplicateTerms(candidate)
        if (candidateTerms.size < MIN_NEAR_TERMS) return MemoryDuplicateAssessment.NONE
        val near = existing.any { old ->
            val oldTerms = duplicateTerms(old)
            if (oldTerms.size < MIN_NEAR_TERMS) return@any false
            val overlap = candidateTerms.intersect(oldTerms).size
            overlap >= MIN_NEAR_OVERLAP &&
                (2.0 * overlap / (candidateTerms.size + oldTerms.size)) >= NEAR_DICE_THRESHOLD
        }
        return if (near) MemoryDuplicateAssessment.NEAR else MemoryDuplicateAssessment.NONE
    }
}

fun memoryContentHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(normalizeMemoryText(text).encodeToByteArray())
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

internal fun normalizeMemoryText(text: String): String = Normalizer
    .normalize(text, Normalizer.Form.NFKC)
    .lowercase()
    .trim()
    .replace(Regex("\\s+"), " ")

private fun duplicateTerms(text: String): Set<String> = buildSet {
    val normalized = normalizeMemoryText(text)
    Regex("[\\p{L}\\p{N}_]+").findAll(normalized).forEach { match ->
        val token = match.value
        if (token.any(::isHan)) {
            val hanRuns = Regex("\\p{IsHan}+").findAll(token).map { it.value }
            hanRuns.forEach { run ->
                if (run.length == 1) add(run)
                if (run.length >= 2) run.windowed(2).forEach(::add)
            }
            Regex("[a-z0-9_]+").findAll(token).map { it.value }.forEach(::add)
        } else {
            add(token)
        }
    }
}

private fun isHan(char: Char): Boolean =
    Character.UnicodeScript.of(char.code) == Character.UnicodeScript.HAN

private const val MIN_NEAR_TERMS = 3
private const val MIN_NEAR_OVERLAP = 3
private const val NEAR_DICE_THRESHOLD = 0.62
