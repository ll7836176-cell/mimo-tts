package com.github.jing332.tts_server_android.utils

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipUtilsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `unzip rejects entries that escape destination`() = runBlocking {
        val root = temporaryFolder.newFolder("restore")
        val outside = File(temporaryFolder.root, "escaped.txt")

        try {
            ZipUtils.unzipFile(zipInput("../escaped.txt" to "outside"), root)
            throw AssertionError("Expected zip traversal entry to be rejected")
        } catch (_: IOException) {
            assertFalse(outside.exists())
        }
    }

    @Test
    fun `unzip limits entry count and expanded bytes`() = runBlocking {
        val root = temporaryFolder.newFolder("restore")

        assertRejected { ZipUtils.unzipFile(zipInput("one" to "1", "two" to "2"), root, maxEntries = 1) }
        assertRejected { ZipUtils.unzipFile(zipInput("large" to "12345"), root, maxExpandedBytes = 4) }
    }

    @Test
    fun `unzip preserves files that stay inside destination`() = runBlocking {
        val root = temporaryFolder.newFolder("restore")

        ZipUtils.unzipFile(zipInput("shared_prefs/config.xml" to "saved"), root)

        assertEquals("saved", File(root, "shared_prefs/config.xml").readText())
    }

    private fun assertRejected(block: suspend () -> Unit) {
        try {
            runBlocking { block() }
            fail("Expected zip limit to be enforced")
        } catch (_: IOException) {
            // Rejection is the expected outcome.
        }
    }

    private fun zipInput(vararg entries: Pair<String, String>) =
        java.util.zip.ZipInputStream(ByteArrayInputStream(buildZip(*entries)))

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
