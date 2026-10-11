package com.github.jing332.tts_server_android.model.speech.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteDeclarationResolverTest {
    private val resolver = RouteDeclarationResolver()

    @Test
    fun `empty tag uses explicit default and ordinary tag uses unique target`() {
        val snapshot = resolver.capture(
            generation = 7,
            declarations = listOf(
                RouteDeclaration(
                    role = TargetRole.DEFAULT,
                    targetKey = TargetKey("", 100L),
                    source = RouteDeclarationSource.EXPLICIT_ROLE_CONFIG,
                ),
                RouteDeclaration(
                    role = TargetRole.TAG,
                    targetKey = TargetKey("narration", 200L),
                    source = RouteDeclarationSource.EXPLICIT_ROLE_CONFIG,
                ),
            ),
        )

        assertEquals(
            RouteKind.DEFAULT,
            (resolver.resolve(snapshot, "", RuleSegmentIdState.Absent) as RouteResolution.Success)
                .target.routeKind,
        )
        assertEquals(
            RouteKind.IDLESS_UNIQUE_TAG,
            (resolver.resolve(snapshot, "narration", RuleSegmentIdState.Absent) as RouteResolution.Success)
                .target.routeKind,
        )
    }

    @Test
    fun `ambiguous and missing explicit ids fail without fallback`() {
        val snapshot = resolver.capture(
            generation = 8,
            declarations = listOf(
                RouteDeclaration(TargetRole.DEFAULT, TargetKey("", 100L)),
                RouteDeclaration(TargetRole.TAG, TargetKey("narration", 200L)),
                RouteDeclaration(TargetRole.TAG, TargetKey("narration", 201L)),
            ),
        )

        assertEquals(
            TtsServerSafeErrorCode.TARGET_AMBIGUOUS,
            (resolver.resolve(snapshot, "narration", RuleSegmentIdState.Absent) as RouteResolution.Failure).code,
        )
        assertEquals(
            TtsServerSafeErrorCode.TARGET_NOT_FOUND,
            (resolver.resolve(snapshot, "narration", RuleSegmentIdState.Present(999L)) as RouteResolution.Failure)
                .code,
        )
    }

    @Test
    fun `narration alias is used only when no explicit narration declaration exists`() {
        val snapshot = resolver.capture(
            generation = 9,
            declarations = listOf(
                RouteDeclaration(TargetRole.DEFAULT, TargetKey("", 100L)),
                RouteDeclaration(TargetRole.NARRATION, TargetKey("", 300L)),
            ),
        )

        val result = resolver.resolve(snapshot, "narration", RuleSegmentIdState.Absent)
            as RouteResolution.Success
        assertEquals(RouteKind.NARRATION_ALIAS, result.target.routeKind)
        assertEquals(300L, result.target.key.configId)
    }
}
