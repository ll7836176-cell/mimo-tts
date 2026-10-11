package com.github.jing332.tts_server_android.ui.systts.edit

object TtsTestTextPolicy {
    fun effectiveText(input: String, fallback: String): String =
        input.takeIf { it.isNotBlank() } ?: fallback
}
