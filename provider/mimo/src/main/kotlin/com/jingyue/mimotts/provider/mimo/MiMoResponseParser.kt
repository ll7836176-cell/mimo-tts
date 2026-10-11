package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import java.util.Base64

sealed interface MiMoResponseEvent {
    data class Audio(
        val bytes: ByteArray,
        val format: String,
        val sampleRate: Int,
        val channelCount: Int,
    ) : MiMoResponseEvent

    data object Done : MiMoResponseEvent

    data class Error(
        val message: String,
        val code: String = MiMoSafeErrorCode.RESPONSE_INVALID.wireValue,
    ) : MiMoResponseEvent
}

class MiMoResponseParser {
    fun parseSse(lines: Iterable<String>): List<MiMoResponseEvent> {
        val events = mutableListOf<MiMoResponseEvent>()
        val parser = newSseParser { event -> events += event }
        lines.forEach(parser::accept)
        parser.finish()
        return events
    }

    fun newSseParser(onEvent: (MiMoResponseEvent) -> Unit): SseParser =
        SseParser(onEvent)

    inner class SseParser internal constructor(
        private val onEvent: (MiMoResponseEvent) -> Unit,
    ) {
        private val dataLines = mutableListOf<String>()
        private var dataLength = 0

        fun accept(line: String) {
            val trimmed = line.trimEnd('\r')
            if (trimmed.isEmpty()) {
                flush()
                return
            }
            if (trimmed.startsWith(":")) return
            if (!trimmed.startsWith("data:")) return

            val data = trimmed.removePrefix("data:").trimStart()
            dataLength += data.length
            if (dataLength > MAX_SSE_DATA_CHARS) {
                dataLines.clear()
                dataLength = 0
                onEvent(MiMoResponseEvent.Error(MiMoSafeErrorCode.RESPONSE_INVALID.wireValue))
                return
            }
            dataLines += data
            if (data == "[DONE]" || isCompleteJson(dataLines.joinToString("\n"))) {
                flush()
            }
        }

        fun finish() {
            flush()
        }

        private fun flush() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n").trim()
            dataLines.clear()
            dataLength = 0
            if (data == "[DONE]") {
                onEvent(MiMoResponseEvent.Done)
            } else {
                parsePayload(data, allowMissingAudio = true)?.let(onEvent)
            }
        }

        private fun isCompleteJson(value: String): Boolean =
            runCatching { MiniJson.parse(value) }.isSuccess
    }

    fun parseBatch(json: String): List<MiMoResponseEvent> {
        if (json.isBlank()) return emptyList()
        return listOfNotNull(parsePayload(json, allowMissingAudio = false))
    }

    private fun parsePayload(json: String, allowMissingAudio: Boolean): MiMoResponseEvent? {
        return runCatching {
            val root = MiniJson.objectValue(MiniJson.parse(json)) ?: error("Response is not an object")
            val error = root["error"]
            if (error != null) return@runCatching safeError(MiMoSafeErrorCode.RESPONSE_INVALID)
            val audio = findAudio(root) ?: return@runCatching if (allowMissingAudio) {
                null
            } else {
                safeError(MiMoSafeErrorCode.RESPONSE_INVALID)
            }
            val encoded = when (audio) {
                is String -> audio
                is Map<*, *> -> audio["data"]?.toString().orEmpty()
                else -> ""
            }
            if (encoded.isBlank()) return@runCatching if (allowMissingAudio) {
                null
            } else {
                safeError(MiMoSafeErrorCode.AUDIO_EMPTY)
            }
            val audioMap = audio as? Map<*, *>
            val format = field(audioMap, "format") ?: field(root, "format") ?: "pcm16"
            val bytes = runCatching { decodeBase64(encoded) }.getOrElse {
                return@runCatching safeError(MiMoSafeErrorCode.AUDIO_DECODING)
            }
            if (bytes.isEmpty()) return@runCatching safeError(MiMoSafeErrorCode.AUDIO_EMPTY)
            MiMoResponseEvent.Audio(
                bytes = bytes,
                format = format,
                sampleRate = intField(audioMap, "sample_rate")
                    ?: intField(audioMap, "sampleRate")
                    ?: intField(root, "sample_rate")
                    ?: intField(root, "sampleRate")
                    ?: defaultSampleRate(format),
                channelCount = intField(audioMap, "channels")
                    ?: intField(root, "channels")
                    ?: 1,
            )
        }.getOrElse { safeError(MiMoSafeErrorCode.RESPONSE_INVALID) }
    }

    private fun findAudio(root: Map<String, Any?>): Any? {
        val direct = root["audio"]
        if (direct != null) return direct
        val choices = MiniJson.arrayValue(root["choices"]) ?: return null
        val choice = MiniJson.objectValue(choices.firstOrNull()) ?: return null
        val delta = MiniJson.objectValue(choice["delta"])
        if (delta?.containsKey("audio") == true) return delta["audio"]
        val message = MiniJson.objectValue(choice["message"])
        return message?.get("audio")
    }

    private fun safeError(code: MiMoSafeErrorCode): MiMoResponseEvent.Error =
        MiMoResponseEvent.Error(message = code.wireValue, code = code.wireValue)

    private fun decodeBase64(value: String): ByteArray {
        require(value.length <= MAX_AUDIO_BASE64_CHARS) { "audio payload is too large" }
        return Base64.getDecoder().decode(value)
    }

    private fun field(map: Map<*, *>?, key: String): String? = map?.get(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun intField(map: Map<*, *>?, key: String): Int? = map?.get(key)?.toString()?.toDoubleOrNull()?.toInt()

    private fun defaultSampleRate(format: String): Int = when (format.lowercase()) {
        "pcm", "pcm16", "raw_pcm" -> DEFAULT_PCM16_SAMPLE_RATE
        else -> 0
    }

    companion object {
        // MiMo documents streaming PCM16 output as 24 kHz, mono PCM16LE.
        const val DEFAULT_PCM16_SAMPLE_RATE = 24_000
        private const val MAX_AUDIO_BASE64_CHARS = 12 * 1024 * 1024
        private const val MAX_SSE_DATA_CHARS = 16 * 1024 * 1024
    }
}
