# Local patches to tts-server-go

This directory is the source for `github.com/jing332/tts-server-go` version
`v0.0.0-20230130020836-aed022548060`, copied from the Go module source verified
by the parent module's `go.sum`. `go.mod` uses a local replace so the Android AAR
contains the reviewed fixes below.

- `server/public/index.html`: repairs Edge voice selection, adds explicit
  loading/empty states, escapes SSML text and voice attributes, validates
  preview inputs, reports decode/network failures, and encodes Legado query
  parameters.
- `server/public/azure.html` and `creation.html`: guard empty/failed voice
  catalogs, preserve valid voice selection, clear dependent locale options,
  escape Azure SSML fields, release preview audio contexts, and build encoded
  Legado URLs.
- `server/public/static.js`: shares XML escaping and voice-state helpers,
  encodes the full source URL in the Legado deep link, and uses the Clipboard
  API with a synchronous fallback.
- `server/api.go`: accepts a `Token` header when generating a Legado config,
  returns its JSON content type, and redacts rejected token values from logs.
- `server/logic.go`: XML-escapes generated SSML attributes and JSON-encodes
  generated request headers so quotes and special characters remain valid.
- `server/api_test.go` and `server/logic_test.go`: cover header-based token
  transfer, special-character encoding, and the server shutdown helper. The
  previous shutdown test blocked indefinitely while opening a real listener.

The legacy GET import URL still transports the configured token as a query
parameter because the reader app fetches this source URL without custom headers.
The imported TTS request itself stores the token in its `Token` header.

Keep changes to the vendored dependency small and review its origin/version
before updating this directory.
