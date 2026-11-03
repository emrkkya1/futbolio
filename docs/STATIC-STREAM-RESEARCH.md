# Static stream resolution: 720pstream

## Result (2026-11-02)

**Confirmed:** fresh, HTTP-only resolution and media fetching for 720pstream, without executing website JavaScript, loading a browser, bypassing TLS verification, or reusing captured signed URLs.

The Kotlin/JVM probe passed for both:

- Nashville SC / Red Bull New York: two AES-encrypted segments fetched and decrypted into 1,458,504 and 1,423,912 bytes of MPEG-TS.
- Atlanta United / Toronto: the same probe, changing only the match-page input, fetched and decrypted 1,438,952 and 1,395,148 bytes of MPEG-TS.

Decrypted data was checked for 188-byte packet alignment and MPEG-TS sync bytes across four packets. This is evidence of actual media fetching/decryption, **not** a claim of on-device video/audio playback or full-match uptime.

## Futbolio integration

720pstream is now registered in the shared production Kotlin engine and bundled defaults (revision 4), exposed in both hosts' provider settings and workbench. Standard older configurations containing PPV gain the provider on import; empty/custom-only configurations and subsequent explicit removals are respected.

The production Stremio API/local relay live test passed for Nashville: discovery → stream response → local master/variant playlists → local AES key → two local encrypted segments → 2,951,036 verified decrypted MPEG-TS bytes. The packaged Windows application independently discovered 98 listings and fetched keys/segments for Nashville and Atlanta via its provider workbench.

`Stream720Provider.Session` owns the server-side auth state. All rewritten child URIs retain that same session. Media traffic triggers mutex-serialized renewal using the server's new session/code/expiry/device fields, `refresh_after` (bounded to 5–300 seconds), and `reauthorize_at`. HTTP 401/403 gets one bounded renewal/retry. There is no per-match background task. The auth header is restricted to the exact HTTPS auth origin and rechecked on every redirect hop; media CDN requests do not receive it. Secrets never appear in Stremio URLs or proxy header hints.

Deterministic tests cover discovery, config migration, literal parsing, scoped credentials, concurrent renewal, device rebinding, bounded unauthorized retries, cancellation, fallback, and the full local Stremio playlist/key/media route. JVM regressions and desktop tests passed. Desktop distributable and Android debug APK built successfully; an initial stale D8 transform collision was resolved by cleaning generated engine/Android build outputs.

No Android device/emulator was attached during this integration. Native Stremio video/audio playback and full-match survival remain manual acceptance checks.

### Subsequent phone/catalog fixes

Phone testing exposed TV-only touch controls and a catalog stall despite healthy provider discovery. Phones now use touch-capable Material buttons, wrapping action rows and narrower margins; television mode retains TV buttons/focus behavior.

Catalog snapshots now wait at most two seconds for the first usable provider result, rather than the entire refresh batch. Match aggregation precomputes team/fixture keys and listing identities instead of reparsing names during every pairwise comparison. Concurrent catalog requests share a short-lived aggregate snapshot. Diagnostics record snapshot/aggregation stages and response-generation timing.

An Android emulator reproduced `SocketTimeoutException` on the original touch APK using a populated 400-match catalog through the local HTTP API. The updated APK passed the same test, returning 100 entries in 3,437 ms, and all 27 Android smoke checks passed. This reproduces and improves an Android catalog failure, but the user's physical phone and native Stremio video/audio still require confirmation. The new fixture is in `android/src/androidTest/.../SmokeInstrumentation.kt`; desktop-scale aggregation and slow-provider regressions are also covered by JVM tests.

## Data protocol

