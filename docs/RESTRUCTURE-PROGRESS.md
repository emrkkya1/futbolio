# Clean architecture restructure: implementation evidence

The approved eight-step plan is being implemented incrementally. This is an
evidence log, not a claim that the entire restructure is finished.

## Implemented foundation

- Default tests no longer contain live provider tests. `:engine:liveTest` is an
  independent source set/task with the existing strict per-provider verifier.
  Legacy live environment flags cannot turn ordinary tests into live probes.
- Added sanitized catalog fixtures for all five providers and fixed listing/event
  ID, manifest, and configuration characterization tests.
- Added regression tests which first reproduced obsolete resolution work,
  stale workbench state after configuration changes, and retained capabilities
  after close. Resolution/workbench invalidation is revision-fenced; close is
  terminal/idempotent and clears capabilities, while provider edits preserve
  capabilities already issued to viewers.
- Credential-bearing diagnostic string representations are redacted without
  merging streams which have different credential profiles.
- A regression test reproduced unwanted 720p default addition to a legacy
  custom-ID PPV-only configuration. The migration now requires the bundled
  PPV instance ID; existing bundled migration tests still pass.
- Added `:domain` and `:application`, targeting JVM 17. Identity, plain-text
  classification and URL syntax policies now live in domain. Application owns
  injectable time and resource-limit contracts; resolver TTL uses monotonic time.
- Added allowed-module-edge enforcement and Kotlin-PSI import checks for the
  inner modules. More boundary/visibility checks will be added as extraction
  progresses; the new modules are not yet the home of all business services.
  A temporary Gradle init script deliberately added `:domain -> :engine`; the
  dependency gate rejected it, and the script was removed. PSI checks also
  exercise an in-memory forbidden import and ignore comments/string literals.
- Added `engine.api.EngineControl` and `EngineFactory`. Mutable catalog,
  resolver, token, workbench and diagnostics collaborators are internal.
  Desktop and Android now call the control contract. Host-level fake-engine
  tests make lifecycle behavior testable without a server.

## Verification (2026-11-02)

The first foundation run passed domain/application tests, engine checks, desktop
host/UI tests, live-suite compilation, Android debug assembly, and Android
instrumentation APK assembly, including an `--offline` Gradle run. All 126 JVM
tests passed (domain 3, application 2, engine 110, desktop 11), with no skips.

An incremental Android DEX collision recurred when recompiling the engine's
anonymous default media-activity callback. After replacing that callback with a
named no-op singleton, both a clean build and a subsequent changed-engine
incremental build passed. No toolchain dependency was upgraded for this change.

No provider live suite, emulator/device instrumentation, or native Stremio
playback has been run for this implementation. A CI job now isolates engine
tests in a loopback-only Linux network namespace; that CI job has not been run
locally on this Windows host. Local default tests enforce injected fixture
transport instead of production HTTP requests.

## Remaining

Complete entry-query/cache and configuration-transition ownership; separate HTTP,
relay/poster/lifecycle adapters and suspending disposal; passive desktop screens
and Android ViewModel/service bridge with no engine in UI state; full engine/host
boundary checks, security/performance, optimized-build, device and acceptance
verification; final architecture documentation. Existing runtime wiring and
policy forwarders are transitional and must not become permanent parallel paths.

## Core extraction and provider boundaries (second checkpoint)

- Domain now owns plain source/listing/catalog/match/preferences models, stable
  identities, matching, status/expiry and one catalog query policy. It has no
  serializer/HTTP/HTML/coroutine dependencies or implicit wall-clock reads.
- Configuration has explicit versioned storage DTOs and application migrations/
  validation. Defaults and supported adapters come from injected metadata, not
  domain constructors or positional list indexing. Legacy fields/IDs are tested.
- Application owns transport/provider/score/media-probe ports and the catalog,
  recovery, resolution, score and workbench services. Workbench phases/stages and
  playback confirmation are typed; explicit JSON mappers retain the wire format.
- All five adapters have their own packages, README and mirrored provider tests.
  Papaz/Taraftarium mapping and Papaz authentication are provider-local; generic
  shared extraction no longer owns their protocols. Registry metadata drives
  editor kinds/default timezones without provider-name branches in either host.
- CLI diagnostics use `engine.api.ProviderTools`, not host-owned provider adapters.
- Tests demonstrate an unknown future adapter through codec, validation, catalog,
  resolution, engine controls and workbench; a fake score feed replaces ESPN;
  same-kind provider instances remain isolated; actual blocked reads close on
  cancellation; obsolete discovery/probe work cannot publish into a new revision.
- Kotlin-PSI checks enforce inward imports, acyclic inner packages and no direct
  provider-to-provider imports. Historical IDs remain fixed, including raw-title
  identity seeds when cleaning old provider labels into plain domain values.

Latest verification: **164 tests passed, zero failures/skips** in the offline
minimum completion run. This included domain/application tests, engine checks,
desktop tests, Android unit tests, Android debug assembly, and instrumentation
APK assembly. A network regression now explicitly validates literal IPv4/IPv6
hosts because OkHttp bypasses its DNS callback for literal addresses. Device
execution, optimized release packaging, provider live tests, and native
Stremio playback remain unverified.
