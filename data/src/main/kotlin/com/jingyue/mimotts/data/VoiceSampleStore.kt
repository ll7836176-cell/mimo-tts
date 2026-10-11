package com.jingyue.mimotts.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest

data class StoredVoiceSample(
    val profileId: String,
    val file: File,
    val mimeType: String,
    val byteCount: Long,
    val sha256: String,
)

data class ImportedVoiceSample(
    val mimeType: String,
    val bytes: ByteArray,
)

class VoiceSampleStore(private val context: Context) {
    private val directory: File by lazy { File(context.filesDir, "voice_samples").apply { mkdirs() } }

    fun importFromUri(profileId: String, uri: Uri, mimeType: String): StoredVoiceSample {
        require(profileId.isNotBlank()) { "Profile id is required" }
        val target = File(directory, safeName(profileId))
        val temporary = File.createTempFile(".${target.name}.", ".tmp", directory)
        try {
            val normalizedMime = mimeType.substringBefore(';').trim().lowercase()
            require(normalizedMime in SUPPORTED_MIME_TYPES) { "Sample must be MP3 or WAV" }
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected sample" }
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE_BYTES) { "Voice sample is too large" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            val sampleBytes = temporary.readBytes()
            require(sampleBytes.isNotEmpty()) { "Sample is empty" }
            require(hasExpectedHeader(sampleBytes, normalizedMime)) { "Sample format does not match its MIME type" }
            require(((sampleBytes.size + 2) / 3) * 4 <= MAX_BASE64_BYTES) {
                "Base64 sample must not exceed 10 MB"
            }
            atomicReplace(temporary, target)
            return StoredVoiceSample(profileId, target, normalizedMime, target.length(), sha256(target))
        } finally {
            temporary.delete()
        }
    }

    fun open(profileId: String): File? = sequenceOf(
        File(directory, safeName(profileId)),
        File(directory, legacySafeName(profileId)),
    ).firstOrNull(File::exists)

    fun delete(profileId: String) {
        File(directory, safeName(profileId)).delete()
        File(directory, legacySafeName(profileId)).delete()
    }

    private fun safeName(profileId: String): String = "${sha256(profileId.toByteArray(Charsets.UTF_8))}.sample"

    private fun legacySafeName(profileId: String): String =
        profileId.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".sample"

    private fun atomicReplace(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    private fun hasExpectedHeader(bytes: ByteArray, mimeType: String): Boolean = when (mimeType) {
        "audio/wav", "audio/x-wav" -> bytes.size >= 12 &&
            bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())
        "audio/mpeg", "audio/mp3" -> bytes.size >= 3 && (
            bytes.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) ||
                (bytes[0].toInt() and 0xff == 0xff && bytes[1].toInt() and 0xe0 == 0xe0)
            )
        else -> false
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val SUPPORTED_MIME_TYPES = setOf("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav")
        private const val MAX_SOURCE_BYTES = 8 * 1024 * 1024L
        private const val MAX_BASE64_BYTES = 10 * 1024 * 1024
    }
}
