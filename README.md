# Futbolio

Live football discovery and HLS relaying for Stremio, with a Turkish-football focus.

**Two hosts, one Kotlin engine.** Desktop serves Stremio on this computer or trusted devices on its local network. Android serves Stremio on that same Android device only. Neither app decodes video; Stremio handles playback. No PC is required for on-device Android playback.

## Run

### Desktop

On Windows, launch `desktop/build/compose/binaries/main/app/Futbolio/Futbolio.exe` after building. The distribution includes its Java runtime; users do not need Java installed separately. Keep the complete `Futbolio` directory together.

On Linux, build on the target architecture with `./gradlew :desktop:packageDeb` (Debian/Ubuntu) or `./gradlew :desktop:packageRpm` (Fedora/RHEL). Install the generated package from `desktop/build/compose/binaries/main/deb/` or `desktop/build/compose/binaries/main/rpm/`. Packages include a bundled Java runtime; the Linux packaging toolchain (`fakeroot` for `.deb`, `rpm` for `.rpm`) must be installed on the build machine. Run `./gradlew :desktop:run` to launch from source.

1. Open Futbolio and click **Start Futbolio**.
2. Install the displayed manifest URL in Stremio, or use **Install in Stremio**.
3. To serve another device, stop Futbolio, open **Network**, select your private LAN address, save, and start again.
4. Install the newly displayed URL on the other device. Allow the port only on the private network if your firewall prompts.

The access token in desktop URLs is a bearer secret. Share it only with trusted users/devices. Do not expose the port publicly, configure router port forwarding, or rely on the token as an internet-facing security system. There is no TLS listener or per-user rate limiting. Guest Wi-Fi isolation and firewalls may prevent devices on the same Wi-Fi from communicating. DHCP address changes require updating settings and reinstalling the add-on URL. Keep the computer on and awake during playback.

Closing the desktop window hides it to the tray where supported. **Quit** stops the engine and exits; **Stop** leaves the UI available. Without a system tray, closing the window exits.

### Android TV

Install `android/build/outputs/apk/release/android-release.apk` (when signed) or the debug APK at `android/build/outputs/apk/debug/android-debug.apk`.

1. Open Futbolio, then **Start Futbolio**.
2. Try **Install / open Stremio**, or install `http://127.0.0.1:7000/manifest.json` through a supported Stremio interface.
3. Keep Futbolio's foreground service running during playback. The UI can be closed.

Android 8.0/API 26 or newer is required. There is no LAN listener, boot-time auto-start, embedded browser, Node runtime, or video decoder. Android can still stop the service under memory pressure.

**Installation varies by Stremio client.** Some TV versions do not accept deep links or expose a URL-install field. Account-sync installation on a desktop requires a compatible server at this exact localhost URL on the desktop during installation; the TV's localhost server is not reachable from a PC. Once synced, localhost on the TV means the TV. Web clients may require HTTPS. Futbolio cannot silently install add-ons into another app or account.

## Features

- Curated match catalogs: live/in-progress and starting within 24 hours only;
  league matches expire two hours after kickoff, cups/tournaments after three.
  Channels and the explicit provider workbench are separate from match filtering.
- Provider-first deduplication combines streams into one match entry, including
  team aliases and conflicting same-day kickoff times. Ambiguous competitions
  stay hidden until resolved. Ordinary browsing never fetches scoreboard enrichment.
- Fixture/status verification is limited to duplicate conflicts and selected
  matches: cached league/day requests, two concurrent calls, bounded queues and
  failure backoff. Metadata waits at most 350 ms; stream selection never waits.
  Unverified statuses are schedule estimates, not confirmed live-match evidence.
- Stremio discovery is organized as Live Now, Starting Soon, Turkish Football,
  Sports Channels, All Matches, and a search-only Futbolio catalog. Competition
  filters are scoped to each catalog; match details link to their competition
  and team search. Provider diagnostics remain in Futbolio, not in Stremio browsing.
