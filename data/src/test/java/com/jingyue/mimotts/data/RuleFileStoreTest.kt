package com.jingyue.mimotts.data

import com.jingyue.mimotts.core.rules.LiteralReplaceRule
import com.jingyue.mimotts.core.rules.RuleSetSnapshot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleFileStoreTest {
    @Test
    fun `corrupt primary file falls back to the last atomic backup`() {
        val directory = File.createTempFile("mimo-rules", "").apply {
            delete()
            mkdirs()
        }
        try {
            val first = RuleSetSnapshot(
                id = "first",
                version = 1,
                serverOwnsPreprocessing = false,
                rules = listOf(LiteralReplaceRule("r1", 1, "AI", "人工智能")),
            )
            val second = first.copy(id = "second", version = 2)
            val store = RuleFileStore(directory)

            store.save(first)
            store.save(second)
            File(directory, RuleFileStore.DEFAULT_FILE_NAME).writeText("not-json")

            assertEquals(first, store.load())
        } finally {
            directory.deleteRecursively()
        }
    }
}
