# Validation record

Recorded 2026-08-25.

Passed locally:

- `.\gradlew.bat test --no-configuration-cache`
- `.\gradlew.bat :app:lint --no-configuration-cache`
- `.\gradlew.bat :app:assembleDebug --no-configuration-cache`
- `.\gradlew.bat :provider:mimo:test --no-configuration-cache`

The debug artifact is `app/build/outputs/apk/debug/app-debug.apk`. The APK has an independent `com.jingyue.mimotts` application ID and declares the Android `TextToSpeechService`, `CHECK_TTS_DATA`, and `GET_SAMPLE_TEXT` entry points.

Not run:

- Connected/instrumented tests: `adb devices` reported no attached Android device on 2026-08-25.
- Online MiMo model matrix: no API key or voice-clone sample was supplied. Offline fake transport and redacted request/response fixtures cover the contract without pretending to validate the cloud service.
- tts-server direct/file compatibility matrix: requires installing both APKs on an API 30+ device.
