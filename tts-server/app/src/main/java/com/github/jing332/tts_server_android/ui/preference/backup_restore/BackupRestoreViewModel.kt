package com.github.jing332.tts_server_android.ui.preference.backup_restore

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cn.hutool.core.io.FileUtil
import cn.hutool.core.util.ZipUtil
import com.drake.net.utils.withIO
import com.github.jing332.tts_server_android.constant.AppConst
import com.github.jing332.tts_server_android.data.appDb
import com.github.jing332.tts_server_android.data.entities.SpeechRule
import com.github.jing332.tts_server_android.data.entities.plugin.Plugin
import com.github.jing332.tts_server_android.data.entities.replace.GroupWithReplaceRule
import com.github.jing332.tts_server_android.data.entities.systts.GroupWithSystemTts
import com.github.jing332.tts_server_android.utils.FileUtils
import com.github.jing332.tts_server_android.utils.ZipUtils
import com.github.jing332.tts_server_android.service.systts.config.RuntimeConfigWriter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipInputStream

class BackupRestoreViewModel(application: Application) : AndroidViewModel(application) {
    // ... /cache/backupRestore
    private val backupRestorePath by lazy {
        application.externalCacheDir!!.absolutePath + File.separator + "backupRestore"
    }

    // /data/data/{package name}
    private val internalDataFile by lazy {
        application.filesDir!!.parentFile!!
    }

    suspend fun restore(bytes: ByteArray): Boolean {
        val backupRestoreDir = File(backupRestorePath)
        if (!backupRestoreDir.exists() && !backupRestoreDir.mkdirs()) {
            throw IllegalStateException("Unable to create restore directory")
        }
        val outFileDir = File(backupRestoreDir, "restore-${UUID.randomUUID()}")
        if (!outFileDir.mkdirs()) {
            throw IllegalStateException("Unable to create temporary restore directory")
        }

        try {
            ZipUtils.unzipFile(ZipInputStream(ByteArrayInputStream(bytes)), outFileDir)

            // Decode every database payload before changing any durable state.
            val files = outFileDir.listFiles().orEmpty().filter { it.isFile }
            val operations = files.mapNotNull(::decodeRestoreOperation)
            val restorePrefsFile = File(outFileDir, "shared_prefs")
            if (operations.isEmpty() && !restorePrefsFile.isDirectory) {
                throw IllegalArgumentException("Backup contains no supported data")
            }

            if (operations.isNotEmpty() && !RuntimeConfigWriter.publish {
                    operations.forEach { it() }
                }
            ) {
                throw IllegalStateException("runtime_publish_failed")
            }

            // shared_prefs is copied only after the Room transaction commits.
            if (restorePrefsFile.isDirectory) {
                FileUtils.copyFolder(restorePrefsFile, internalDataFile)
            }

            return restorePrefsFile.isDirectory
        } finally {
            outFileDir.deleteRecursively()
        }
    }

    private fun decodeRestoreOperation(file: File): (() -> Unit)? {
        val jsonStr = file.readText()
        return when {
            file.name.endsWith("list.json") -> {
                val list: List<GroupWithSystemTts> = AppConst.jsonBuilder.decodeFromString(jsonStr)
                val operation: () -> Unit = { appDb.systemTtsDao.insertGroupWithTts(*list.toTypedArray()) }
                operation
            }

            file.name.endsWith("speechRules.json") -> {
                val list: List<SpeechRule> = AppConst.jsonBuilder.decodeFromString(jsonStr)
                val operation: () -> Unit = { appDb.speechRule.restoreFromBackup(*list.toTypedArray()) }
                operation
            }

            file.name.endsWith("replaceRules.json") -> {
                val list: List<GroupWithReplaceRule> =
                    AppConst.jsonBuilder.decodeFromString(jsonStr)
                val operation: () -> Unit = { appDb.replaceRuleDao.insertRuleWithGroup(*list.toTypedArray()) }
                operation
            }

            file.name.endsWith("plugins.json") -> {
                val list: List<Plugin> = AppConst.jsonBuilder.decodeFromString(jsonStr)
                val operation: () -> Unit = { appDb.pluginDao.restoreFromBackup(*list.toTypedArray()) }
                operation
            }

            else -> null
        }
    }

    suspend fun backup(_types: List<Type>): ByteArray = withIO {
        File(tmpZipPath).deleteRecursively()
        File(tmpZipPath).mkdirs()

        val types = _types.toMutableList()
        if (types.contains(Type.PluginVars)) types.remove(Type.Plugin)
        types.forEach {
            createConfigFile(it)
        }

        val zipFile = File(tmpZipFile)
        ZipUtils.zipFolder(File(tmpZipPath), zipFile)
        return@withIO zipFile.readBytes()
    }

    override fun onCleared() {
        super.onCleared()
        File(backupRestorePath).deleteRecursively()
    }

    // ... /cache/backupRestore/backup
    private val tmpZipPath by lazy {
        backupRestorePath + File.separator + "backup"
    }

    private val tmpZipFile by lazy {
        backupRestorePath + File.separator + "backup.zip"
    }

    private fun createConfigFile(type: Type) {
        when (type) {
            is Type.Preference -> {
                val folder = internalDataFile.absolutePath + File.separator + "shared_prefs"
                FileUtils.copyFilesFromDir(
                    File(folder),
                    File(tmpZipPath + File.separator + "shared_prefs"),
                )
            }

            is Type.List -> {
                encodeJsonAndCopyToTmpZipPath(appDb.systemTtsDao.getSysTtsWithGroups(), "list")
            }

            is Type.SpeechRule -> {
                encodeJsonAndCopyToTmpZipPath(appDb.speechRule.all, "speechRules")
            }

            is Type.ReplaceRule -> {
                encodeJsonAndCopyToTmpZipPath(
                    appDb.replaceRuleDao.allGroupWithReplaceRules(),
                    "replaceRules"
                )
            }

            is Type.IPlugin -> {
                if (type.includeVars) {
                    encodeJsonAndCopyToTmpZipPath(appDb.pluginDao.all, "plugins")
                } else {
                    encodeJsonAndCopyToTmpZipPath(appDb.pluginDao.all.map {
                        it.userVars = mutableMapOf()
                        it
                    }, "plugins")
                }
            }
        }
    }

    private inline fun <reified T> encodeJsonAndCopyToTmpZipPath(v: T, name: String) {
        val s = AppConst.jsonBuilder.encodeToString(v)
        File(tmpZipPath + File.separator + name + ".json").writeText(s)
    }
}
