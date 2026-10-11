package com.jingyue.mimotts.debug

import android.os.Bundle
import android.os.SystemClock
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Debug-only target-process seam. The control surface accepts safe metadata
 * only; request text, style, credentials and provider bodies are rejected.
 */
object MiMoTargetProcessTestSeam {
    const val METHOD_ISSUE_BOOTSTRAP = "issueBootstrap"
    const val METHOD_INSTALL = "install"
    const val METHOD_RESET = "reset"
    const val METHOD_STATS = "stats"
    const val METHOD_DIAGNOSTICS = "diagnostics"
    const val KEY_PROTOCOL_VERSION = "protocolVersion"
    const val KEY_BOOTSTRAP_TOKEN = "bootstrapToken"
    const val KEY_SESSION_TOKEN = "sessionToken"
    const val KEY_MODE = "mode"
    const val KEY_PROVIDER_RESULT = "providerResult"
    const val KEY_CHUNK_PLAN = "chunkPlan"
    const val KEY_STATUS = "status"
    const val KEY_CANONICAL_CODE_ID = "canonicalCodeId"
    const val KEY_FINGERPRINT = "fingerprint"
    const val KEY_UNKNOWN_COUNT = "unknownCount"
    const val KEY_OUTCOME = "outcome"
    const val KEY_CHUNKS = "chunks"
    const val STATUS_OK = "ok"
    const val STATUS_ERROR = "error"
    const val OUTCOME_SUCCESS = "success"
    const val OUTCOME_FAILURE = "failure"
    const val OUTCOME_CANCEL = "cancel"
    const val MODE_FAKE_SUCCESS = "FAKE_SUCCESS"
    const val MODE_FAKE_FAILURE = "FAKE_FAILURE"
    const val MODE_FAKE_CANCEL = "FAKE_CANCEL"
    const val MODE_RECORD_ONLY = "RECORD_ONLY"
    const val TTL_MILLIS = 60_000L
    const val PROTOCOL_VERSION = 1

    private data class Bootstrap(val token: String, val expiresAt: Long)
    private data class Session(val token: String, val expiresAt: Long, val mode: String)
    private val lock = Any()
    private var bootstrap: Bootstrap? = null
    private var session: Session? = null
    private var unknownCount = 0

    fun call(method: String, input: Bundle?): Bundle = synchronized(lock) {
        when (method) {
            METHOD_ISSUE_BOOTSTRAP -> issueBootstrap(input)
            METHOD_INSTALL -> install(input)
            METHOD_RESET -> reset(input)
            METHOD_STATS -> stats(input)
            METHOD_DIAGNOSTICS -> diagnostics(input)
            else -> error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
        }
    }

    private fun issueBootstrap(input: Bundle?): Bundle {
        if (!validateKeys(input, setOf(KEY_PROTOCOL_VERSION))) return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
        if (!validProtocol(input)) return error(MiMoSafeCode.REQUEST_UNSUPPORTED_PROTOCOL)
        val token = opaqueToken()
        bootstrap = Bootstrap(token, now() + TTL_MILLIS)
        return ok().apply { putString(KEY_BOOTSTRAP_TOKEN, token) }
    }

    private fun install(input: Bundle?): Bundle {
        if (!validateKeys(input, setOf(KEY_PROTOCOL_VERSION, KEY_BOOTSTRAP_TOKEN, KEY_MODE, KEY_PROVIDER_RESULT, KEY_CHUNK_PLAN))) {
            return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
        }
        if (!validProtocol(input)) return error(MiMoSafeCode.REQUEST_UNSUPPORTED_PROTOCOL)
        val mode = input?.getString(KEY_MODE).orEmpty()
        if (mode !in setOf(
                MODE_FAKE_SUCCESS,
                MODE_FAKE_FAILURE,
                MODE_FAKE_CANCEL,
                MODE_RECORD_ONLY,
            )
        ) return error(MiMoSafeCode.CONTROL_UNSUPPORTED_MODE)
        if (mode == MODE_RECORD_ONLY && (
                input?.containsKey(KEY_PROVIDER_RESULT) == true ||
                    input?.containsKey(KEY_CHUNK_PLAN) == true
                )
        ) {
            return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
        }
        val supplied = input?.getString(KEY_BOOTSTRAP_TOKEN)
        val current = bootstrap
        if (supplied.isNullOrBlank() || current == null || current.token != supplied || current.expiresAt <= now()) {
            return error(MiMoSafeCode.CONTROL_INVALID_TOKEN)
        }
        if (mode == MODE_RECORD_ONLY) {
            MiMoTargetProcessDebugRuntime.installRecordOnly()
        } else {
            val providerResult = input?.getBundle(KEY_PROVIDER_RESULT)
                ?: return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
            val chunkPlan = input.getBundle(KEY_CHUNK_PLAN)
                ?: return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
            val descriptor = parseFakeDescriptor(mode, providerResult, chunkPlan)
                ?: return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
            if (!MiMoTargetProcessDebugRuntime.installFake(mode, descriptor.chunks, descriptor.failureCode)) {
                return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
            }
        }
        bootstrap = null
        val token = opaqueToken()
        session = Session(token, now() + TTL_MILLIS, mode)
        return ok().apply { putString(KEY_SESSION_TOKEN, token) }
    }