- Square 640×640 match posters: stadium backdrop, bundled team crests,
  top-left kickoff, bottom-center competition mark, and a white negative-space
  Futbolio icon at bottom left. Compressed JPEGs keep catalog artwork lightweight;
  legacy PNG routes remain supported with the correct actual image MIME type.
  Artwork never downloads during browsing/rendering. The development snapshot
  includes a generic pack of 455 teams and 22 competitions, plus an official TFF
  supplement covering all 20 current 1. Lig clubs and the competition mark;
  missing assets use neutral badges. The TFF pack is refreshed separately with
  `scripts/import-tff-artwork.ps1` and survives generic pack regeneration.
  See `engine/src/main/resources/artwork/ARTWORK-NOTICES.md` for provenance and
  the logo-rights review required before public distribution.
- Turkish Football, All Football, and Sports Channels catalogs.
- Search, 100-item pagination, Turkish-team/league heuristics, Istanbul times, generated match posters.
- PPV, PapazSports, Taraftarium24, FutbolVivo, and 720pstream adapters; up to eight configured provider instances.
- Alternate PPV players/language labels, static HLS/DASH extraction, bounded iframe traversal.
- Explicit external-browser fallback when extraction or playback fails.
- Shared HLS playlist/segment/key/init/rendition rewriting, header preservation, MIME correction and single byte ranges.
- Incremental media forwarding with 32 KiB buffers, no segment cache or video files on disk.
- Per-provider health, partial-failure warnings, bounded diagnostics without raw stream URLs.
- Provider configuration import/export, including legacy Node and Android configuration envelopes.

## Providers and limitations

The bundled third-party domains are configurable, not verified official domains. Listing extraction does **not** establish authorization, availability, correct match mapping, or actual player compatibility. Provider domains and layouts change independently of Futbolio.

- **PPV:** consumes the public football JSON catalog and alternate embeds. JavaScript-only/protected players may yield only a website option. Obtain the operator's permission before public integration/deployment.
- **PapazSports:** follows published entry buttons/interstitials to the current portal, reads match/channel data without executing scripts, and relays its published VIP player protocol with media-driven token renewal. Browser choices use the resolved portal, not Bitly. Legacy data-script recovery is allowed only with a concrete player domain; it never fabricates shortener-hosted event links. Legacy test-playlist mappings remain explicitly unverified.
- **Taraftarium24:** follows the labeled entry link, discovers channels and the published match API, and resolves the published player mapping. Channels remain available if the optional match API fails.
- **720pstream:** discovers football match cards and resolves the nested player from literal/JSON data without executing scripts. The shared relay retains renewable sessions and origin-scoped AES-key credentials. Live discovery, local Stremio API, playlists, keys, and decrypted segments have passed; full-match native video/audio acceptance remains unverified. See `docs/STATIC-STREAM-RESEARCH.md` for focused tests and limitations.

No DRM, account/login, CAPTCHA, geographic restriction or paywall bypass is implemented. Use only streams you are authorized to access. There is no official live-score feed, guaranteed EPG, transcoding or codec repair. Time-only upstream listings are anchored to their discovery date in the provider timezone; without an upstream date, that date remains an assumption. Duplicate matching is conservative and cannot guarantee identity when provider names, dates or competition labels are incorrect.

## Architecture

The approved clean-architecture restructure is in progress. The build now has
`:domain` (pure identity/text/URL policies), `:application` (runtime policies and
time ports), and the existing `:engine`, `:desktop`, and `:android` modules.
Hosts use `engine.api.EngineControl`/`EngineFactory`; engine collaborators are
Kotlin-internal. Full service extraction and passive presentation layers are
still being migrated; the older overview below describes runtime behavior,
not the final package layout. See `docs/RESTRUCTURE-PROGRESS.md`.

```
desktop (Compose Desktop, LAN controls, tray, file settings, desktop posters)
    └── engine (Kotlin/JVM library)
android (Compose TV, foreground service, DataStore, Android posters)
    └── the SAME engine
```

