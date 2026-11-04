# Champions League provider state extracts — 2026-11-04 UTC

Sanitized, reduced extracts from real responses observed while PSV Eindhoven–Club
Brugge and RB Leipzig–Manchester City were live (approximately 21:24–21:45 UTC).
These are frozen parser inputs, not live URLs or recorded video.

Preserved: provider field names/types, team spellings, original schedule dates and
clock values, source variant structure, Turkish competition/restriction labels,
Next.js encoding, player bootstrap shape and script ordering. Public numeric event
and channel IDs are non-secret listing metadata.

Replaced/removed: credentials, device/session IDs, signatures, real hostnames,
tracking/advertising implementations, unrelated listings and decorative markup.
Use `.example` addresses and obvious `fixture-*` values only. Some timestamps in
the renewable player/auth state are moved forward to keep fixtures deterministic.
The auth file retains observed response keys, with fixture values substituted.
720p's large advertising script is represented by a size marker; the test expands
synthetic non-data JavaScript to recreate scan-budget starvation without copying
third-party code. Smaller clean bootstrap responses use the same configuration
shape and are covered separately by removing that noise marker.

Regression expectations:

- All five catalogs contribute to the two canonical fixtures at the frozen clock.
- Papaz/Taraftarium clock-only rows remain attached after midnight in Istanbul.
- Brujas/Club Brugge and Leipzig/RB Leipzig do not lose provider sources.
- Turkish UEFA names and region/quality notes do not hide the match.
- 720p resolves the selected event, reads either clean or ad-heavy player data,
  authorizes a renewable session, and keeps credentials scoped/redacted.
- Website-only resolutions never appear as Stremio streams.

Raw diagnostic snapshots can contain credentials and must stay outside Git. Add
new dated state extracts rather than replacing this failure history when an
upstream changes. Never treat a snapshot test as proof of current live playback.
