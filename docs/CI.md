# CI and Android APK releases

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

Both jobs run on Linux/JDK 21 and upload reports even on failure. There is no
Xvfb, Compose UI execution, emulator/device execution, or desktop packaging.
`DesktopUiTest` stays available through the existing manual `:desktop:test` task;
the headless task runs the remaining desktop tests. Internet-backed cases belong
under `engine/src/live/` and are manual-only through `:engine:liveTest`.

## Release trigger and output

On a push to `main`, compare `futbolioVersion` against `github.event.before` (the
commit before the entire push, not just `HEAD^`). The helper validates the same
stable numeric version ranges as Android. Decreases fail; unchanged versions or
an existing `vX.X.X` tag skip release. An initial branch push with no predecessor
can publish an untagged version. Tags are fetched with full history. No version
is bumped automatically.

Only after both check jobs pass, a new version builds `:android:assembleRelease`
using the production signing configuration (never `developmentSigning`). The APK
signature is verified, then the APK is uploaded as both a workflow artifact and
a GitHub release asset named `futbolio-vX.X.X.apk`. GitHub creates the `vX.X.X` tag
at the checked commit, with generated release notes. PRs, non-main pushes, and
tag pushes cannot publish. Windows/Linux desktop releases are deferred.

## Signing setup

Configure repository Actions secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` using
GitHub's secret settings or a secure interactive setup. Never put secrets in
issues, logs, source files, or chat. Preserve the same signing identity and a
secure backup for all subsequent versions, including any existing installations
signed with that identity. The keystore is restored to a private runner temporary
file and removed even when the build fails. Passwords are passed only to the
build step through the existing `FUTBOLIO_*` environment variables.

Normal check jobs have read-only repository permissions; only the main release
job has contents-write permission. Releases use the built-in `GITHUB_TOKEN`.

## Recovery and local validation

Missing signing secrets fail before the release build; checks are independent of
them. Fix configuration and rerun failed jobs. A successfully published version
is immutable in this workflow: never overwrite its APK, reuse its version, or
delete its tag merely to rebuild. Publish a higher version for a changed APK.
If a tag/release was created but upload/publication was interrupted, inspect it
manually rather than blindly rerunning publication; the detector skips existing
tags. The workflow APK artifact can help recover an interrupted publication.

```sh
python3 -m unittest discover -s scripts/ci -p 'test_*.py' -v
./gradlew :domain:test :application:test :engine:check
./gradlew :desktop:headlessTest :android:testDebugUnitTest :android:verifyVersioning :android:lintDebug :android:lintRelease
# With the existing FUTBOLIO_* signing environment configured:
./gradlew :android:assembleRelease
```

Dependency downloads require network access before offline runs. Headless desktop
tests may skip the self-hosted LAN fixture when no LAN interface is available;
that does not enable any real-provider requests. Native Stremio playback and
full-match stability still require the manual acceptance checklist.
