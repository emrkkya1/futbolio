# Deterministic tests and explicit live verification

Status: accepted as part of the approved restructure plan.

Ordinary `test` and `check` must not depend on third-party provider availability.
All live provider verification is invoked explicitly with `:engine:liveTest`.
This task is not wired to `check`; environment variables no longer enable live
tests in the default suite. The older PPV/720p live flags are retired in favor
of the generic per-provider verifier.

Unit and local integration tests inject fixture transports. Their JVM enables
`futbolio.offlineTests`; the production transport fails with an AssertionError
before making a request, so provider outage handling cannot swallow an accidental
internet dependency. The normal destination policy remains in force outside
tests. CI additionally runs engine tests in a Linux network namespace containing
only loopback, defending against accidental networking outside that transport.

Provider reports contain stage outcomes, counts, signatures and safe messages,
never signed URLs, header values or raw exception messages. Missing active matches
and website fallback are non-passes. Valid media segments do not establish native
audio/video playback or full-match reliability.
