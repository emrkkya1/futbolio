# Minimum-first stream preparation

## Invariant

Every playable provider option remains visible and openable, even without metadata.
The HTTP stream endpoint waits only for necessary missing-provider link extraction,
never for optional metadata or a bandwidth benchmark. See
[ready snapshots](READY-STREAM-SNAPSHOTS.md).

## Ordered startup and background phases (0.4.5)

1. **Discovery and statuses.** Start the local server immediately. In the background,
   await the initial discovery attempts from all enabled providers (35-second
   per-source bounds), group matches, then make the bounded ESPN live-scoreboard
   request before ranking live candidates. ESPN failure/timeout fails open to
   schedule estimates; it does not block explicit stream selection.
2. **Priority minimum sets.** Rank visible Live Now/halftime matches and choose four.
   Finish every provider's ordinary link-extraction attempt for one match before
   moving to the next. Two extractions can overlap within the match. There is no
   sixteen-page cutoff: all providers of all four targets are considered.
3. **Priority enrichment.** Only after all four minimum sets have been attempted,
   sample up to three distinct provider kinds per match, up to two options each.
   Alternate pages/mirrors cannot manufacture extra provider slots. Measurements
   are globally serialized and individually bounded to six seconds. Each pass has
   a 45-second *active-probe* budget; minimum-link refresh time is separate. If a
   budget or foreground preemption interrupts enrichment, retain completed work
   and resume unfinished targets before expanding coverage.
4. **Expansion.** After the initial priority-enrichment attempts have completed,
   prepare minimum sets for other live matches in the same ranked order. Process
   at most four additional matches per pass, every provider of each selected match.
   Do not run benchmarks for the rest of the catalogue. Later passes advance the
   bounded expansion cursor rather than repeatedly fetching its first match.

“Complete” means each provider's ordinary attempt reached success, unavailable, or
timeout. It does not promise that broken providers yield streams or every probe
yields metadata. Completed phase barriers do not restart merely because the first
cache entry expired while a slow later provider was being fetched.

The shared weighted [interest score](MATCH-PRIORITY.md), not stream quality, chooses
both catalogue order and preparation priority. Only existing catalogue/ESPN data
and local rules are used; ranking performs no network calls.

## User selection is an override

If the selected match's complete minimum set is fresh, return all options immediately
with current information. Otherwise:

- Cancel unrelated extraction and measurement tasks, including an older selection.
- Promote in-flight minimum extraction for this match instead of duplicating it.
- Preserve every completed cache entry and any already-published information.
- Prepare all of this match's providers using the normal extraction path; do not
  wait for initial ESPN classification or optional enrichment.
- Return every playable option. Background preparation resumes after foreground
  work and its short quiet period unless playback has begun.

Selecting metadata can initiate this preparation before Stremio requests streams.
The HTTP request and scheduler share single-flight work. Background completion or
cancellation cannot overwrite a newer configuration revision.

## Minimum-set metadata

Reuse information a provider response already contains when parsing it is bounded
and cheap. For example, if normal extraction already fetched an HLS master playlist,
read its highest rendition's resolution, frame rate and advertised bitrate from
the first 64K characters without requesting the rendition or any segments.

Fetching another playlist, segment, key or speed sample solely for information is
enrichment, not part of the minimum set. No guessed resolution from “HD” labels and
no GOOD/MEDIUM/SLOW grade without the required evidence. Changed declarations
invalidate incompatible old evidence; expiring an old grade does not erase fresh
declarations in the current minimum set.

## Freshness, capacity and playback

- Prepared URLs retain a 20-second monotonic cache lifetime. Freshness maintenance
  has priority over the next expensive probe. Each maintenance wave refreshes at
  most two due listings, rotating through priority/recently selected matches so a
  slow provider cannot restart a full freshness barrier indefinitely.
- Evidence retains its own maximum 60-second lifetime from the oldest observation.
  Reuse it only for identical URL, credential headers and playback-session instance;
  refreshing links never resets sample age or re-downloads a fresh checked option.
- Protect priority and recently selected cache entries from expansion eviction.
  The default cache holds 64 listings. Expansion is best-effort warming, not a
  promise to retain every match in memory. Old lower-priority entries can be evicted.
- Continue bounded idle maintenance while the relay runs; there is no automatic
  90-second browsing-window cutoff. A memory/settings reset invalidates work and
  requires startup/configuration or interaction to re-enable it.
- Playback cancels speculative stream work and probes. The engine permits new work
  only when media is inactive and the 30-second media-activity quiet period has
  expired. Resume automatically afterward, without requiring another catalogue click.
  Explicit cold link extraction is still permitted when changing streams.

Slow networks, outages, expired credentials and finite cache capacity can still
cause cold extraction. Refreshing does not guarantee every target stays fresh at
every instant. Stremio responses are snapshots: later cache changes do not push
labels/providers into an already-open native list. Transport checks are not proof
of freeze-free playback on the Stremio device.

## Verification and historical observations

Deterministic tests cover the full priority-minimum barrier (including twenty
provider listings), phase ordering, empty/failed/timed-out providers, preemption in
minimum/enrichment phases, playback pause/resume, bounded expansion/cache protection,
expiry without starvation, and continued idle operation. Real-engine tests gate
provider discovery and ESPN independently, verify live/final status selection, and
exercise fail-open ESPN errors/timeouts. Endpoint tests keep optional probes blocked
while returning and opening every unrated stream through the actual relay.

Earlier 7 November experiments used the previous scheduler: the top match's first
links appeared after 525 ms, first grade after 5,391 ms, and the whole batch took
42.8 seconds, with no GOOD option observed. Those are historical observations—not
timings for the new minimum-first policy, complete-page readiness, or a controlled
same-stream speedup claim. Native full-match playback still needs manual validation.
