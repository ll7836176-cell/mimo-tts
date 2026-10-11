package com.github.jing332.tts_server_android.data.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.github.jing332.tts_server_android.data.AppDatabase
import com.github.jing332.tts_server_android.data.entities.systts.SystemTts
import com.github.jing332.tts_server_android.data.entities.systts.SystemTtsGroup
import com.github.jing332.tts_server_android.data.entities.replace.ReplaceRule
import com.github.jing332.tts_server_android.data.entities.replace.ReplaceRuleGroup
import com.github.jing332.tts_server_android.model.speech.tts.MsTTS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class SystemTtsDaoInteractionTest {
    private lateinit var database: AppDatabase

    @Before
    fun createDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.systemTtsDao.insertGroup(
            SystemTtsGroup(id = 1, name = "A"),
            SystemTtsGroup(id = 2, name = "B"),
        )
        database.systemTtsDao.insertTts(
            SystemTts(id = 11, groupId = 1, isEnabled = true, tts = MsTTS()),
            SystemTts(id = 12, groupId = 2, isEnabled = true, tts = MsTTS()),
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun unchecking_group_in_single_group_mode_keeps_other_groups_enabled() {
        database.systemTtsDao.setGroupEnabled(1, isEnabled = false, multipleGroupsEnabled = false)

        assertEquals(false, database.systemTtsDao.getTtsListByGroupId(1).single().isEnabled)
        assertEquals(true, database.systemTtsDao.getTtsListByGroupId(2).single().isEnabled)
    }

    @Test
    fun checking_group_in_single_group_mode_disables_the_other_groups() {
        database.systemTtsDao.setGroupEnabled(2, isEnabled = true, multipleGroupsEnabled = false)

        assertEquals(false, database.systemTtsDao.getTtsListByGroupId(1).single().isEnabled)
        assertEquals(true, database.systemTtsDao.getTtsListByGroupId(2).single().isEnabled)
    }

    @Test
    fun deleting_a_group_removes_its_entries_together() {
        database.systemTtsDao.deleteGroupAndTts(SystemTtsGroup(id = 1, name = "A"))

        assertEquals(null, database.systemTtsDao.getGroup(1))
        assertEquals(0, database.systemTtsDao.getTtsListByGroupId(1).size)
        assertEquals(1, database.systemTtsDao.getTtsListByGroupId(2).size)
    }

    @Test
    fun replacement_group_toggle_and_deletion_update_its_rules_together() {
        val group = ReplaceRuleGroup(id = 3, name = "replace")
        database.replaceRuleDao.insertGroup(group)
        database.replaceRuleDao.insert(
            ReplaceRule(id = 31, groupId = group.id, isEnabled = true),
            ReplaceRule(id = 32, groupId = group.id, isEnabled = true),
        )

        database.replaceRuleDao.setGroupEnabled(group.id, false)
        assertEquals(false, database.replaceRuleDao.getListInGroup(group.id).any { it.isEnabled })

        database.replaceRuleDao.deleteGroupAndRules(group)
        assertEquals(null, database.replaceRuleDao.getGroup(group.id))
        assertEquals(0, database.replaceRuleDao.getListInGroup(group.id).size)
    }
}
