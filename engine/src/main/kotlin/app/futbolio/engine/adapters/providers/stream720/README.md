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

Tests: sanitized `providers/stream720/schedule.html`, session renewal/scoping,
encrypted-fixture relay and strict media checks. `:engine:liveTest` is opt-in;
valid decrypted segments still do not prove native full-match audio/video.
