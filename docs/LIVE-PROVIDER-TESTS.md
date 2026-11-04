# Live provider verification (PC/JVM, no emulator)

## Champions League repair checkpoint — 4 November 2026 UTC

At approximately 21:46 UTC, the real local Stremio API returned **7 inline stream
choices each** for PSV Eindhoven–Club Brugge and RB Leipzig–Manchester City.
Both fixtures included PapazSports, Taraftarium24, FutbolVivo and 720pstream.
For each fixture, at least one source from each provider passed strict recognized
media verification through the local relay, including decrypted 720p AES-128
segments. PPV resolved no static media, as expected. No website-only options were
included. This verifies transport, not native Android/Stremio decoded playback.

Repairs include Turkish UEFA competition names, team aliases, clock-only listings
after Istanbul midnight, and region/quality labels incorrectly used as leagues.
The requested grouping rule accepts a shared normalized team and the same kickoff
minute, irrespective of home/away order; exact-rule matches group transitively.
Clock-only date correction requires independent still-live dated evidence.

720p's published bootstrap replaced the old minified-variable layout. An additional
ad-heavy response exhausted a naive global scan budget before that bootstrap;
bounded per-script scanning and small-script-first ordering fix that failure.

Frozen real-provider state extracts for all five catalogs plus the 720p event,
embed, player and auth chain are saved under
`engine/src/test/resources/providers/states/2026-11-04/`. `CapturedProviderStateTest`
runs offline at a fixed clock. The extracts remove credentials, active hostnames,
tracking code and unrelated content; their README records the transformations.

Focused live diagnosis (time-dependent target fixtures):

```sh
./gradlew :engine:liveTest --tests app.futbolio.engine.MatchStreamDiagnosticsTest
```

The diagnostic reports independent provider/media outcomes; its aggregate JUnit
success must not substitute for reading individual media-verification flags.
`FUTBOLIO_TRACE_DIRECTORY` optionally saves private raw responses during the
`traceTarget720PlayerStages` case. These may contain credentials: never commit or
publish them. Ordinary tests never capture or contact live providers.

Each enabled bundled provider has its **own JUnit case**. The test discovers real match listings, selects up to three non-expired started or unknown-start matches, resolves up to two HLS choices per match, and fetches media through the production upstream/session path. Future matches are not treated as live. Channels are counted separately and cannot substitute for discovering and testing a match.

## Provider contract and registration

Every adapter implements the same `Provider` interface:

```kotlin
interface Provider {
    suspend fun discover(source: SourceConfig, net: Upstream): ProviderResult
    suspend fun resolve(item: Listing, net: Upstream): List<StreamChoice>
    suspend fun recover(source: SourceConfig, net: Upstream): ProviderResult? // optional, defaults to null
}
```

The port is owned by `:application` and has no concrete static-player default. Adapters explicitly compose shared extraction or their own player/session implementation. `StreamResolver` only supplies common bounds, caching and website fallback; it has no provider-name branches. `DomainRecovery` uses generic discovery/recovery hooks. Both coordinators are application services, independent of HTTP/parser libraries.

To add an adapter, implement `Provider` in its own `engine/adapters/providers/<name>/` package, then add **one** `ProviderRegistration(ProviderDescriptor(SourceConfig(...), settings)) { MyProvider }` in `engine/src/main/kotlin/app/futbolio/engine/adapters/providers/ProviderRegistry.kt`. This list drives adapter lookup, defaults, optional-setting/timezone metadata, both host editors and live tests. No coordinator, host screen branch, extra kind list, or live-test change is needed. Adapters are stateless; instance state is keyed by configured ID or belongs to a resolved playback session. Saved-default migration remains a separate product decision.

`ProviderContractTest` checks that registration/default/test lists stay identical and that a hypothetical adapter can complete the same discovery → resolution → verified-media test without any harness edits.

Success requires recognized MPEG-TS/MP4 bytes. AES-128 content is decrypted and checked; a playlist, website fallback, encryption key alone, or arbitrary encrypted payload is not success. Explicit and sequence-derived IVs are supported. No website scripts execute, no tokens are hardcoded, and no Android emulator is used. This does not establish native video/audio playback or match identity.

## Manual-only live suite

All internet-backed test cases live under [`engine/src/live/`](../engine/src/live/).
They keep the `liveTest` source set/task name for command compatibility and are
excluded from ordinary `test`, `check`, and automated CI. Compile without running
providers with `./gradlew :engine:liveTestClasses`.

`LiveProviderVerifierTest` and the other fixture-backed provider/relay tests stay
under `src/test`: they exercise live-verification logic without live dependencies.
Loopback HTTP servers and self-hosted LAN relay fixtures are not online services.

## Run all providers

From the project directory, with the project's JDK/SDK environment set:

