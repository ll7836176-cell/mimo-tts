package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import java.net.URI

data class MiMoSynthesisInput(
    val endpoint: String,
    val apiKey: String,
    val profile: MiMoVoiceProfile,
    val text: String,
    val instructions: List<String> = emptyList(),
    val styleInstruction: String? = null,
    val audioTags: List<String> = emptyList(),
    val optimizeTextPreview: Boolean = profile.optimizeTextPreview,
    val modelId: String = profile.modelId,
    val speechRate: Float = 1f,
    val pitch: Float = 1f,
    val volume: Float = 1f,
)

data class MiMoHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

class MiMoRequestComposer {
    fun compose(input: MiMoSynthesisInput): Result<MiMoHttpRequest> = runCatching {
        require(input.apiKey.isNotBlank()) { MiMoSafeErrorCode.AUTH_FAILURE.wireValue }
        val profileErrors = input.profile.validationErrors()
        require(profileErrors.isEmpty()) { MiMoSafeErrorCode.REQUEST_INVALID.wireValue }
        val model = MiMoModelProfiles.forModel(input.modelId)
            ?: MiMoModelProfiles.forModel(input.profile.modelId)
            ?: error(MiMoSafeErrorCode.REQUEST_INVALID.wireValue)
        require(input.profile.kind in model.descriptor.supportedVoiceKinds) {
            MiMoSafeErrorCode.REQUEST_INVALID.wireValue
        }
        require(input.text.isNotBlank() || input.optimizeTextPreview) {
            MiMoSafeErrorCode.REQUEST_INVALID.wireValue
        }

        val assembledInstructions = MiMoInstructionAssembler().assemble(
            input.instructions,
            input.styleInstruction?.let { FieldState.Present(it) } ?: FieldState.Absent,
        )
        require(assembledInstructions.isSuccess) { assembledInstructions.errorCode!!.wireValue }

        val userBlocks = mutableListOf<String>()
        if (!input.profile.voiceDescription.isNullOrBlank()) {
            userBlocks += "Voice description: ${input.profile.voiceDescription}"
        }
        userBlocks += assembledInstructions.instructions.filter { it.isNotBlank() }
        val messages = mutableListOf<Map<String, Any?>>()
        if (userBlocks.isNotEmpty()) {
            messages += linkedMapOf("role" to "user", "content" to userBlocks.joinToString("\n"))
        }
        val assistantText = if (input.audioTags.isEmpty() || input.text.startsWith(input.audioTags.joinToString(""))) {
            input.text
        } else {
            input.audioTags.joinToString("") + input.text
        }
        if (assistantText.isNotBlank()) {
            messages += linkedMapOf("role" to "assistant", "content" to assistantText)
        }

        val audio = linkedMapOf<String, Any?>("format" to "pcm16")
        when (input.profile.kind) {
            com.jingyue.mimotts.core.spi.VoiceKind.BUILT_IN -> audio["voice"] = input.profile.voiceId
            com.jingyue.mimotts.core.spi.VoiceKind.CLONE -> audio["voice"] = input.profile.sample?.dataUrl()
            com.jingyue.mimotts.core.spi.VoiceKind.DESIGN -> Unit
        }
        val body = linkedMapOf<String, Any?>(
            "model" to input.modelId,
            "messages" to messages,
            "audio" to audio,
            "stream" to (input.profile.kind == com.jingyue.mimotts.core.spi.VoiceKind.BUILT_IN),
        )
        MiMoHttpRequest(
            url = normalizeEndpoint(input.endpoint),
            headers = mapOf(
                "api-key" to input.apiKey,
                "Content-Type" to "application/json",
                "Accept" to if (input.profile.kind == com.jingyue.mimotts.core.spi.VoiceKind.BUILT_IN) {
                    "text/event-stream"
                } else {
                    "application/json"
                },
            ),
            body = MiniJson.stringify(body),
        )
    }

    private fun normalizeEndpoint(endpoint: String): String {
        val uri = runCatching { URI(endpoint.trim()) }
            .getOrElse { error(MiMoSafeErrorCode.REQUEST_INVALID.wireValue) }
        require(uri.scheme.equals("https", ignoreCase = true)) { MiMoSafeErrorCode.REQUEST_INVALID.wireValue }
        val path = uri.path.trimEnd('/')
        val normalizedPath = when {
            path.endsWith("/v1/chat/completions") -> path
            path.endsWith("/v1") -> "$path/chat/completions"
            path.isBlank() -> "/v1/chat/completions"
            else -> "$path/v1/chat/completions"
        }
        return URI(uri.scheme, uri.authority, normalizedPath, uri.query, uri.fragment).toString()
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.xiaomimimo.com/v1/chat/completions"
    }
}
