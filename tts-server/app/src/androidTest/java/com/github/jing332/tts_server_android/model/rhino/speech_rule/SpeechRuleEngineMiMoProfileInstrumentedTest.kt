package com.github.jing332.tts_server_android.model.rhino.speech_rule

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.jing332.tts_server_android.data.entities.SpeechRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechRuleEngineMiMoProfileInstrumentedTest {
    @Test
    fun packagedFixtureIsEvaluatedByRhinoAndPreservesProfile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val code = instrumentation.context.assets.open("speech_rule_fixtures/mimo_profile.js")
            .bufferedReader()
            .use { it.readText() }
        val rule = SpeechRule(code = code)
        val engine = SpeechRuleEngine(context, rule)

        engine.evalInfo()
        val segment = engine.handleText("测试文本").single()

        assertEquals("MiMo speech profile fixture", rule.name)
        assertEquals("tts-server.mimo-profile-fixture", rule.ruleId)
        assertNotNull(segment.mimoProfile)
        assertEquals("冰糖", segment.mimoProfile?.voice)
        assertEquals("温柔地朗读", segment.mimoProfile?.style)
        assertEquals(listOf("保持自然停顿"), segment.baseInstructions)
    }
}
