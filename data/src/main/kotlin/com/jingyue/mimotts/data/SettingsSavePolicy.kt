package com.jingyue.mimotts.data

/**
 * Defines how the settings form treats a masked/empty API-key field.
 * An empty field means "keep the key already stored"; clearing is an explicit action.
 */
object SettingsSavePolicy {
    fun resolveApiKey(input: String, existing: String?): String? =
        input.trim().takeIf { it.isNotEmpty() } ?: existing
}
