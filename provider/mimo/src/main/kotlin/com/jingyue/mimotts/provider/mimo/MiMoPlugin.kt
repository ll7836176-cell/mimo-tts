package com.jingyue.mimotts.provider.mimo

import com.jingyue.mimotts.core.spi.ConfigField
import com.jingyue.mimotts.core.spi.ConfigFieldType
import com.jingyue.mimotts.core.spi.ConfigSchema
import com.jingyue.mimotts.core.spi.EnginePlugin
import com.jingyue.mimotts.core.spi.ProviderDescriptor
import com.jingyue.mimotts.core.spi.SynthesisProvider
import com.jingyue.mimotts.core.spi.MiMoVoiceCatalogSnapshot

class MiMoPlugin(
    private val profileMap: Map<String, MiMoVoiceProfile> = MiMoProvider.defaultProfiles(),
    private val catalogOwner: MiMoVoiceCatalog = MiMoVoiceCatalog(),
    private val catalogSnapshot: MiMoVoiceCatalogSnapshot = catalogOwner.snapshot(),
) : EnginePlugin {
    override val descriptor = ProviderDescriptor(
        id = "mimo",
        displayName = "Xiaomi MiMo",
        defaultModelId = MiMoModelIds.BUILT_IN,
        moduleVersion = "1.0",
        configSchema = ConfigSchema(
            version = 1,
            fields = listOf(
                ConfigField("endpoint", ConfigFieldType.TEXT, required = true, defaultValue = "https://api.xiaomimimo.com/v1/chat/completions"),
                ConfigField("apiKey", ConfigFieldType.SECRET, required = true, sensitive = true),
                ConfigField("model", ConfigFieldType.SELECT, required = true, defaultValue = MiMoModelIds.BUILT_IN),
                ConfigField("voice", ConfigFieldType.SELECT, required = true),
                ConfigField("serverOwnsPreprocessing", ConfigFieldType.BOOLEAN, required = true, defaultValue = "true"),
            ),
        ),
    )

    override val models = listOf(MiMoModelProfiles.builtIn.descriptor)

    override val voices = catalogSnapshot.voices

    override fun createProvider(): SynthesisProvider = MiMoProvider(
        profiles = profileMap,
        catalog = catalogSnapshot,
    )
}
