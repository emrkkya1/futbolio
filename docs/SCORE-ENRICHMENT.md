# Match score enrichment (0.3.3)

Before returning nonempty match catalogs or match metadata, Futbolio requests:

```text
https://site.api.espn.com/apis/site/v2/sports/soccer/all/scoreboard?limit=150
```

One shared demand-driven request covers all providers and competitions. A
successful snapshot is cached for 30 seconds; the request (including queue time)
has a 1.5-second deadline. There is no background polling. Stream selection uses
the current snapshot without waiting for ESPN, and poster rendering never makes
network requests.

## Display behavior

- **Live:** score replaces `VS` between the crests on square thumbnails; the
  minute appears at the bottom right, e.g. `47'` or `90+4'`.
- **Halftime:** the returned title begins `HT | `, the score remains visible,
  and the thumbnail clock reads `HT`. Descriptions include halftime and score.
- **Ended:** an ESPN-confirmed final is removed from all ordinary catalogs and
  search results. Final statuses are retained across later missing/failed
  snapshots so an already-ended match is not resurrected.
  Fresh ESPN live/halftime status takes precedence over the existing estimated
  match-duration cutoff, including stoppage/extra time; stale data does not.
- **Scheduled:** existing title/artwork stays intact; no artificial 0–0 score or
  running clock is displayed.
- **No match / ambiguous match / API failure:** retain existing listing content
  and kickoff-based behavior. Never guess a score or running minute. After a
  failed refresh, old live scores are discarded and retries back off 15 seconds.

Matching requires both normalized teams/explicit aliases, one unique ESPN event,
and a kickoff within 12 hours. Squad qualifiers (youth/women/reserves) are kept;
abbreviated aliases cannot silently remove them. Reversed provider team order
reverses the displayed score to match its crests. IDs, provider listings, team
names, competition, and provider kickoff remain unchanged by this display overlay.
The legacy `scoresEnabled` JSON property is retained for configuration import
compatibility; enrichment is automatic rather than controlled by that old flag.

Scores/minutes older than five minutes are not displayed. No clock is advanced
locally: displayed minutes come from ESPN. Poster URLs include the score and
match status, **not the minute**: minute-only updates must not cause whole-screen
image reloads. Descriptions still receive the latest minute; a cached poster may
show an older one until a goal/status change or a natural image refetch. When an
image is actually requested, it is rendered from the current snapshot.

There is no push notification or forced client refresh. Stremio must fetch fresh
catalog/metadata before learning a changed poster URL; whether an already-open
home screen reloads it is client-specific and is not guaranteed by the add-on
protocol. Neither clicking a match nor a fixed refresh interval is a portable
guarantee. No aggressive catalog polling or cache invalidation is introduced.
Logos remain bundled/offline.

ESPN is an optional, unofficial external data source, not a live-status or match
identity guarantee. API outages must not prevent listings or stream playback.

## Offline verification

`ScoreEnrichmentTest` covers matching, reversed order, ambiguous/unmatched
fixtures, squad boundaries, stable IDs, and finished-match visibility.
`ScoreboardServiceTest` covers coalescing, cache expiry, deadlines, backoff,
cancellation, and reconfiguration. `EspnScoreEnrichmentTest` uses a fixed JSON
fixture and a real loopback HTTP server to verify catalog descriptions, halftime
prefixes, ended-match removal, poster models, and poster-URL invalidation without
any live API dependency. `PosterDesignTest` verifies rendered score/clock regions.
