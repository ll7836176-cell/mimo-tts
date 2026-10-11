package com.github.jing332.tts_server_android.model.rhino.speech_rule

import android.content.Context
import com.github.jing332.tts_server_android.R
import com.github.jing332.tts_server_android.data.entities.SpeechRule
import com.github.jing332.tts_server_android.data.entities.TagsDataMap
import com.github.jing332.tts_server_android.data.entities.systts.SpeechRuleInfo
import com.github.jing332.tts_server_android.model.rhino.core.BaseScriptEngine
import com.github.jing332.tts_server_android.model.rhino.core.BaseScriptEngineContext
import com.github.jing332.tts_server_android.model.rhino.core.Logger
import com.github.jing332.tts_server_android.model.speech.RuleSegmentIdState
import com.github.jing332.tts_server_android.model.speech.TtsServerSafeErrorCode

class SpeechRuleEngine(
    val context: Context,
    private val rule: SpeechRule,
    override var code: String = rule.code,
    override val logger: Logger = Logger.global
) :
    BaseScriptEngine(ttsrvObject = BaseScriptEngineContext(context = context, "ReadRule")) {
    companion object {
        const val OBJ_JS = "SpeechRuleJS"

        const val FUNC_GET_TAG_NAME = "getTagName"
        const val FUNC_HANDLE_TEXT = "handleText"
        const val FUNC_SPLIT_TEXT = "splitText"

        private val PROFILE_KEYS = setOf("schemaVersion", "voice", "style")
    }

    private val objJS
        get() = findObject(OBJ_JS)

    @Suppress("UNCHECKED_CAST")
    fun evalInfo() {
        eval()
        objJS.apply {
            rule.name = get("name").toString()
            rule.ruleId = get("id").toString()
            rule.author = get("author").toString()

            rule.tags = get("tags") as Map<String, String>

            rule.tagsData =
                getOrDefault(
                    "tagsData",
                    emptyMap<String, Map<String, Map<String, String>>>()
                ) as TagsDataMap

            runCatching {
                rule.version = (get("version") as Double).toInt()
            }.onFailure {
                throw NumberFormatException(context.getString(R.string.plugin_bad_format))
            }
        }
    }

    fun getTagName(tag: String, tagMap: Map<String, String>): String {
        return rhino.invokeMethod(objJS, FUNC_GET_TAG_NAME, tag, tagMap).toString()
    }

    data class TagData(val id: String, val value: String)

    fun handleText(text: String, list: List<SpeechRuleInfo> = emptyList()): List<TextWithTag> {
        val tagsDataMap: MutableMap<String, MutableMap<String, MutableList<Map<String, String>>>> =
            mutableMapOf()
        list.forEach { info ->
            if (tagsDataMap[info.tag] == null)
                tagsDataMap[info.tag] = mutableMapOf()

            info.tagData.forEach {
                if (tagsDataMap[info.tag]!![it.key] == null)
                    tagsDataMap[info.tag]!![it.key] = mutableListOf()

                tagsDataMap[info.tag]!![it.key]!!.add(
                    mapOf(
                        "id" to info.configId.toString(),
                        "value" to it.value
                    )
                )
            }
        }
        return handleText(text, tagsDataMap)
    }

    /** ['dialogue']['role']= List<TagData>
     *@param tagsDataSet 例： key: dialogue, value: map(key: role, value: [{tagDataId:111, 张三, 李四])
     */
    fun handleText(
        text: String,
        tagsDataMap: Map<String, Map<String, List<Map<String, String>>>>
    ): List<TextWithTag> {
        val resultList: MutableList<TextWithTag> = mutableListOf()
        rhino.invokeMethod(objJS, FUNC_HANDLE_TEXT, text, tagsDataMap)
            ?.run { this as List<*> }
            ?.let { list ->
                list.forEach {
                    if (it is Map<*, *>) {
                        val parsedId = parseId(it)
                        val profileResult = parseProfile(it)
                        val instructionsResult = parseInstructions(it)
                        resultList.add(
                            TextWithTag(
                                it["text"].toString(),
                                it["tag"].toString(),
                                id = (parsedId.first as? RuleSegmentIdState.Present)?.value ?: 0L,
                                idState = parsedId.first,
                                mimoProfile = (profileResult as? ProfileParse.Success)?.profile,
                                profileError = (profileResult as? ProfileParse.Failure)?.code,
                                baseInstructions = (instructionsResult as? InstructionsParse.Success)?.value
                                    ?: emptyList(),
                                instructionsError = (instructionsResult as? InstructionsParse.Failure)?.code,
                            )
                        )
                    }
                }

            }
        return resultList
    }

    @Suppress("UNCHECKED_CAST")
    fun splitText(text: String): List<CharSequence> {
        return rhino.invokeMethod(
            objJS,
            FUNC_SPLIT_TEXT,
            text
        ) as List<CharSequence>
    }

    private fun parseId(value: Map<*, *>): Pair<RuleSegmentIdState, TtsServerSafeErrorCode?> {
        if (!value.containsKey("id")) return RuleSegmentIdState.Absent to null
        val raw = value["id"]
        val parsed = when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Short -> raw.toLong()
            is Byte -> raw.toLong()
            is Number -> {
                val number = raw.toDouble()
                if (number.isFinite() && number % 1.0 == 0.0) number.toLong() else null
            }
            is String -> raw.trim().toLongOrNull()
            else -> null
        }
        return if (parsed != null && parsed > 0) {
            RuleSegmentIdState.Present(parsed) to null
        } else {
            RuleSegmentIdState.Invalid() to TtsServerSafeErrorCode.TARGET_ID_INVALID
        }
    }

    private sealed interface ProfileParse {
        data class Success(val profile: SpeechRuleMiMoProfileParser.Profile) : ProfileParse
        data class Failure(val code: TtsServerSafeErrorCode) : ProfileParse
        object Absent : ProfileParse
    }

    private fun parseProfile(value: Map<*, *>): ProfileParse {
        val rawProfile = when {
            value.containsKey("profile") -> value["profile"]
            PROFILE_KEYS.any(value::containsKey) -> value.filterKeys { it in PROFILE_KEYS }
            else -> return ProfileParse.Absent
        }
        val map = rawProfile as? Map<*, *>
            ?: return ProfileParse.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        if (map.keys.any { it !is String }) {
            return ProfileParse.Failure(TtsServerSafeErrorCode.PARAM_UNKNOWN_KEY)
        }
        @Suppress("UNCHECKED_CAST")
        return when (val result = SpeechRuleMiMoProfileParser().parse(map as Map<String, Any?>)) {
            is SpeechRuleMiMoProfileParser.Result.Absent -> ProfileParse.Absent
            is SpeechRuleMiMoProfileParser.Result.Success -> ProfileParse.Success(result.profile)
            is SpeechRuleMiMoProfileParser.Result.Failure -> ProfileParse.Failure(result.code)
        }
    }

    private sealed interface InstructionsParse {
        data class Success(val value: List<String>) : InstructionsParse
        data class Failure(val code: TtsServerSafeErrorCode) : InstructionsParse
    }

    private fun parseInstructions(value: Map<*, *>): InstructionsParse {
        val raw = value["baseInstructions"] ?: value["instructions"] ?: return InstructionsParse.Success(emptyList())
        val list = raw as? List<*>
            ?: return InstructionsParse.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        if (list.any { it !is String }) {
            return InstructionsParse.Failure(TtsServerSafeErrorCode.PARAM_TYPE_UNSUPPORTED)
        }
        @Suppress("UNCHECKED_CAST")
        return InstructionsParse.Success((list as List<String>).toList())
    }

    data class TextWithTag(
        val text: String,
        val tag: String,
        val id: Long = 0L,
        val idState: RuleSegmentIdState = RuleSegmentIdState.Present(id),
        val mimoProfile: SpeechRuleMiMoProfileParser.Profile? = null,
        val profileError: TtsServerSafeErrorCode? = null,
        val baseInstructions: List<String> = emptyList(),
        val instructionsError: TtsServerSafeErrorCode? = null,
    )

}
