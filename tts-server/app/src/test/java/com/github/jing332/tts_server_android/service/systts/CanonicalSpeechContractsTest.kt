package com.github.jing332.tts_server_android.service.systts

import com.github.jing332.tts_server_android.model.rhino.speech_rule.SpeechRuleMiMoProfileParser
import com.github.jing332.tts_server_android.model.speech.BundleEntrySnapshot
import com.github.jing332.tts_server_android.model.speech.BundlePrimitiveSnapshot
import com.github.jing332.tts_server_android.model.speech.GenericTtsRequestParamsSnapshot
import com.github.jing332.tts_server_android.model.speech.RouteDeclaration
import com.github.jing332.tts_server_android.model.speech.RouteDeclarationSource
import com.github.jing332.tts_server_android.model.speech.RouteKind
import com.github.jing332.tts_server_android.model.speech.RouteResolution
import com.github.jing332.tts_server_android.model.speech.TargetKey
import com.github.jing332.tts_server_android.model.speech.TargetRole
import com.github.jing332.tts_server_android.service.systts.config.RouteDeclarationResolver
import com.github.jing332.tts_server_android.service.systts.help.TtsBundleMaterializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalSpeechContractsTest {
    @Test
    fun `canonical package contracts expose immutable route and bundle types`() {
        val target = TargetKey("dialogue", 10L)
        val snapshot = RouteDeclarationResolver().capture(
            1L,
            listOf(
                RouteDeclaration(TargetRole.DEFAULT, TargetKey("", 1L)),
                RouteDeclaration(TargetRole.TAG, target, source = RouteDeclarationSource.EXPLICIT_ROLE_CONFIG),
            ),
        )
        val resolved = RouteDeclarationResolver().resolve(
            snapshot,
            "dialogue",
            com.github.jing332.tts_server_android.model.speech.RuleSegmentIdState.Absent,
        )
        assertEquals(RouteKind.IDLESS_UNIQUE_TAG, (resolved as RouteResolution.Success).target.routeKind)

        val materialized = TtsBundleMaterializer().materializeSnapshot(
            GenericTtsRequestParamsSnapshot(
                listOf(BundleEntrySnapshot(0, "enabled", BundlePrimitiveSnapshot.BooleanValue(true))),
            ),
            providerId = "mimo",
        )
        assertTrue(materialized is TtsBundleMaterializer.SnapshotResult.Success)
        assertTrue(SpeechRuleMiMoProfileParser().parse(emptyMap()) is SpeechRuleMiMoProfileParser.Result.Absent)
    }
}
