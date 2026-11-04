# Changelog

The version applies to Futbolio as a whole: shared engine, Stremio manifest,
desktop application and Android application. Android's integer version code is
derived from that version using fixed-width numeric positions.

## 0.3.3

### Added
- Layered Android adaptive launcher icon with a dark background and a separate
  lime diamond foreground whose F remains a transparent cutout.
- White-only notification icon with a transparent background and F cutout.
- Enrich matched listings from ESPN's all-soccer scoreboard before returning
  catalogs: live scores and match minutes in descriptions and square posters.
- Prefix halftime titles with `HT |` and remove ESPN-confirmed ended matches.
- Share scoreboard requests with a short cache and timeout; unavailable,
  ambiguous, and unmatched data leave listings unchanged. Score updates retain
  listing IDs. Score/status changes invalidate poster URLs; minute ticks do not
  trigger whole-screen image reloads. Larger scores and minutes automatically fit
  their thumbnail areas.

## 0.3.2

### Added
- Publish signed, versioned Android APKs to GitHub Releases after successful
  checks on version-increasing pushes to `main`.
- Headless desktop unit-test task, Android debug/release lint gates, and tested
  release-version detection. Desktop packaging and GUI tests are excluded from CI.

### Changed
- Move all internet-backed provider and stream diagnostics under `engine/src/live/`
  for explicit manual execution; automated core tests remain network-isolated.

## 0.3.1

### Fixed
- Restore missing provider sources on live matches through Turkish competition
  recognition, team aliases and evidence-backed midnight date correction.
- Group listings sharing a normalized team and kickoff minute, including reversed
  home/away order and transitively connected listings.
- Keep Taraftarium region/quality annotations from hiding an entire match.
- Support 720p's published renewable-player configuration across static data
  layouts; prevent large inline advertising scripts from exhausting parser budgets.
- Remove website-only choices from Stremio's stream results.
- Make desktop dependency locking portable across host operating systems while
  retaining framework/native version pins; stop preparation daemons before
  network-isolated CI and prefetch complete test runtime artifacts.

### Added
- Sanitized, dated real-provider regression states and offline resolution tests.
- Safe resolution diagnostics and opt-in per-provider/live-relay investigation.
- One application version in `gradle.properties`, generated shared runtime version
  information, and a derived Android version code with enforced component limits.

### Verification
- 208 core JVM tests and 4 Android host unit tests passed for the 0.3.1 build;
  Android debug APK and desktop distribution assembled successfully.
- PSV Eindhoven–Club Brugge and RB Leipzig–Manchester City each returned seven
  inline choices; media verified through the local relay from PapazSports,
  Taraftarium24, FutbolVivo and 720pstream on 4 November 2026 UTC.
- User confirmed native playback of PapazSports, FutbolVivo and 720pstream on a
  Mi Stick. Occasional 720p freezes remain under investigation; long-session
  renewal and full-match stability are not yet verified.
- All 18 desktop host/UI tests also passed on Linux.

## 0.3.0

- Initial repository baseline with shared Kotlin engine and desktop/Android hosts.
