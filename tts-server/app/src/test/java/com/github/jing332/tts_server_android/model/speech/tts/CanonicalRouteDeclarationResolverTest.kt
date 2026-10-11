package com.github.jing332.tts_server_android.service.systts.config

import com.github.jing332.tts_server_android.model.speech.CaptureState
import com.github.jing332.tts_server_android.model.speech.RouteDeclaration
import com.github.jing332.tts_server_android.model.speech.RouteDeclarationSnapshot
import com.github.jing332.tts_server_android.model.speech.RouteDeclarationSource
import com.github.jing332.tts_server_android.model.speech.RouteDeclarationState
import com.github.jing332.tts_server_android.model.speech.RouteKind
import com.github.jing332.tts_server_android.model.speech.RouteResolution
import com.github.jing332.tts_server_android.model.speech.RuleSegmentIdState
import com.github.jing332.tts_server_android.model.speech.TargetKey
import com.github.jing332.tts_server_android.model.speech.TargetRole
import com.github.jing332.tts_server_android.model.speech.TtsServerSafeErrorCode
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalRouteDeclarationResolverTest {
    private val resolver = RouteDeclarationResolver()

    @Test
    fun `narration alias does not fall back to default`() {
        val snapshot = resolver.capture(
            generation = 1,
            declarations = listOf(
                RouteDeclaration(TargetRole.DEFAULT, TargetKey("", 10L)),
            ),
        )

        assertEquals(
            TtsServerSafeErrorCode.TARGET_NOT_FOUND,
            (resolver.resolve(snapshot, "narration", RuleSegmentIdState.Absent) as RouteResolution.Failure).code,
        )
    }

    @Test
    fun `duplicate target declarations are deduplicated before cardinality`() {
        val duplicateDefault = RouteDeclaration(
            role = TargetRole.DEFAULT,
            targetKey = TargetKey("", 10L),
            source = RouteDeclarationSource.SPEECH_RULE_INFO_LEGACY,
        )
        val duplicateTag = RouteDeclaration(
            role = TargetRole.TAG,
            targetKey = TargetKey("dialogue", 20L),
        )
        val snapshot = resolver.capture(
            generation = 2,
            declarations = listOf(duplicateDefault, duplicateDefault, duplicateTag, duplicateTag),
        )

        assertEquals(
            RouteKind.DEFAULT,
            (resolver.resolve(snapshot, "", RuleSegmentIdState.Absent) as RouteResolution.Success)
                .target.routeKind,
        )
        assertEquals(
            RouteKind.IDLESS_UNIQUE_TAG,
            (resolver.resolve(snapshot, "dialogue", RuleSegmentIdState.Absent) as RouteResolution.Success)
                .target.routeKind,
        )
    }

    @Test
    fun `invalid default state is reported instead of becoming not found`() {
        val snapshot = RouteDeclarationSnapshot(
            generation = 3,
            declarations = emptyList(),
            defaultTarget = RouteDeclarationState.Invalid(TtsServerSafeErrorCode.ROUTE_DECLARATION_INVALID),
            narrationTarget = RouteDeclarationState.Absent,
            enabledTargetsByTag = emptyMap(),
            captureState = CaptureState.Ready,
        )

        assertEquals(
            TtsServerSafeErrorCode.ROUTE_DECLARATION_INVALID,
            (resolver.resolve(snapshot, "", RuleSegmentIdState.Absent) as RouteResolution.Failure).code,
        )
    }
}
