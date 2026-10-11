package com.jingyue.mimotts.core.rules

class RuleValidator {
    fun validate(ruleSet: RuleSetSnapshot): List<String> {
        val errors = mutableListOf<String>()
        if (ruleSet.id.isBlank()) errors += "Rule set id is required"
        if (ruleSet.version < 1) errors += "Rule set version must be positive"
        ruleSet.rules.forEach { rule ->
            if (rule.id.isBlank()) errors += "Rule id is required"
            if (rule.maxInputLength !in 1..100_000) errors += "${rule.id}: invalid input limit"
            if (rule.maxReplacements !in 1..100_000) errors += "${rule.id}: invalid replacement limit"
            when (rule) {
                is LiteralReplaceRule -> if (rule.from.isEmpty()) errors += "${rule.id}: source is required"
                is RegexReplaceRule -> runCatching { Regex(rule.pattern) }
                    .onFailure { errors += "${rule.id}: invalid regex" }
                is SegmentRule -> runCatching { Regex(rule.delimiterPattern) }
                    .onFailure { errors += "${rule.id}: invalid segment regex" }
                is AudioTagRule -> if (rule.tag.isBlank()) errors += "${rule.id}: tag is required"
                is InstructionRule -> if (rule.instruction.isBlank()) errors += "${rule.id}: instruction is required"
                is ProfileRoutingRule -> if (rule.profileId.isBlank()) errors += "${rule.id}: profile is required"
                is ChunkRule -> if (rule.maxChunkLength !in 1..100_000) errors += "${rule.id}: invalid chunk limit"
            }
        }
        return errors
    }

    fun requireValid(ruleSet: RuleSetSnapshot) {
        val errors = validate(ruleSet)
        require(errors.isEmpty()) { errors.joinToString("; ") }
    }
}