    private fun reset(input: Bundle?): Bundle {
        if (!validateKeys(input, setOf(KEY_PROTOCOL_VERSION, KEY_SESSION_TOKEN))) {
            return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
        }
        if (!validProtocol(input) || !validSession(input?.getString(KEY_SESSION_TOKEN))) {
            return error(MiMoSafeCode.CONTROL_INVALID_TOKEN)
        }
        session = null
        MiMoTargetProcessDebugRuntime.clear()
        return ok()
    }

    private fun stats(input: Bundle?): Bundle {
        if (!validateKeys(input, setOf(KEY_PROTOCOL_VERSION, KEY_SESSION_TOKEN))) {
            return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
        }
        if (!validProtocol(input) || !validSession(input?.getString(KEY_SESSION_TOKEN))) {
            return error(MiMoSafeCode.CONTROL_INVALID_TOKEN)
        }
        return ok().apply {
            val counters = MiMoTargetProcessDebugRuntime.snapshotCounters()
            putInt("providerRequests", counters.providerRequests)
            putInt("factoryCreates", counters.factoryCreates)
            putInt("transportRequests", counters.transportRequests)
            putInt("chunkEvents", counters.chunkEvents)
            putInt("sinkEvents", counters.sinkEvents)
        }
    }

    private fun diagnostics(input: Bundle?): Bundle {
        if (!validateKeys(input, setOf(KEY_PROTOCOL_VERSION, KEY_SESSION_TOKEN))) {
            return error(MiMoSafeCode.CONTROL_INVALID_ARGUMENT)
        }
        if (!validProtocol(input) || !validSession(input?.getString(KEY_SESSION_TOKEN))) {
            return error(MiMoSafeCode.CONTROL_INVALID_TOKEN)
        }
        return ok().apply {
            putString(KEY_FINGERPRINT, fingerprint(session?.token.orEmpty()))
            putInt(KEY_UNKNOWN_COUNT, unknownCount)
        }
    }

    private fun validSession(token: String?): Boolean {
        val current = session ?: return false
        return !token.isNullOrBlank() && token == current.token && current.expiresAt > now()
    }

    private fun validProtocol(input: Bundle?): Boolean {
        val value = input?.get(KEY_PROTOCOL_VERSION) ?: PROTOCOL_VERSION
        return value is Int && value == PROTOCOL_VERSION
    }

    private fun validateKeys(input: Bundle?, allowed: Set<String>): Boolean {
        val unknown = input?.keySet().orEmpty().filterNot { it in allowed }
        if (unknown.isNotEmpty()) unknownCount += unknown.size
        return unknown.isEmpty()
    }

    private data class FakeDescriptor(
        val chunks: Int,
        val failureCode: String?,
    )

    private fun parseFakeDescriptor(
        mode: String,
        providerResult: Bundle,
        chunkPlan: Bundle,
    ): FakeDescriptor? {
        if (!validateKeys(providerResult, setOf(KEY_OUTCOME, KEY_CANONICAL_CODE_ID)) ||
            !validateKeys(chunkPlan, setOf(KEY_CHUNKS))
        ) return null

        val expectedOutcome = when (mode) {
            MODE_FAKE_SUCCESS -> OUTCOME_SUCCESS
            MODE_FAKE_FAILURE -> OUTCOME_FAILURE
            MODE_FAKE_CANCEL -> OUTCOME_CANCEL
            else -> return null
        }
        if (providerResult.getString(KEY_OUTCOME) != expectedOutcome) return null
        val failureCode = providerResult.getString(KEY_CANONICAL_CODE_ID)
        if (mode == MODE_FAKE_FAILURE &&
            failureCode != null &&
            MiMoSafeCode.entries.none { it.id == failureCode }
        ) return null
        val chunks = if (chunkPlan.containsKey(KEY_CHUNKS)) {
            val value = chunkPlan.get(KEY_CHUNKS)
            if (value !is Int) return null
            value
        } else {
            return null
        }
        if (chunks !in 1..16) return null
        return FakeDescriptor(chunks, failureCode)
    }

    private fun ok() = Bundle().apply {
        putString(KEY_STATUS, STATUS_OK)
        putString(KEY_CANONICAL_CODE_ID, MiMoSafeCode.OK.id)
    }

    private fun error(code: MiMoSafeCode) = Bundle().apply {
        putString(KEY_STATUS, STATUS_ERROR)
        putString(KEY_CANONICAL_CODE_ID, code.id)
    }

    private fun opaqueToken(): String = UUID.randomUUID().toString()
    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

    private enum class MiMoSafeCode(val id: String) {
        OK("ok"),
        REQUEST_UNSUPPORTED_PROTOCOL("request.unsupported_protocol"),
        CONTROL_INVALID_ARGUMENT("control.invalid_argument"),
        CONTROL_INVALID_TOKEN("control.invalid_token"),
        CONTROL_UNSUPPORTED_MODE("control.unsupported_mode"),
    }
}
