package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.FieldState
import com.jingyue.mimotts.core.spi.MiMoSafeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class MiMoInstructionAssemblerTest {
    @Test
    fun `style is appended once and empty style is a no-op`() {
        val assembler = MiMoInstructionAssembler()

        val styled = assembler.assemble(listOf("base"), FieldState.Present("calm"))
        assertEquals(listOf("base", "calm"), styled.instructions)

        val empty = assembler.assemble(listOf("base"), FieldState.Present(""))
        assertEquals(listOf("base"), empty.instructions)
    }

    @Test
    fun `existing style is moved to the single appended position`() {
        val result = MiMoInstructionAssembler().assemble(
            listOf("calm", "base"),
            FieldState.Present("calm"),
        )

        assertEquals(listOf("base", "calm"), result.instructions)
    }

    @Test
    fun `invalid style is rejected before composition`() {
        val result = MiMoInstructionAssembler().assemble(
            emptyList(),
            FieldState.Invalid(MiMoSafeErrorCode.STYLE_INVALID),
        )

        assertEquals(MiMoSafeErrorCode.STYLE_INVALID, result.errorCode)
    }
}
