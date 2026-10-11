package com.github.jing332.tts_server_android.utils

object FileMimeFilter {
    fun matches(acceptedTypes: List<String>, actualType: String?): Boolean {
        if (acceptedTypes.any { it.trim() == "*/*" }) return true
        val actual = actualType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        val actualParts = actual.split('/', limit = 2)
        if (actualParts.size != 2 || actualParts.any { it.isBlank() }) return false

        return acceptedTypes.any { acceptedType ->
            val accepted = acceptedType.substringBefore(';').trim().lowercase()
            val acceptedParts = accepted.split('/', limit = 2)
            acceptedParts.size == 2 &&
                acceptedParts[0] in setOf(actualParts[0], "*") &&
                acceptedParts[1] in setOf(actualParts[1], "*")
        }
    }
}