The engine owns all provider handling, discovery, catalog selection/cache, stream resolution, Stremio API, HLS relaying, destination policy and diagnostics. Hosts supply platform lifecycle, configuration persistence, poster rendering and wake management. There is no runtime dependency on `football-stremio` or `football-tv`.

Kotlin/JVM is shared directly because both targets support JVM libraries; Kotlin/Native/KMP target abstractions are unnecessary for these targets. Networking uses OkHttp, the embedded HTTP server uses Ktor CIO, HTML parsing uses jsoup, typed configuration uses kotlinx.serialization, and concurrency uses coroutines/StateFlow. The only custom script parser accepts restricted data literals and never evaluates source code.

Refreshes are demand-driven with a one-minute interval. Failed providers retain successful listings for at most ten minutes; dated expired events are removed. Stream resolution is cached for twenty seconds. Relay tokens expire after thirty minutes of inactivity. Caches, concurrent requests, media sizes and resolution work are bounded.

## Build and check

Requires JDK 21, Android SDK platform 35/build tools, and network access on the first Gradle run. `JAVA_HOME` and `ANDROID_HOME` select installations. Windows helper scripts fall back to the existing `C:\emreky\android-tools` installations without modifying the system PATH.

```powershell
# Builds both hosts and runs JVM tests (desktop UI tests need a graphical session).
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1

# Android emulator/device smoke checks; defaults to emulator-5554, never picks a physical device automatically.
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/android-smoke.ps1
```

```sh
./gradlew :domain:test :application:test :engine:check :desktop:test :android:assembleDebug :desktop:createDistributable
./gradlew :desktop:run
./gradlew :desktop:run --args="--headless"
./gradlew :desktop:run --args="--check-sources"
```

`--check-sources` uses the new Kotlin implementation and contacts live providers. It reports extraction, not end-to-end playback. `--data-dir PATH` selects an isolated desktop settings directory for the UI/headless host. Linux desktop UI tests need a display, e.g. `xvfb-run -a ./gradlew :desktop:test`.

Windows distributable: `desktop/build/compose/binaries/main/app/Futbolio/`. `:desktop:packageMsi` creates an MSI where packaging prerequisites are available. macOS/Linux packages must be built and verified on those operating systems. Installer code signing/notarization requires your release credentials and is not supplied by this project.

### Android signing

Debug builds are development-signed. Release builds are optimized and unsigned unless production credentials are supplied:

- `FUTBOLIO_KEYSTORE`
- `FUTBOLIO_STORE_PASSWORD`
- `FUTBOLIO_KEY_ALIAS`
- `FUTBOLIO_KEY_PASSWORD`

For local optimized-build verification only:

```sh
./gradlew :android:assembleRelease -PdevelopmentSigning=true
```

That explicitly uses the Android debug key and is **not production signing**. Never commit signing keys or passwords. The Gradle wrapper distribution is checksum-pinned; dependency versions and lockfiles are committed.

## Migration

Original projects remain intact. Futbolio has new app/add-on identities:

- Desktop add-on: `community.futbolio.desktop`
- Android add-on: `community.futbolio.local`
- Android package: `app.futbolio.android`
- Media prefix: `fut:`

Both hosts can coexist in a Stremio account. Install the desired Futbolio add-on separately; old add-ons/library references are not silently replaced. Remove old add-ons only after verifying playback.

Use **Import JSON** to import a Node `config.json` or an old Android `{ "schema": 1, "sources": [...] }` envelope. Import intentionally replaces the provider list, not host/network settings. Legacy `host`, `port` and `publicUrl` are not imported. Android cannot read another package's private settings; export them if available, or recreate providers manually. TVs without a document picker can still use the provider editor.

Desktop settings live in `~/.futbolio/`: `providers.json` and `desktop.json` (contains the access token). Do not publish that directory. Writes are atomic where supported. Corrupt settings are reported rather than silently overwritten. Android uses private DataStore settings and disables app backup. Neither host uploads analytics or diagnostics; providers still see their incoming requests and your public IP.

See [manual acceptance checks](docs/MANUAL-TESTS.md) for real-device and Stremio verification.
