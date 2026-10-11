package com.jingyue.mimotts.data

import com.jingyue.mimotts.core.rules.AudioTagRule
import com.jingyue.mimotts.core.rules.ChunkRule
import com.jingyue.mimotts.core.rules.InstructionRule
import com.jingyue.mimotts.core.rules.LiteralReplaceRule
import com.jingyue.mimotts.core.rules.ProfileRoutingRule
import com.jingyue.mimotts.core.rules.RegexReplaceRule
import com.jingyue.mimotts.core.rules.RuleScope
import com.jingyue.mimotts.core.rules.RuleSetSnapshot
import com.jingyue.mimotts.core.rules.SegmentRule
import com.jingyue.mimotts.core.rules.TagPosition
import com.jingyue.mimotts.core.rules.TtsRule

class UnsupportedRuleImportException(message: String) : IllegalArgumentException(message)

object RuleJsonCodec {
    fun encode(ruleSet: RuleSetSnapshot): String = JsonSupport.stringify(
        linkedMapOf(
            "schemaVersion" to 1,
            "id" to ruleSet.id,
            "version" to ruleSet.version,
            "serverOwnsPreprocessing" to ruleSet.serverOwnsPreprocessing,
            "rules" to ruleSet.rules.map(::encodeRule),
        ),
    )

    fun decode(json: String): RuleSetSnapshot {
        val root = JsonSupport.objectValue(JsonSupport.parse(json)) ?: error("Rule export must be a JSON object")
        val rules = JsonSupport.arrayValue(root["rules"]).orEmpty().mapNotNull { decodeRule(JsonSupport.objectValue(it)) }
        return RuleSetSnapshot(
            id = root.string("id") ?: error("Rule set id is required"),
            version = root.long("version") ?: 1,
            serverOwnsPreprocessing = root.boolean("serverOwnsPreprocessing") ?: true,
            rules = rules,
        )
    }

    fun importTtsServerRules(json: String): RuleSetSnapshot {
        val root = JsonSupport.parse(json)
        if (containsScript(root)) {
            throw UnsupportedRuleImportException("SpeechRule JavaScript is not supported; only declarative replaceRules can be imported")
        }
        val objectRoot = JsonSupport.objectValue(root)
        val rawRules = when {
            objectRoot?.get("replaceRules") != null -> JsonSupport.arrayValue(objectRoot["replaceRules"]).orEmpty()
            JsonSupport.arrayValue(root) != null -> JsonSupport.arrayValue(root).orEmpty()
            else -> listOf(root)
        }
        val rules = rawRules.mapIndexedNotNull { index, item ->
            val value = JsonSupport.objectValue(item) ?: return@mapIndexedNotNull null
            val pattern = value.string("pattern") ?: value.string("source") ?: return@mapIndexedNotNull null
            val replacement = value.string("replacement") ?: value.string("replace") ?: ""
            val regex = value.boolean("regex") ?: value.boolean("isRegex") ?: false
            if (regex) RegexReplaceRule("tts-$index", value.int("order") ?: index, pattern, replacement)
            else LiteralReplaceRule("tts-$index", value.int("order") ?: index, pattern, replacement)
        }
        return RuleSetSnapshot("tts-server-import", 1, serverOwnsPreprocessing = false, rules = rules)
    }

    fun exportTtsServerReplaceRules(ruleSet: RuleSetSnapshot): String {
        val rules = ruleSet.rules.mapNotNull { rule ->
            when (rule) {
                is LiteralReplaceRule -> linkedMapOf<String, Any?>(
                    "name" to rule.id,
                    "enabled" to rule.enabled,
                    "regex" to false,
                    "pattern" to rule.from,
                    "replacement" to rule.to,
                    "order" to rule.order,
                )
                is RegexReplaceRule -> linkedMapOf<String, Any?>(
                    "name" to rule.id,
                    "enabled" to rule.enabled,
                    "regex" to true,
                    "pattern" to rule.pattern,
                    "replacement" to rule.replacement,
                    "order" to rule.order,
                )
                else -> null
            }
        }
        return JsonSupport.stringify(linkedMapOf("replaceRules" to rules))
    }

