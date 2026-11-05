# Building and development

## Build from source

Requires JDK 21, plus Android SDK platform 35 and build tools for Android tasks.
Set `JAVA_HOME` and `ANDROID_HOME` as needed. The first build needs network access
to download dependencies. On Windows, use `gradlew.bat` instead of `./gradlew`.

```sh
# Run the desktop app
./gradlew :desktop:run

# Build an Android debug APK and desktop distribution
./gradlew :android:assembleDebug :desktop:createDistributable

# Run core tests, architecture checks, and headless host tests
./gradlew :domain:test :application:test :engine:check
./gradlew :desktop:headlessTest :android:testDebugUnitTest
```

- Android debug APK: `android/build/outputs/apk/debug/android-debug.apk`.
- Desktop distribution: `desktop/build/compose/binaries/main/app/`.
- Desktop UI tests: `:desktop:test` (requires a graphical session).
- Native desktop packages must be built on their target OS. Packaged desktop
  distributions include Java; keep the complete application directory together.
- Live provider tests run manually with `:engine:liveTest`, never as CI gates;
  see [live testing](LIVE-PROVIDER-TESTS.md). Media retrieval alone is not proof of
  Stremio playback; use the [manual acceptance checklist](MANUAL-TESTS.md).

## Desktop operation

Start Futbolio, then use **Install in Stremio** or install its displayed manifest
URL. For another device, stop Futbolio, choose your private LAN address under
**Network**, save, restart, and install the new URL on that device.

LAN URLs contain a bearer access token. Share them only with trusted devices;
never expose the port publicly or configure router port forwarding. The listener
has no TLS or per-user rate limiting. Keep the computer awake; firewall rules,
guest Wi-Fi isolation, or a changed LAN address may interrupt access.

Closing the window hides it to the tray where supported; otherwise it exits.
**Quit** stops the relay. Desktop settings are in `~/.futbolio/`; `desktop.json`
contains the access token and must not be published. Android uses private app
storage with backup disabled.

## Application versioning

`futbolioVersion` in `gradle.properties` is the single version for the engine,
Stremio manifest, desktop package, and Android APK. Android's version code is
`major × 1,000,000 + minor × 1,000 + patch` (for example, `0.3.1` → `3001`).
Supported ranges are major `0..2099`, minor/patch `0..999`; `0.0.0`, prereleases,
build suffixes, and leading zeros are rejected.

Use a higher version for every published APK update, including rebuilds, and
update [CHANGELOG.md](../CHANGELOG.md). Releases use `vX.Y.Z` tags on the reviewed
commit after merging to `main`. Build every platform artifact from that same
commit and preserve the Android signing key so updates retain settings.
Debug-signed APKs are development builds, not production releases. Never commit
signing keys or passwords. See [CI and release operations](CI.md) for publishing,
signing setup, validation, and recovery.

## Architecture

Both apps share one Kotlin/JVM engine. `domain` contains matching and catalog
policies; `application` owns services and ports; `engine` implements providers,
networking, the Stremio API, and relaying. Desktop and Android supply lifecycle,
persistence, and presentation.

- [Architecture and safety boundaries](ARCHITECTURE.md)
- [Restructure progress](RESTRUCTURE-PROGRESS.md)
- [Historical verification notes](VERIFICATION.md)
- [Provider research and limitations](STATIC-STREAM-RESEARCH.md)
