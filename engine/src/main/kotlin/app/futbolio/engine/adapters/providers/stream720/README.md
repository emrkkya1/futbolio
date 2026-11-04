# 720pstream adapter

Parses football cards and bounded literal/JSON embed data without evaluating
scripts. Each resolved playback session owns renewable device/auth bindings;
renewal is single-flight and media-driven with one bounded unauthorized retry.
Key credentials are origin-scoped; all requests use validated transport.

Next.js literal payloads supply initialGames competition metadata (needed by
the curated catalog) and the selected event's iframeUrl when published. Null
players on future events remain unavailable, not guessed from other fixtures.
Legacy iframe/session players and generic published HLS players are supported;
HLS content retains its type even when a provider uses a nonstandard extension.

Renewable player configuration is detected by protocol fields, not a particular
global variable or minifier's local names. Bounded static-data extraction handles
nested object literals, JSON script blocks, JSON.parse/atob literal data, data
attributes, and plain/base64/character-array URLs. Small scripts are inspected
before large inline libraries, with per-script budgets, so advertising code cannot
starve a later bootstrap. Multiple players require an unambiguous selected ID;
contradictory credentials/URLs are rejected, not combined. The older literal
session format remains a compatibility fallback. No downloaded scripts execute.

This tolerates data-layout changes, not arbitrary future protocols. DRM, browser
challenges, computed executable configuration, missing players and ambiguous
sources remain unsupported. URLs/signatures are never guessed or hardcoded.

Tests: sanitized `providers/stream720/schedule.html`, session renewal/scoping,
encrypted-fixture relay and strict media checks. `:engine:liveTest` is opt-in;
valid decrypted segments still do not prove native full-match audio/video.

Dated real-response extracts and their sanitization notes live in
`engine/src/test/resources/providers/states/2026-11-04/`.
