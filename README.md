# MiMo TTS

MiMo TTS is an independent Android `TextToSpeechService` and MiMo Studio app. It is deliberately separate from Talkify: it has its own application ID (`com.jingyue.mimotts`), preferences, service, provider registry, and private storage.

## What is implemented

- Android TTS discovery, `CHECK_TTS_DATA`, sample text, locale availability, canonical/legacy Voice resolution, synthesis, stop, and generation isolation.
- Provider-neutral SPI in `core/tts-spi`, deterministic no-script rules in `core/rules`, and exact-once audio session handling in `core/audio`.
- MiMo v2.5 built-in, voice design, and voice clone model profiles; eight verified built-in Voices are canonical. `mimo_default` is only exposed when the catalog is explicitly constructed with `defaultAliasAvailable = true`.
- OpenAI-compatible `/v1/chat/completions` request composition, SSE/batch response parsing, PCM/WAV/MP3 decoding, actual audio format propagation, and max-buffer chunking.
- Private clone sample import through SAF, AES/GCM API-key storage backed by Android Keystore, atomic rule JSON storage, tts-server declarative replace-rule import/export, and JavaScript rejection.
- A small Compose configuration screen for endpoint/API Key storage and opening Android TTS settings.

## Build

Use JDK 21 and the Android SDK installed at `D:\code\AndroidSdk` (or set `ANDROID_HOME`/`ANDROID_SDK_ROOT` to another SDK). The project targets API 37 and supports Android 11/API 30 or newer.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:ANDROID_HOME = 'D:\code\AndroidSdk'
$env:ANDROID_SDK_ROOT = 'D:\code\AndroidSdk'
.\gradlew.bat test --no-configuration-cache
.\gradlew.bat :app:lint --no-configuration-cache
.\gradlew.bat :app:assembleDebug --no-configuration-cache
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Online MiMo tests are intentionally not run without a user-supplied key and sample; a fake transport is used for offline contract tests instead.

## Configuration and privacy

The default endpoint is `https://api.xiaomimimo.com/v1/chat/completions`. Only HTTPS endpoints are accepted by the request composer. The API key is encrypted with an Android Keystore AES/GCM key and is not included in rule/profile exports. Clone samples are copied to `filesDir/voice_samples` and excluded from both legacy backup and Android 12+ data extraction. Text, instructions, Base64 samples, and authorization values are not logged.

`serverOwnsPreprocessing` defaults to `true`, so local rewrite/segmentation/routing does not run again when tts-server has already processed a request. Explicit MiMo instructions and audio tags remain available.

## Module boundaries

```text
:app                 Android UI + TextToSpeechService
:core:tts-spi        provider/model/voice/request/event contracts
:core:rules          versioned, deterministic, no-script rule DSL
:core:audio          audio specs, playback policy, exact-once session
:data                private storage, secret store, rule JSON codec
:provider:mimo       MiMo JSON, SSE/batch parser, audio decoder, transport
```

The Xiaomi JSON contract is confined to `:provider:mimo`; core modules do not depend on Android UI, Room, or Xiaomi-specific JSON. A new provider can implement `EnginePlugin` and register in `ProviderModuleRegistry` without adding a provider branch to the rule engine or TTS service.

## References

Protocol and compatibility behavior were derived from the implementation specifications in the parent workspace, the official MiMo documentation, and the tts-server Android baseline. No third-party source code or API key/sample was copied into this project. See [REFERENCES.md](REFERENCES.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
