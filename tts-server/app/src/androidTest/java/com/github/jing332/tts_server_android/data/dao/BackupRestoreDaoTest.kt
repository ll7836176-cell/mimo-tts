package com.github.jing332.tts_server_android.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.jing332.tts_server_android.data.AppDatabase
import com.github.jing332.tts_server_android.data.entities.SpeechRule
import com.github.jing332.tts_server_android.data.entities.plugin.Plugin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupRestoreDaoTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun plugin_restore_overwrites_same_version_and_preserves_the_database_row() {
        database.pluginDao.insert(Plugin(pluginId = "voice", version = 2, userVars = mapOf("key" to "new")))
        val originalId = database.pluginDao.getByPluginId("voice")!!.id

        database.pluginDao.restoreFromBackup(
            Plugin(pluginId = "voice", version = 2, userVars = mapOf("key" to "backup")),
        )

        val restored = database.pluginDao.getByPluginId("voice")!!
        assertEquals(originalId, restored.id)
        assertEquals("backup", restored.userVars["key"])
    }

    @Test
    fun speech_rule_restore_overwrites_disabled_rules_with_the_same_version() {
        database.speechRule.insert(
            SpeechRule(
                isEnabled = false,
                name = "Current",
                version = 1,
                ruleId = "rules",
                code = "current",
            ),
        )

        database.speechRule.restoreFromBackup(
            SpeechRule(
                isEnabled = true,
                name = "Backup",
                version = 1,
                ruleId = "rules",
                code = "backup",
            ),
        )

        val restored = database.speechRule.getByRuleIdForRestore("rules")!!
        assertTrue(restored.isEnabled)
        assertEquals("backup", restored.code)
        assertFalse(restored.name == "Current")
    }
}
