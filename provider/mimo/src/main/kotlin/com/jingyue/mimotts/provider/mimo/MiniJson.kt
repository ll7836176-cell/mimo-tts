package com.jingyue.mimotts.provider.mimo

/** Small dependency-free JSON reader/writer kept inside the provider boundary. */
internal object MiniJson {
    fun stringify(value: Any?): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            quote(key.toString()) + ":" + stringify(item)
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::stringify)
        else -> quote(value.toString())
    }

    fun parse(json: String): Any? = Parser(json).parse()

    @Suppress("UNCHECKED_CAST")
    fun objectValue(value: Any?): Map<String, Any?>? = value as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arrayValue(value: Any?): List<Any?>? = value as? List<Any?>

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private class Parser(private val source: String) {
        private var index = 0

        fun parse(): Any? {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            require(index == source.length) { "Unexpected JSON at $index" }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            require(index < source.length) { "Unexpected end of JSON" }
            return when (source[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return result
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                if (peek('}')) {
                    index++
                    return result
                }
                expect(',')
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return result
            }
            while (true) {
                result += parseValue()
                skipWhitespace()
                if (peek(']')) {
                    index++
                    return result
                }
                expect(',')
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (index < source.length) {
                when (val char = source[index++]) {
                    '"' -> return result.toString()
                    '\\' -> {
                        require(index < source.length) { "Invalid escape" }
                        when (val escaped = source[index++]) {
                            '"', '\\', '/' -> result.append(escaped)
                            'b' -> result.append('\b')
                            'f' -> result.append('\u000C')
                            'n' -> result.append('\n')
                            'r' -> result.append('\r')
                            't' -> result.append('\t')
                            'u' -> {
                                val hex = source.substring(index, index + 4)
                                result.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> error("Unsupported escape: $escaped")
                        }
                    }
                    else -> result.append(char)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseNumber(): Number {
            val start = index
            while (index < source.length && source[index] !in " \t\r\n,]}") index++
            val token = source.substring(start, index)
            return if (token.contains('.') || token.contains('e', true)) token.toDouble() else token.toLong()
        }

        private fun parseLiteral(token: String, value: Any?): Any? {
            require(source.startsWith(token, index)) { "Expected $token" }
            index += token.length
            return value
        }

        private fun skipWhitespace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun expect(char: Char) {
            skipWhitespace()
            require(index < source.length && source[index] == char) { "Expected $char at $index" }
            index++
        }

        private fun peek(char: Char): Boolean {
            skipWhitespace()
            return index < source.length && source[index] == char
        }
    }
}
