# Predictive Live Now stream prefetch

## Selection

Select at most four visible, unambiguous Live Now matches (including halftime).
Upcoming, finished, cancelled, postponed, channel, and uncurated entries do not
participate. Interest ranking is separate from measured stream quality.

1. Senior Galatasaray, Fenerbahçe, and Beşiktaş matches.
2. Champions League.
3. Europa League.
4. Conference League.
5. Turkish leagues, then Turkish Cup.
6. Premier League.
7. LaLiga, Bundesliga, Serie A, Ligue 1, Primeira Liga, Eredivisie, then other
   already-curated competitions.

Favourite-team matches receive 10,000 interest points plus their competition score.
European competition scores are 900/800/700, Turkish competitions 600/590/580, Premier
League 500, and the remaining listed leagues 400/300/200/100/90/80. Ties prefer more
recent kickoffs and then stable IDs. User team aliases are respected; youth/reserve/
women's suffixes are not stripped to manufacture a senior-team favourite match.
The ranking is applied before the catalog's pagination limit.

## Scheduler

- Start speculating after the service starts; non-channel catalog requests renew
  a 90-second browsing-interest window. Health checks and poster requests do not.
- Provider snapshot changes and browsing wake preparation immediately. Also reconsider
  candidates every five seconds while interested, skipping fresh results.
  Reuse the catalog snapshot without adding scoreboard requests or another discovery
  loop. Provider discovery retains its own existing cache/refresh limits.
- Prepare at most sixteen listing pages per batch, highest-interest match first.
  Two preparations overlap, and their slots are released as soon as links are
  extracted—not after speed sampling. Ready links enter a short-lived cache.
- **Measure at most three distinct providers per match.** Provider identity is the
  adapter kind, so alternate player pages or configured mirrors do not manufacture
  extra provider slots. Pages returning no HLS choices do not consume a slot.
  Other providers' playable links remain available without a guessed connection grade.
- Bandwidth-heavy checks remain globally serialized. Preparation can overlap a
  measurement, so extraction traffic and unrelated network traffic can still affect
  the connection; this is not a perfectly isolated laboratory benchmark.
- Give the highest-interest match an initial eight-second measurement window, then
  do the same for lower-priority candidates. Consume whichever prepared provider is
  ready first: a slow first-listed provider cannot hide an already-ready alternative.
- Initially check one stream option per chosen provider. If it is MEDIUM/SLOW and
  there is no GOOD result yet, try one prepared alternative inside the same initial
  match budget. Deepen remaining options only after the initial match passes. Never
  re-download a fresh option that was already assessed.
- Check at most two choices per measured provider, two recent segments per choice,
  at most 512 KiB per segment. Four matches × three providers × two choices bounds
  media samples to 24 MiB per batch, plus bounded playlist/provider/key traffic.
  The whole background batch has a 45-second deadline; each check retains its
  six-second limit. Initial-window timeouts leave incomplete checks unrated.
- This intentionally bounds cost rather than continuously scanning every stream.
  Stremio catalog refreshes may renew interest even without a precise user-visibility
  signal; playback suppression and the per-batch limits still apply.

## Foreground selection and freshness

Foreground and background resolution share single-flight work. A cold selection
promotes preparation of **all** its providers and cancels unrelated speculation,
immediately releasing pending-admission slots. Ready snapshot reads do not cancel
measurements or wait for a foreground quiet period.

Ready cached provider results return without awaiting extraction or speed probes. A warmed
measurement can be reused for at most 60 seconds from the oldest observed sample,
using monotonic time for expiration. Check age is visible. Warming does not silently
keep evidence fresh without new measurements. All prepared links have their own
20-second cache lifetime, proactively refreshed independently of sampling. A fresh
link can survive expiration of its old grade; a fresh grade never extends an old URL.

Preparation and assessment have separate APIs and lifecycles. Progressive playlist/
sample observations update only the matching revision and prepared URL/session's
cache entry. Raw links do not become fresh merely because they waited in a measurement
queue. Expired prepared links are not measured, silently re-extracted by the measurement
stage, or resurrected as fresh cache entries. Completed first-option checks are reused
when a second option is assessed.