1. GET the match page, e.g. `https://720pstream.cx/live/soccer/nashville-sc`. Parse the `iframe.video[src]` URL. Nashville currently points to an `embedsports.me` player.
2. GET that embed with the match page as Referer. Read the script element's `data-pldm`, `data-pid`, `data-play`, `data-player`, and `data-gc` attributes, plus the inline JSON `siteConfig` object. Do not load advertising scripts.
3. Construct `https://<data-pldm>/sd0embed/<data-gc>` with query parameters `pid`, `v`, `player`, `csrf`, `csrf_ip`, `expires`, and `sec_hash`. Values come from that fresh embed response, not a channel-name mapping. GET with the embed as Referer.
4. The nested player exposes its initial manifest URL as Base64 encoded via a literal integer array (`String.fromCharCode`). Decode **only** that data. The current URL ends in `/manifest.ts`, despite being an HLS master playlist. Likewise decode the auth base and `X-CSRF-Auth` value; read the stream, device, expiry, session, and host IDs from literal player data.
5. GET the auth endpoint (currently `seckyes.cc`) with `scode`, `stream`, `expires`, `u_id`, `session_id`, and `host_id`; supply `X-CSRF-Auth`, JSON Accept, nested-player Referer and Origin. Require successful session JSON. Set the manifest's `u_id` to the returned `device_id`.
6. Fetch the HLS master and selected variant, resolving relative playlist paths against each actual playlist URL. CDN domains changed across fresh attempts (`ragice.autos`, `mhys.ink`); they are deliberately **not** hardcoded.
7. The media playlist currently uses standard `AES-128`/identity encryption. Fetch its key URI with the auth header, **only on the auth host**, plus player Referer/Origin. The response is a raw 16-byte key. Fetch segment URIs without the auth header; use the playlist IV and AES-CBC/PKCS padding to validate plaintext MPEG-TS.

The site's `embed.min.js` confirms steps 2–3 directly. No interpreter, `eval`, browser automation, executable plugin, or downloaded WASM is part of this protocol probe.

## Reproduction

Production adapter: `engine/src/main/kotlin/app/futbolio/engine/Stream720Provider.kt`. Tests: `engine/src/test/kotlin/app/futbolio/engine/Static720pProbeTest.kt`. The former research-only resolver has been removed; tests now call production code and the local Stremio API/relay.

PowerShell, from the project directory (with the usual JDK/Android SDK environment):

```powershell
$env:FUTBOLIO_720P_LIVE = '1'
Remove-Item Env:FUTBOLIO_720P_MATCH -ErrorAction SilentlyContinue
./gradlew.bat :engine:test --tests app.futbolio.engine.Static720pProbeTest --console=plain

# Same flow, different match; no source edits:
$env:FUTBOLIO_720P_MATCH = 'https://720pstream.cx/live/soccer/atlanta-united-fc'
./gradlew.bat :engine:test --tests app.futbolio.engine.Static720pProbeTest.liveFreshResolutionDecryptsTwoSegments --console=plain
```

Unset `FUTBOLIO_720P_LIVE` to run the seven deterministic fixture tests. Both environment values are Gradle test inputs. The live test uses the production transport's bounded redirects, timeouts, sizes, verified TLS and DNS-pinned public-address validation. Logs exclude signed URLs, key material, and auth tokens. Cross-origin redirects never receive scoped credentials.

## Limits and production follow-up

- This is a registered shared-engine provider. No Node runtime or duplicate JavaScript provider is added to either app. General extra headers remain restricted; the new authentication field is separate and origin-scoped.
- Parsing is reusable across matches using this observed player template, not a universal JavaScript interpreter. Literal layouts changing will fail closed. The fixture tests cover fresh parameters, URL encoding, variable CDN/auth hosts, and rejection of executable expressions.
- The live decryption assertion expects MPEG-TS and an explicit IV. Production does not decrypt or flatten the playlist: it preserves AES declarations, rotation, implicit IVs, byte ranges, alternate audio and discontinuities for Stremio, rewriting every external URI through the shared relay/session.
- Renewal is implemented and covered with a simulated clock and concurrent requests. Real full-match survival has **not** been measured. The original player credentials are finite-lived (the website itself stops after two hours); selecting the stream again starts a fresh player/auth session if those credentials expire. Indefinite recovery across full player-token expiration is not claimed.
- Match availability is time-dependent. An ended match can stop resolving even when this protocol remains correct.

Without a match override, the live test discovers matches from the configured provider, checks the Stremio football catalog API, and tries up to three currently started matches selected from discovery. No match or stream URL is seeded. At least one candidate must yield two decrypted MPEG-TS segments through the local relay. No active candidates or no verified media is a test failure, not a discovery-only pass. A match override still has to exist in the discovered results.

## Other supplied sites

- `v2.sportsurge.net/live-sports/`: HTTP 403 during the initial static fetch; no working segment path established. No browser challenge bypass attempted.
- `livextv.pro/sport/football`: its frontend bundle currently reads `https://api.ppv.st/api/streams` and renders iframe sources. This points back to the PPV source family, not a demonstrated independent simpler media protocol. No segment success claimed for LiveXTV.

Do not commit HARs, downloaded player pages, auth JSON, signed media URLs, or AES keys. Those were kept outside the project during investigation.
