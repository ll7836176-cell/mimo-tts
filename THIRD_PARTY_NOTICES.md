# Third-party notices

The audio module includes the Apache-2.0 Sonic time-stretching implementation. It is used at the Android TTS sink so speech-rate changes do not shift pitch.

## Sonic

- Source: `core/audio/src/main/java/com/jingyue/mimotts/core/audio/Sonic.java`
- Copyright 2010–2011 Bill Cox.
- Licensed under the Apache License, Version 2.0.

The source was adapted from the implementation already used by Talkify in this workspace; the original license header is retained in the copied source file.

Runtime dependencies:

- Kotlin, Android Gradle Plugin, AndroidX Compose/Core/Activity: used under their respective published licenses; resolved through Gradle Maven repositories.
- OkHttp 4.12.0: Apache License 2.0.
- JLayer 1.0.1: LGPL-2.1-or-later. It is isolated to `:provider:mimo` for MP3 decoding; no JLayer code is copied or modified.

When publishing an APK, include the license texts required by the resolved dependency versions and verify the dependency lockfiles generated for the release build.
