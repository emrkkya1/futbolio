# Shared engine and host boundaries

> The approved clean-architecture restructure is in progress. See
> [implementation evidence](RESTRUCTURE-PROGRESS.md) for current boundaries and
> verified gates. The single-module file inventory below is historical and will
> be replaced once service and adapter extraction is complete. The runtime
> safety and platform constraints remain requirements throughout the migration.

## Decisions

- Kotlin/JVM library shared by both hosts. No duplicate JavaScript providers, embedded Node runtime, browser scraping engine or native/JNI boundary.
- Compose Desktop and Android TV-specific Compose controls. UI layouts are platform-specific; engine models, state and behavior are shared.
- Ktor CIO handles inbound HTTP framing. OkHttp handles upstream HTTP/TLS and connection reuse. No custom HTTP parser or TLS implementation.
- jsoup handles HTML structure. Restricted JavaScript literal parsing is retained only for published data arrays; no downloaded code is executed.
- Configuration is typed/versioned JSON; desktop writes atomically and Android persists through DataStore. No database or executable provider-update system.
- Desktop is local-only by default, with explicit LAN interface selection and a bearer token in installation/media URLs. Android cannot be configured to expose a LAN listener.
- Separate Stremio IDs let desktop and same-device add-ons coexist. Shared listing IDs identify provider listings, not canonical fixtures. Match merging remains future work.

## Engine packages

The engine is intentionally one Gradle module, split by responsibility into source files. Additional Gradle modules are unnecessary until independent publication or dependency boundaries justify them.

- `Models.kt`: typed source/configuration/listing/playback models and identity.
- `Text.kt`: URL validation, HTML parsing, Turkish normalization and restricted literal parsing.
- `Providers.kt`: adapter registry and the three discovery implementations.
- `Network.kt`: injectable upstream interface, validated DNS destinations, redirect policy, cancellable response ownership and response limits.
- `Catalog.kt`: per-provider state, parallel refresh, expiration and selection.
- `Resolver.kt`: bounded/cached static stream resolution, iframe traversal and website fallbacks.
- `Relay.kt`: playlist rewriting, token registry, diagnostics and platform callbacks.
- `FutbolioEngine.kt`: lifecycle, Stremio API and incremental media forwarding.

Hosts inject poster rendering, source configuration, bind/advertised addresses and media-activity callbacks. The engine never reads platform preferences, calls UI APIs or acquires Android wake locks.

## Resource/safety boundaries

- Provider documents: 2 MiB maximum.
- Playlist rewrite buffer: 512 KiB maximum.
- Media forwarding: 32 KiB copy buffer, 64 MiB object limit, 45-second request deadline.
- Active media: four Android, eight desktop; excess requests receive 503.
- Active API calls: eight; excess requests receive 503.
- Resolver: two active listing resolutions, eight queued/in-flight identities, up to four alternate players and bounded iframe traversal.
- Resolution cache: 32 listings, twenty seconds.
- Catalogs: up to eight source configurations, 600 rows each; one-minute demand refresh interval, ten-minute stale-data limit.
- Relay registry: 4,096 opaque capabilities, thirty-minute inactivity expiry.
- Recent diagnostic events: thirty, without raw media URLs.

These are application-level bounds, not total process-memory promises. Framework/runtime/TLS allocations and Stremio's separate decoder process still consume memory.

Outbound DNS results must all satisfy the public-address policy; OkHttp connects to those returned addresses rather than resolving again independently. Redirects are revalidated, arbitrary forward-proxy configuration is disabled, credentials/fragments/unsafe schemes are rejected, and source header injection is blocked. HTTP Host validation protects the local listener against basic rebinding requests. This is not a claim that every internet deployment threat is covered; do not expose the service publicly.

## Dependency baseline

Kotlin 2.4.21, Compose Desktop plugin 1.12.2, Gradle 8.14.4, AGP 8.13.2, Ktor 3.6.1, OkHttp 5.3.2 and jsoup 1.23.2 are pinned. R8 is explicitly overridden to 9.1.57 for Kotlin 2.4 metadata support. Android UI libraries use a compatible stable Compose BOM with TV Material components. OkHttp 5.3.2 is deliberate: later 5.5.x Android artifacts require compile SDK 37, beyond this migration's installed SDK 35/tooling baseline. Upgrades should move the compatible set and rerun the short checks—not independently adopt every latest artifact.

## Verification layers

1. JVM fixtures: providers, parsers, catalog/protocol behavior, relay integrity, transport redirects/compression/cancellation and network restrictions.
2. Desktop host/UI tests: persistence, lifecycle, real LAN-interface API/relay access and basic provider editing/navigation.
3. Android instrumentation: actual service/API, loopback-only binding, posters, UI attachment/closure and the shared fixture's HLS/segment path. Remote navigation remains a manual acceptance check.
4. Manual acceptance: real Stremio install and decoded playback, actual second-device Wi-Fi reachability, long-match service survival, standby and low-memory recovery.

Fixture media is deterministic transport data, not decodable football video. Passing it does not replace layer 4.
