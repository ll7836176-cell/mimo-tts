package com.jingyue.mimotts.data

import com.jingyue.mimotts.core.rules.RuleSetSnapshot
import com.jingyue.mimotts.core.rules.RuleValidator
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class RuleFileStore(private val directory: File) {
    private val lock = Any()
    private var lastKnownGood: RuleSetSnapshot? = null

    fun load(fileName: String = DEFAULT_FILE_NAME): RuleSetSnapshot? = synchronized(lock) {
        val file = safeFile(fileName)
        val backup = backupFile(file)
        listOf(file, backup).firstNotNullOfOrNull { candidate ->
            runCatching { decodeAndValidate(candidate.readText()) }.getOrNull()
        }?.also { lastKnownGood = it } ?: lastKnownGood
    }

    fun save(snapshot: RuleSetSnapshot, fileName: String = DEFAULT_FILE_NAME) = synchronized(lock) {
        directory.mkdirs()
        val target = safeFile(fileName)
        val backup = backupFile(target)
        val temporary = File.createTempFile(".${target.name}.", ".tmp", directory)
        try {
            val encoded = RuleJsonCodec.encode(snapshot)
            temporary.writeText(encoded)
            decodeAndValidate(temporary.readText())
            if (target.exists()) {
                Files.copy(target.toPath(), backup.toPath(), REPLACE_EXISTING)
            }
            atomicReplace(temporary, target)
            lastKnownGood = snapshot
        } finally {
            temporary.delete()
        }
    }

    private fun safeFile(fileName: String): File {
        val name = File(fileName).name
        require(name == fileName && name.isNotBlank() && name != "." && name != "..") {
            "Invalid rule file name"
        }
        val root = directory.canonicalFile
        val file = File(root, name).canonicalFile
        require(file.parentFile == root) { "Rule file must stay inside its directory" }
        return file
    }

    private fun backupFile(target: File): File = File(target.parentFile, "${target.name}.bak")

    private fun decodeAndValidate(json: String): RuleSetSnapshot =
        RuleJsonCodec.decode(json).also { RuleValidator().requireValid(it) }

    private fun atomicReplace(source: File, target: File) {
        runCatching {
            Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), REPLACE_EXISTING)
        }
    }

    companion object {
        const val DEFAULT_FILE_NAME = "rules.json"
    }
}
