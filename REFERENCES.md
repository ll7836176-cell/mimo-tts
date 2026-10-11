# References and traceability

This project implements the two specifications supplied in the parent workspace:

- `docs/MIMO_TTS_APP_IMPLEMENTATION_SPEC.md`: independent MiMo TTS app, module boundaries, Android service contract, security, and acceptance matrix.
- `docs/TALKIFY_XIAOMI_MIMO_TTS_EXTENSIBLE_IMPLEMENTATION_SPEC.md`: MiMo request/response behavior, tts-server ownership boundary, rule semantics, and audio lifecycle details.

External behavior references recorded on 2026-08-25:

| Source | Use | Code copied? |
| --- | --- | --- |
| [MiMo Speech Synthesis](https://mimo.mi.com/docs/usage-guide/speech-synthesis) | Three v2.5 model profiles, messages, Voice design/clone, audio formats | No |
| [MiMo OpenAI API compatibility](https://mimo.mi.com/docs/api/chat/openai-api) | `/v1/chat/completions`, `assistant` text, `user` controls, `audio` fields | No |
| [MiMo v2.5 TTS release notes](https://mimo.mi.com/docs/en-US/news/previous-news/v2.5-tts-release) | Capability and release context | No |
| [XiaomiMiMo/MiMo-Skills](https://github.com/XiaomiMiMo/MiMo-Skills/tree/main/skills/mimo-v2-5-tts) | Official examples and behavior fixtures | No runtime dependency or source copy |
| [hass-cortex/xiaomi-mimo-tts](https://github.com/hass-cortex/xiaomi-mimo-tts) | Profile/sample and long-text batching ideas | No |
| [marsPRE/android-custom-tts-engine](https://github.com/marsPRE/android-custom-tts-engine) | Android TTS discovery shape | No |
| [jing332/tts-server-android](https://github.com/jing332/tts-server-android) | Android tts-server Voice and replace-rule boundary | No |

The repositories above are behavior/architecture references only; this checkout does not vendor them and therefore cannot silently inherit an unpinned upstream commit. The provider contract is covered by local, credential-free fixtures in `provider/mimo/src/test/resources/official`.
