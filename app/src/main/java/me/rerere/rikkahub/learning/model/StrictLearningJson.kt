package me.rerere.rikkahub.learning.model

/** Duplicate-key preflight shared by every Learning model-output parser. */
internal object StrictLearningJsonKeyScanner {
    enum class Result { VALID, DUPLICATE, INVALID }

    fun scan(raw: String): Result = try {
        Cursor(raw).document()
        Result.VALID
    } catch (_: DuplicateKey) {
        Result.DUPLICATE
    } catch (_: InvalidJson) {
        Result.INVALID
    }

    private class Cursor(private val raw: String) {
        private var index = 0

        fun document() {
            whitespace()
            value()
            whitespace()
            if (index != raw.length) invalid()
        }

        private fun value() {
            if (index >= raw.length) invalid()
            when (raw[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalid()
            }
        }

        private fun objectValue() {
            expect('{')
            whitespace()
            if (take('}')) return
            val keys = hashSetOf<String>()
            while (true) {
                whitespace()
                val key = stringValue()
                if (!keys.add(key)) throw DuplicateKey()
                whitespace()
                expect(':')
                whitespace()
                value()
                whitespace()
                if (take('}')) return
                expect(',')
            }
        }

        private fun arrayValue() {
            expect('[')
            whitespace()
            if (take(']')) return
            while (true) {
                value()
                whitespace()
                if (take(']')) return
                expect(',')
                whitespace()
            }
        }

        private fun stringValue(): String {
            expect('"')
            val value = StringBuilder()
            while (index < raw.length) {
                val char = raw[index++]
                when {
                    char == '"' -> return value.toString()
                    char == '\\' -> value.append(escape())
                    char.code < 0x20 -> invalid()
                    else -> value.append(char)
                }
            }
            invalid()
        }

        private fun escape(): Char {
            if (index >= raw.length) invalid()
            return when (val escaped = raw[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> unicodeEscape()
                else -> invalid()
            }
        }

        private fun unicodeEscape(): Char {
            if (index + 4 > raw.length) invalid()
            var value = 0
            repeat(4) {
                value = value * 16 + raw[index++].digitToIntOrNull(16).orInvalid()
            }
            return value.toChar()
        }

        private fun number() {
            take('-')
            if (take('0')) {
                if (peekDigit()) invalid()
            } else {
                requireDigits()
            }
            if (take('.')) requireDigits()
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                requireDigits()
            }
        }

        private fun requireDigits() {
            if (!peekDigit()) invalid()
            while (peekDigit()) index += 1
        }

        private fun peekDigit(): Boolean = index < raw.length && raw[index] in '0'..'9'

        private fun literal(expected: String) {
            if (!raw.regionMatches(index, expected, 0, expected.length)) invalid()
            index += expected.length
        }

        private fun whitespace() {
            while (index < raw.length && raw[index] in charArrayOf(' ', '\t', '\r', '\n')) index += 1
        }

        private fun take(char: Char): Boolean = if (index < raw.length && raw[index] == char) {
            index += 1
            true
        } else {
            false
        }

        private fun expect(char: Char) {
            if (!take(char)) invalid()
        }

        private fun Int?.orInvalid(): Int = this ?: invalid()
        private fun invalid(): Nothing = throw InvalidJson()
    }

    private class DuplicateKey : RuntimeException()
    private class InvalidJson : RuntimeException()
}
