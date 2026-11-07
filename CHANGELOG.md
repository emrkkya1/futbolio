# Changelog

The version applies to Futbolio as a whole: shared engine, Stremio manifest,
desktop application and Android application. Android's integer version code is
derived from that version using fixed-width numeric positions.

## 0.4.3

### Fixed
- Automatically try another port when AirPlay or another service occupies the
  configured port, disposing failed startup attempts and remembering the working
  port. Installation and copy actions use the actual running endpoint.
- Warn users when a non-default port is used: existing Stremio installations on
  port 7000 must be removed and re-added with the new add-on URL.

## 0.4.2

### Added
- Bounded HLS connection measurements with GOOD, MEDIUM, and SLOW grades,
  resolution detection from H.264 segment headers, and bitrate/frame-rate metadata.
- Predictive preparation of up to four likely Live Now matches, prioritizing
  major Turkish teams and competitions, with at most three measured providers per
  match. Preparation runs independently of serialized bandwidth checks and pauses
  during playback.

### Changed
- Sort match catalogues by the same descending interest scores used for predictive
  prefetch, before pagination; equal scores retain live-first and kickoff ordering.
- Compact Stremio stream entries: provider and connection grade in the name;
  resolution, frame rate, and bitrate on one title line with measurement age below.
  Unmeasured and failed checks stay unrated; playable alternatives remain listed.

### Fixed
- Interpret FutbolVivo's default schedule in Eastern time, migrate its old bundled
  UTC default, and reconcile observed match aliases without losing providers.
- Correct partial-transfer timing estimates and a response-close cancellation race.

## 0.4.1

### Changed
- Darken the primary green from `#4FA14C` to `#3A8638` so white button text meets
  the 4.5:1 body-text contrast target, also applied to the launcher icon.
- Restyle square posters to the brand palette: dimmed bundled stadium backdrop,
  bundled Manrope typography, a verified-live green pill, lavender secondary
  labels, and the green diamond mark with a transparent F cutout. Poster URLs
  advance to v=8 so Stremio refetches restyled artwork.

## 0.4.0

### Added
- Check stable GitHub releases when opening the Android app, with an update
  toast, a persistent green update arrow, and a download/install action.
  Downloads verify GitHub's SHA-256 digest, package, version, and signing key;
  Android permission and installation confirmation remain required.
- Persisted Times and Scores On/Off control for live score/clock display in
  Stremio descriptions and posters, without changing kickoff times, fixture
  enrichment, or finished-match filtering.
- Bundled Kodchasan and Manrope fonts and filled Material Design Icons, with
  their license notices included.

### Changed
- Redesign Android TV around relay controls on the left and stream sources on
  the right; mobile uses a compact header and independently scrolling source
  list with a visible scrollbar and fixed bottom actions.
- Use a flat dark-gray background, green primary actions, restrained lavender
  accents, softened diamond/pointed buttons, and TV focus outlines.
- Simplify provider popups to availability, event count, and stream-fetch status,
  with Test Provider and Close actions instead of cards, URLs, and report search.
- Unify popup action sizing, add Close icons, and provide compact, device-specific
  Stremio installation instructions with a full-width Copy URL action.
- Simplify Android Settings to Diagnostics, Check for updates, and Times and Scores.
- Replace the Android launcher icon's solid backdrop with a green diamond and
  transparent F cutout; launchers may still apply their own icon treatment.

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