Selecting an in-flight warm-up never inherits its measurement deadline. Prepared
choices return immediately once every provider's link results are available; cold
providers still require normal extraction. Never omit playable alternatives just
because their metadata is missing or another provider is already ready. Stremio does not push later
cached grades/providers into an already-rendered page. The online test reports first
playable links, first grade, first GOOD result, fresh-listing count, and fully-warm-match
count separately from total batch duration.

The foreground stream endpoint now reads [ready snapshots](READY-STREAM-SNAPSHOTS.md)
instead of waiting for sampling. Cold providers still require normal preparation
so every playable stream is included; subsequent requests pick up progressively
available metadata without waiting for it. Link maintenance runs independently of sampling
and refreshes before expiry. Fresh evidence can be reused for exactly matching URLs,
headers and session instances without resetting its age. Workbench diagnostics remain
separate from match budgets.

URLs and credentials remain scoped to their original listing/session; no token/query
stripping is used to pretend a newly resolved stream is the previously measured one.
Renewable provider sessions continue to handle auth refresh. Short freshness does not
guarantee that every plain upstream URL will remain available or that quality will
stay constant; a later selection after expiry resolves and measures again.

## Playback, settings, memory, and shutdown

When relay playback starts, cancel speculative resolutions and active measurement
stages. Canceling a foreground measurement stage preserves the already-resolved
playback choices. While media is active, and for 30 seconds afterward, no new probes
are started. This also protects bandwidth during gaps between segment requests.
Normal host media-activity notifications are still forwarded unchanged.

Changing settings invalidates old-generation work. Memory trimming clears interest
and caches instead of immediately repopulating them. Shutdown stops the scheduler
and cancels its work. No scheduler or resolved streams are persisted across app runs.

## Connection levels

Measured grades are exactly **GOOD / MEDIUM / SLOW**, not interest-score tiers.
All three require two valid measured segment samples. GOOD requires at least 2×
headroom in both, MEDIUM has worst headroom in [1×, 2×), and SLOW has worst headroom
below 1×. Unknown, failed, unsupported, and incomplete checks stay ungraded; failure
is never converted to SLOW. These remain short transport observations, not native
player rebuffer guarantees. `LIMITED` in older validation reports is now called MEDIUM.

## Verification

Offline tests cover ordering, strict team matching, eligibility, pagination, cache
hits/expiry, breadth plus favourite completion, serialized measurements with parallel
provider preparation, whole-match promotion, foreground preemption, admission slots,
playback cancellation, interest expiry, memory/settings invalidation, and stopping.

```sh
./gradlew :domain:test :application:test :engine:test :desktop:test :android:testDebugUnitTest
./gradlew :engine:liveTest --tests '*LivePrefetchVerificationTest'
```

The online experiment uses real discovered candidates and real checks, then verifies
fresh cached selections make zero new upstream requests. It reports partial coverage
honestly and does not claim native playback was verified.

In the original 7 November live run, the selected candidates were Galatasaray–Amed, Göztepe–
Başakşehir, Nottingham Forest–Manchester City, and Stuttgart–Werder Bremen. An eight-
listing batch took about 24.8 seconds. All provider listings for the first two matches
were cached; the other two remained partially prepared. Fresh provider-cache lookups
completed in less than one millisecond at the test's timing resolution and made zero
new upstream requests. Some alternatives remained unrated or unavailable, which is
not the same as every stream being verified. Completing favourite matches before
expanding alternatives increased fully cached matches from one to two in this run.

After separating preparation and sampling and adding the three-provider cap, a later
live run's highest-interest match (Nottingham Forest–Manchester City) had playable
links after **525 ms** and its first measured grade after **5,391 ms**. No GOOD option
was observed in that run, so `firstGoodMillis` remained null. The complete background
batch still took **42.8 seconds**, including lower-priority matches, unfinished/slow
preparations, and refinement. These observations demonstrate why first-useful timings
must not be conflated with total-batch duration, complete-page readiness, or a promise
that any stream will be freeze-free. Network conditions and candidates differed from
the earlier run; this is not a controlled same-stream speedup claim.