```powershell
$env:JAVA_HOME = 'C:/emreky/android-tools/jdk'
$env:ANDROID_HOME = 'C:/emreky/android-tools/sdk'
./gradlew.bat :engine:liveTest --console=plain
```

Live cases belong to a separate source set and task, never ordinary `test` or `check`. Invoking `liveTest` is the explicit opt-in and always runs fresh. The older `FUTBOLIO_LIVE_PROVIDERS`, `FUTBOLIO_PPV_LIVE`, `FUTBOLIO_720P_LIVE`, and `FUTBOLIO_720P_MATCH` flags are retired; they do not enable live work in unit tests. Each provider has a 60-second total budget, discovery has a 20-second budget, and each of at most three match attempts has a 12-second budget. All sources are still tried after an individual case fails.

## Reports

- `engine/build/reports/live-providers/<provider-id>.json`: independent fresh per-provider reports, including match/channel counts, candidates, attempts, HLS choices, verified media bytes/formats, outcome and elapsed time.
- `engine/build/reports/tests/liveTest/index.html`: JUnit results with a separate case for each provider.

Outcomes distinguish `PASS`, `DISCOVERY_FAILED`, `DISCOVERY_TIMEOUT`, `NO_MATCHES`, `NO_ACTIVE_MATCHES`, `RESOLUTION_FAILED`, `SEGMENT_FAILED`, and `PROVIDER_TIMEOUT`. Only `PASS` passes its JUnit case. In particular, no active match is reported as **untested live media**, not proof that the provider is broken. Any non-pass makes the overall Gradle invocation fail, even if another provider passes. Reports do not include stream URLs, tokens, AES keys or upstream exception messages.

Deterministic harness tests:

```powershell
./gradlew.bat :engine:test --tests app.futbolio.engine.LiveProviderVerifierTest --console=plain
```

These check independent success/failure, distinct failure stages, cancellation/time budgets, implicit AES IVs and rejection of encrypted garbage.

## Observed run (2026-11-02)

- PPV: 151 matches, no currently eligible started matches; no media verification possible.
- PapazSports: 102 matches and 53 channels; three sampled started matches produced no inline HLS.
- Taraftarium24: 11 matches and 25 channels; three sampled unknown-start matches resolved HLS but strict media verification failed.
- FutbolVivo: 28 matches and 8 channels; one match attempt fetched and verified 3,200,512 bytes of media.
- 720pstream: 176 matches, no currently eligible started matches; no media verification possible.

These are time-dependent observations, not permanent provider ratings. Other previously verified matches may already have ended.

## Provider repairs and local Stremio relay checkpoint (3 November 2026 local time)

- PapazSports entry navigation now reads Bitly's published button literal and
  explicit destination interstitial. The verified entry resolved to
  `www.papazsports1032.pro` at this checkpoint; it is not hardcoded as a permanent
  domain. Browser and player URLs use the resolved portal. Its current VIP auth
  protocol returned HLS, including nonstandard `.js` playlist names. A started
  match passed the real local `/stream` API and opaque `/hls` relay, with
  1,126,308 bytes of recognized media in the last run. Sessions refresh on media
  traffic before the website's 40-minute refresh interval, or retry once on
  401/403. Renewal/cancellation/header behavior has deterministic fixture tests;
  no full-match duration acceptance was run.
- Taraftarium24 passed both strict upstream match-media verification and the
  local Stremio relay. The last relay check used a continuous channel and
  verified 3,414,080 bytes. Diagnostics now retain the resolved active domain.
- FutbolVivo follows its redirected homepage as the actual browser/referer base.
  NHL/NFL items incorrectly labeled football by the upstream agenda are excluded.
  A continuous channel passed the local Stremio relay with 1,837,888 verified
  bytes. This does not substitute for testing an active football fixture.
- 720pstream's current Next.js data supplies competition metadata and selected
  player props without executing scripts. Existing renewable players remain
  supported. At this checkpoint its football listings were future events with
  no published active player: the relay case was **skipped**, not passed. Native
  playback and a fresh live 720p football sample remain unverified.

No native Stremio GUI/decoder video or audio acceptance was performed for this
checkpoint. Local API/relay verification checks actual playlist rewriting,
credentials, keys and recognized/decrypted media—not the contents of a full match.

Focused, opt-in checks (never part of default offline tests):

```powershell
./gradlew.bat :engine:liveTest --tests app.futbolio.engine.ProviderEntryVerificationTest --tests app.futbolio.engine.LiveStremioRelayVerificationTest --console=plain
```

Redacted current relay reports are in `engine/build/reports/live-relay/`. These
record whether a **started match** or **continuous channel** was used. They do
not replace the stricter match-only cases; a channel pass must not be presented
as a match-only suite pass. Pending-event skips are explicit in the JUnit report.
