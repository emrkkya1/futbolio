# Manual live tests

All tests that depend on real providers, public entry links, current schedules,
or upstream media belong here, not under `src/test`.

Run explicitly from the repository root:

```sh
./gradlew :engine:liveTest --console=plain
```

These sources retain the `liveTest` Gradle source set and task names. They are
neither compiled nor run by ordinary `test` / `check` tasks or the current CI
workflow. `:engine:liveTestClasses` compiles them without contacting providers.

- `LiveProviderVerificationTest`: real provider discovery, resolution, and media.
- `LiveStremioRelayVerificationTest`: real upstream media through the local relay.
- `ProviderEntryVerificationTest`: real published entry-link navigation.
- `MatchStreamDiagnosticsTest`: current-match comparisons and real player/API tracing.

Live checks can fail or skip because providers or active events are unavailable;
they are manual diagnostics, not merge gates. Media retrieval does not establish
decoded video/audio playback or full-match stability.

Offline tests of the verifier itself remain in `src/test`, using injected fixture
transports. Local HTTP servers, loopback sockets, and the desktop's own LAN
interface do not require internet or an external service and remain automated.

See [live verification details](../../../docs/LIVE-PROVIDER-TESTS.md) and the
[manual device/playback checklist](../../../docs/MANUAL-TESTS.md).