    private fun encodeRule(rule: TtsRule): Map<String, Any?> {
        val value = linkedMapOf<String, Any?>(
            "type" to rule::class.simpleName,
            "id" to rule.id,
            "order" to rule.order,
            "enabled" to rule.enabled,
            "maxInputLength" to rule.maxInputLength,
            "maxReplacements" to rule.maxReplacements,
            "scope" to linkedMapOf(
                "providerId" to rule.scope.providerId,
                "modelId" to rule.scope.modelId,
                "localeLanguageTag" to rule.scope.localeLanguageTag,
                "profileId" to rule.scope.profileId,
            ),
        )
        when (rule) {
            is LiteralReplaceRule -> { value["from"] = rule.from; value["to"] = rule.to }
            is RegexReplaceRule -> { value["pattern"] = rule.pattern; value["replacement"] = rule.replacement }
            is SegmentRule -> { value["delimiterPattern"] = rule.delimiterPattern; value["profileTag"] = rule.profileTag }
            is ProfileRoutingRule -> { value["profileTag"] = rule.profileTag; value["profileId"] = rule.profileId }
            is InstructionRule -> value["instruction"] = rule.instruction
            is AudioTagRule -> {
                value["tag"] = rule.tag
                value["position"] = rule.position.name
                value["anchor"] = rule.anchor
            }
            is ChunkRule -> value["maxChunkLength"] = rule.maxChunkLength
        }
        return value
    }

    private fun decodeRule(value: Map<String, Any?>?): TtsRule? {
        if (value == null) return null
        val id = value.string("id") ?: return null
        val order = value.int("order") ?: 0
        val enabled = value.boolean("enabled") ?: true
        val scopeMap = JsonSupport.objectValue(value["scope"])
        val scope = RuleScope(
            providerId = scopeMap.string("providerId"),
            modelId = scopeMap.string("modelId"),
            localeLanguageTag = scopeMap.string("localeLanguageTag"),
            profileId = scopeMap.string("profileId"),
        )
        val maxInput = value.int("maxInputLength") ?: 20_000
        val maxReplacements = value.int("maxReplacements") ?: 1_000
        return when (value.string("type")) {
            LiteralReplaceRule::class.simpleName -> LiteralReplaceRule(id, order, value.string("from") ?: "", value.string("to") ?: "", enabled, scope, maxInput, maxReplacements)
            RegexReplaceRule::class.simpleName -> RegexReplaceRule(id, order, value.string("pattern") ?: "", value.string("replacement") ?: "", enabled, scope, maxInput, maxReplacements)
            SegmentRule::class.simpleName -> SegmentRule(id, order, value.string("delimiterPattern") ?: "", value.string("profileTag") ?: "default", enabled, scope, maxInput, maxReplacements)
            ProfileRoutingRule::class.simpleName -> ProfileRoutingRule(id, order, value.string("profileTag") ?: "", value.string("profileId") ?: "", enabled, scope, maxInput, maxReplacements)
            InstructionRule::class.simpleName -> InstructionRule(id, order, value.string("instruction") ?: "", enabled, scope, maxInput, maxReplacements)
            AudioTagRule::class.simpleName -> AudioTagRule(id, order, value.string("tag") ?: "", runCatching { TagPosition.valueOf(value.string("position") ?: "PREFIX") }.getOrDefault(TagPosition.PREFIX), value.string("anchor"), enabled, scope, maxInput, maxReplacements)
            ChunkRule::class.simpleName -> ChunkRule(id, order, value.int("maxChunkLength") ?: 1, enabled, scope, maxInput, maxReplacements)
            else -> null
        }
    }

    private fun containsScript(value: Any?, fieldName: String? = null): Boolean = when (value) {
        is Map<*, *> -> value.entries.any { (key, child) ->
            val name = key.toString().lowercase()
            val forbiddenField = name.contains("script") || name.contains("javascript") ||
                name.contains("speechrule") || name == "js" || name == "code" ||
                name == "function" || name == "eval"
            forbiddenField || containsScript(child, name)
        }
        is Iterable<*> -> value.any { containsScript(it, fieldName) }
        is String -> fieldName in setOf("script", "javascript", "js", "code", "function", "eval") ||
            (fieldName == null && value.contains("Rhino", ignoreCase = true))
        else -> false
    }

    private fun Map<String, Any?>?.string(key: String): String? = this?.get(key)?.toString()
    private fun Map<String, Any?>?.int(key: String): Int? = this?.get(key)?.toString()?.toDoubleOrNull()?.toInt()
    private fun Map<String, Any?>?.long(key: String): Long? = this?.get(key)?.toString()?.toDoubleOrNull()?.toLong()
    private fun Map<String, Any?>?.boolean(key: String): Boolean? = this?.get(key) as? Boolean
}
