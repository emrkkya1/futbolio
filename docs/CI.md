# CI and application releases

## Required checks

`.github/workflows/check.yml` runs on pushes and pull requests:

- **Offline core tests**: `:domain:test :application:test :engine:check`, including
  architecture checks and fixture-backed provider/API/relay integration tests.
  Dependencies are prepared before running test JVMs in a Linux network namespace
  with loopback only. Gradle's `--offline` is not itself a network sandbox.
- **Host unit tests and Android lint**: `:desktop:headlessTest`,
  `:android:testDebugUnitTest`, `:android:verifyVersioning`, `:android:lintDebug`,
  and `:android:lintRelease`. It also tests the version-detection helper and checks
  commit whitespace. Android lint is the current static-analysis gate; Kotlin
  formatting/detekt are not configured.

Both core jobs run on Linux/JDK 21 and upload reports even on failure.
Two additional desktop packaging jobs run on every push/PR:

- **Desktop Linux AppImage** (Ubuntu 22.04, JDK 21): run `:desktop:test` under Xvfb
  with software rendering, build a self-contained runtime, wrap it as an AppImage,
  and launch the actual AppImage without requiring FUSE. Ubuntu 22.04 sets the
  build baseline; older glibc systems and musl/Alpine are not supported targets.
- **Desktop Windows exe** (Windows, JDK 21, WiX 3): run headless host tests, build
  `:desktop:packageExe`, and smoke-test the bundled Windows launcher/runtime.

The native smoke test loads all bundled fonts and serves a real loopback manifest
with no provider configuration or user settings. It does not prove installer UX,
graphics-driver compatibility, or Stremio playback on every target machine.
Linux Compose tests cover dashboard/provider management, network controls, and
error handling. Desktop assets and SHA-256 sidecars are uploaded as workflow
artifacts on PRs as well as main. Tests/reports/screenshots are retained on failure.
There is no emulator/device execution. Internet-backed provider cases belong
under `engine/src/live/` and remain manual-only through `:engine:liveTest`.

## Release trigger and output

On a push to `main`, compare `futbolioVersion` against `github.event.before` (the
commit before the entire push, not just `HEAD^`). The helper validates the same
stable numeric version ranges as Android. Decreases fail; unchanged versions or
an existing `vX.X.X` tag skip release. An initial branch push with no predecessor
can publish an untagged version. Tags are fetched with full history. No version
is bumped automatically.

Only after core checks and both desktop packaging jobs pass, a new version builds `:android:assembleRelease`
using the production signing configuration (never `developmentSigning`). The APK
signature is verified, then the APK is uploaded as both a workflow artifact and
a GitHub release asset named `futbolio-vX.X.X.apk`. GitHub creates the `vX.X.X` tag
at the checked commit, with generated release notes. PRs, non-main pushes, and
tag pushes cannot publish. A dependent publication job attaches the already-tested
desktop assets to that same GitHub release:

- `futbolio-vX.X.X-linux-x86_64.AppImage` and its `.sha256` sidecar
- `futbolio-vX.X.X-windows-x86_64.exe` and its `.sha256` sidecar

Both contain a Java runtime; Windows installers use per-user installation and a
stable upgrade UUID. Desktop binaries are currently unsigned (no Windows code
signing certificate is configured), and SmartScreen warnings may occur. The
AppImage builder is pinned to appimagetool 1.9.1 and a verified SHA-256; its verified
embedded runtime is reused rather than downloading a floating second runtime.
An explicit **Run workflow** dispatch on `main` may also publish the current
untagged version, after the same checks; use this to recover a failed release
when workflow fixes were committed without changing the application version.

## Signing setup

Configure repository Actions secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` using
GitHub's secret settings or a secure interactive setup. Never put secrets in
issues, logs, source files, or chat. Preserve the same signing identity and a
secure backup for all subsequent versions, including any existing installations
signed with that identity. The keystore is restored to a private runner temporary
file and removed even when the build fails. Passwords are passed only to the
build step through the existing `FUTBOLIO_*` environment variables.

Normal check/packaging jobs have read-only repository permissions; only main
publication jobs have contents-write permission. Releases use `GITHUB_TOKEN`.

## Recovery and local validation

Missing signing secrets fail before the release build; checks are independent of
them. Fix configuration and rerun failed jobs. A failed release
can also be recovered after workflow fixes by dispatching **Checks** on `main`,
provided its version has not been tagged yet. A successfully published version
is immutable in this workflow: never overwrite its APK, reuse its version, or
delete its tag merely to rebuild. Publish a higher version for a changed APK.
If a tag/release was created but upload/publication was interrupted, inspect it
manually rather than blindly rerunning publication; the detector skips existing
tags. Workflow artifacts can recover an interrupted publication. If only desktop
asset upload failed after the release was created, rerun that failed job in the
same workflow (preserving its original release decision). Do not overwrite already
published files; inspect the release assets before recovery.

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py' -v
./gradlew :domain:test :application:test :engine:check
./gradlew :desktop:headlessTest :android:testDebugUnitTest :android:verifyVersioning :android:lintDebug :android:lintRelease
# With the existing FUTBOLIO_* signing environment configured:
./gradlew :android:assembleRelease
# Linux desktop (JDK 21 and Xvfb):
xvfb-run -a ./gradlew :desktop:test :desktop:createDistributable
bash scripts/desktop/package_appimage.sh
python3 scripts/desktop/smoke_native.py dist/desktop/futbolio-v0.4.4-linux-x86_64.AppImage --appimage --version 0.4.4
# Windows (JDK 21 and WiX 3 on PATH):
./gradlew.bat :desktop:headlessTest :desktop:packageExe
```

Dependency downloads require network access before offline runs. Headless desktop
tests may skip the self-hosted LAN fixture when no LAN interface is available;
that does not enable any real-provider requests. Native Stremio playback and
full-match stability still require the manual acceptance checklist.
