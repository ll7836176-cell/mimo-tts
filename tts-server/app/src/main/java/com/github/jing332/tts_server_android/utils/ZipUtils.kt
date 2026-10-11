package com.github.jing332.tts_server_android.utils

import kotlinx.coroutines.isActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext


object ZipUtils {
    const val TAG = "ZipUtils"
    private const val MAX_UNZIP_ENTRIES = 10_000
    private const val MAX_UNZIP_BYTES = 128L * 1024L * 1024L

    suspend fun zipFolder(sourceFolder: File, zipFile: File) {
        zipFile.delete()
        zipFile.parentFile?.mkdirs()
        zipFile.createNewFile()

        val fos = FileOutputStream(zipFile)
        val zos = ZipOutputStream(BufferedOutputStream(fos))

        zos.setLevel(Deflater.DEFAULT_COMPRESSION)
        zipFolder(sourceFolder, zos, "")
        zos.closeEntry()
        zos.close()
    }

    @Throws(IOException::class)
    private suspend fun zipFolder(folder: File, zos: ZipOutputStream, parentPath: String) {
        val files = folder.listFiles()
        val buffer = ByteArray(4096)
        var bytesRead: Int

        for (file in files!!) {
            if (!coroutineContext.isActive) break

            if (file.isDirectory) {
                zipFolder(file, zos, parentPath + file.name + "/")
            } else {
                val fis = FileInputStream(file)
                val bis = BufferedInputStream(fis)
                val entryPath = parentPath + file.name
                val entry = ZipEntry(entryPath)
                zos.putNextEntry(entry)
                while (bis.read(buffer).also { bytesRead = it } != -1) {
                    if (!coroutineContext.isActive) break
                    zos.write(buffer, 0, bytesRead)
                }
                bis.close()
                fis.close()
            }
        }
    }

    suspend fun unzipFile(
        zis: ZipInputStream,
        destFolder: File,
        maxEntries: Int = MAX_UNZIP_ENTRIES,
        maxExpandedBytes: Long = MAX_UNZIP_BYTES,
    ) {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxExpandedBytes > 0) { "maxExpandedBytes must be positive" }

        if (!destFolder.exists() && !destFolder.mkdirs()) {
            throw IOException("Unable to create restore directory")
        }

        val root = destFolder.canonicalFile
        val buffer = ByteArray(4096)
        var entryCount = 0
        var expandedBytes = 0L

        zis.use { zip ->
            var entry = zip.nextEntry
            while (coroutineContext.isActive && entry != null) {
                entryCount += 1
                if (entryCount > maxEntries) {
                    throw IOException("Archive contains too many entries")
                }

                val file = resolveZipEntry(root, entry.name)
                if (entry.isDirectory) {
                    if (!file.exists() && !file.mkdirs()) {
                        throw IOException("Unable to create archive directory")
                    }
                } else {
                    file.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs()) {
                            throw IOException("Unable to create archive directory")
                        }
                    }

                    BufferedOutputStream(FileOutputStream(file)).use { output ->
                        while (true) {
                            val bytesRead = zip.read(buffer)
                            if (bytesRead == -1) break
                            if (bytesRead.toLong() > maxExpandedBytes - expandedBytes) {
                                throw IOException("Archive expands beyond the allowed size")
                            }
                            expandedBytes += bytesRead
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            if (!coroutineContext.isActive) throw CancellationException()
        }
    }

    internal fun resolveZipEntry(root: File, entryName: String): File {
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, entryName).canonicalFile
        val rootPath = canonicalRoot.path
        val targetPath = target.path
        val rootPrefix = rootPath.trimEnd(File.separatorChar) + File.separator

        if (!targetPath.startsWith(rootPrefix, ignoreCase = File.separatorChar == '\\')) {
            throw IOException("Archive entry escapes the restore directory")
        }

        return target
    }
}
