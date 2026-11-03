# Verification — 1 November 2026

## Implemented and built

- Shared Kotlin engine consumed by both hosts; no runtime imports from the two legacy projects.
- Windows Compose Desktop distribution with bundled runtime, LAN controls and tokenized URLs.
- Android debug and R8-optimized development-signed APKs, minimum API 26.
- All three provider adapters, catalogs/search/pagination/metadata, resolution/fallbacks, HLS relay, configuration editing/import/export and diagnostics.
- Gradle dependency lockfiles generated and wrapper distribution checksum pinned.

## Passed checks

- **53 engine JVM tests:** providers, data-only parsing, network restrictions, compression/redirects, cancellation ownership, catalog/cache behavior, Stremio API, HLS rewriting and byte integrity.
- **8 desktop tests:** persistence, lifecycle, configuration updates, real assigned-LAN-interface API/relay access, PNG posters, Compose navigation and provider editing.
- **26 Android API 29 emulator checks:** actual foreground service, local-only listener, posters, activity closure, shared fixture resolution/relay, preserved headers, ranges, two consumers forwarding 40 MiB with SHA-256 integrity, failure retention and shutdown. Recorded in `test-results/android-smoke.txt`.
- Final optimized APK installed and rendered. Explicit initial remote focus was added after startup-control testing; D-pad Start then activated the foreground service and exposed the manifest. The API remained available after leaving the activity, and the service could be stopped through the UI.
- APK signature verified: **Android Debug development certificate**, not a production release key.
- Live discovery from the desktop Kotlin implementation returned PPV 19, PapazSports 164 and Taraftarium24 38 listings at the time of checking. This is extraction, not decoded playback.

## Remaining manual acceptance / known uncertainty

**The optimized APK's live provider requests failed in this emulator**, although the same providers were reachable from the Windows host and the Android fixture relay passed. The cause was not established; do not assume either physical-TV connectivity or provider playback is verified. Check live provider status on the real TV first and report diagnostics if all providers remain unavailable.

Also not verified here:

- Actual video/audio rendering inside Stremio, installation/deep-link behavior and account synchronization on the user's Stremio version.
- A second physical device reaching the desktop over Wi-Fi (the desktop LAN interface itself was tested locally).
- Full-match service survival, standby/reconnection and severe memory pressure on the physical Mi Stick.
- macOS/Linux packaging and production installer/APK signing.

This is a **manual-acceptance development build**, not a claim of universally verified live playback or production release readiness. See `MANUAL-TESTS.md` for the short handoff checklist. Original projects remain intact for rollback.

## Handoff artifacts

- `dist/Futbolio-0.2.0-windows.zip` — extract the complete folder and run `Futbolio/Futbolio.exe`.
- `dist/Futbolio-0.2.0-tv-development.apk` — optimized, development-signed Android build.
- `dist/MANUAL-TESTS.md` and `dist/SHA256SUMS`.
