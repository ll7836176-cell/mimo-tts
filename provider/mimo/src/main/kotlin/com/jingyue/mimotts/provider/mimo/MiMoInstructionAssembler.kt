package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import java.nio.charset.StandardCharsets

data class MiMoInstructionAssemblyResult(
    val instructions: List<String>,
    val errorCode: MiMoSafeErrorCode? = null,
) {
    val isSuccess: Boolean get() = errorCode == null
}

class MiMoInstructionAssembler {
    fun assemble(
        baseInstructions: List<String>,
        style: FieldState<String>,
    ): MiMoInstructionAssemblyResult {
        val base = baseInstructions.toList()
        return when (style) {
            FieldState.Absent -> MiMoInstructionAssemblyResult(base)
            is FieldState.Invalid -> MiMoInstructionAssemblyResult(emptyList(), style.code)
            is FieldState.Present -> {
                if (style.value.isEmpty()) return MiMoInstructionAssemblyResult(base)
                val length = style.value.toByteArray(StandardCharsets.UTF_8).size
                if (length > MAX_STYLE_UTF8_BYTES) {
                    MiMoInstructionAssemblyResult(emptyList(), MiMoSafeErrorCode.STYLE_TOO_LONG)
                } else {
                    MiMoInstructionAssemblyResult(base.filterNot { it == style.value } + style.value)
                }
            }
        }
    }

    companion object {
        const val MAX_STYLE_UTF8_BYTES = 1024
    }
}
