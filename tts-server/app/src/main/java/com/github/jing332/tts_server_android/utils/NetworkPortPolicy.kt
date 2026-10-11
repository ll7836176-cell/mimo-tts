package com.github.jing332.tts_server_android.utils

object NetworkPortPolicy {
    fun parse(value: String?): Int? {
        val port = value?.trim()?.toIntOrNull() ?: return null
        return port.takeIf { it in 1..65_535 }
    }
}
