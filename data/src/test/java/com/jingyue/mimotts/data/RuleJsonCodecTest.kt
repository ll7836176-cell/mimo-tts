package com.jingyue.mimotts.data

import com.jingyue.mimotts.core.rules.LiteralReplaceRule
import com.jingyue.mimotts.core.rules.RuleSetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuleJsonCodecTest {
    @Test
    fun `replace rules round trip without secrets`() {
        val source = RuleSetSnapshot(
            id = "rules",
            version = 2,
            serverOwnsPreprocessing = false,
            rules = listOf(LiteralReplaceRule("r1", order = 1, from = "AI", to = "人工智能")),
        )

        val encoded = RuleJsonCodec.encode(source)
        val decoded = RuleJsonCodec.decode(encoded)

        assertEquals(source, decoded)
    }

    @Test
    fun `speech rule javascript is rejected`() {
        assertThrows(UnsupportedRuleImportException::class.java) {
            RuleJsonCodec.importTtsServerRules("{\"script\":\"Rhino.exec(...)\"}")
        }
    }
}
