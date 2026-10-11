package com.jingyue.mimotts.provider.mimo

object MiMoTextChunker {
    fun split(text: String, maxLength: Int): List<String> {
        require(maxLength > 0) { "maxLength must be positive" }
        validateTags(text, maxLength)
        if (text.codePointCount(0, text.length) <= maxLength) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.codePointCount(start, text.length)
            if (remaining <= maxLength) {
                chunks += text.substring(start)
                break
            }
            val hardEnd = text.offsetByCodePoints(start, maxLength).coerceAtMost(text.length)
            val softStart = text.offsetByCodePoints(start, (maxLength * 0.55f).toInt().coerceAtLeast(1))
                .coerceAtMost(hardEnd)
            val candidate = text.substring(softStart, hardEnd)
            val boundary = candidate
                .indexOfLast { it in "。！？!?；;，,、\n " }
                .takeIf { it >= 0 }
                ?.let { softStart + it + 1 }
                ?: hardEnd
            val safeBoundary = avoidSplittingToken(text, start, boundary, hardEnd)
            chunks += text.substring(start, safeBoundary)
            start = safeBoundary
        }
        return chunks.filter { it.isNotEmpty() }
    }

    private fun validateTags(text: String, maxLength: Int) {
        Regex("\\[[^\\]\\r\\n]*]|<[^>\\r\\n]*>").findAll(text).forEach { match ->
            require(match.value.codePointCount(0, match.value.length) <= maxLength) {
                "Audio tag exceeds chunk limit"
            }
        }
    }

    private fun avoidSplittingToken(text: String, start: Int, boundary: Int, hardEnd: Int): Int {
        var safe = boundary.coerceIn(start + 1, hardEnd)
        if (safe < text.length && Character.isLowSurrogate(text[safe])) safe++
        while (safe < hardEnd && isCombining(text[safe])) safe++

        val openSquare = text.lastIndexOf('[', safe - 1)
        val closeSquare = text.lastIndexOf(']', safe - 1)
        if (openSquare > closeSquare) {
            val closing = text.indexOf(']', safe)
            if (closing >= 0 && closing + 1 <= hardEnd) {
                safe = closing + 1
            } else if (openSquare > start) {
                safe = openSquare
            }
        }
        val openAngle = text.lastIndexOf('<', safe - 1)
        val closeAngle = text.lastIndexOf('>', safe - 1)
        if (openAngle > closeAngle) {
            val closing = text.indexOf('>', safe)
            if (closing >= 0 && closing + 1 <= hardEnd) {
                safe = closing + 1
            } else if (openAngle > start) {
                safe = openAngle
            }
        }
        return safe.coerceAtMost(hardEnd)
    }

    private fun isCombining(value: Char): Boolean = when (Character.getType(value)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        -> true
        else -> false
    }
}
