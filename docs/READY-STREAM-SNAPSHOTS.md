# Ready stream snapshots

The Stremio stream endpoint reads an immutable snapshot of prepared links and the
metadata available **at request time**. It never waits for a connection benchmark.

**Rule #1: include every playable stream from every provider, with or without
metadata.** Additional information must never delay or filter the stream list.

## Request path

- Opening a match's metadata or stream list marks that match as the current
  preparation priority, including a selected match outside the Live Now shortlist.
- If all providers have fresh prepared results, return the complete stream list
  immediately, including every unrated option.
- If any providers are cold, extract their links as in the original flow, using
  bounded concurrency and the existing provider timeouts. Do not return a partial
  list merely because one provider is already ready. This necessary extraction
  is separate from collecting additional metadata or speed samples.
- Metadata collection continues independently of the HTTP request, and progressively
  published information becomes visible on the **next** request. A pending check
  does not block returning the provider's already-extracted choices.
- A snapshot already returned to Stremio is not mutated. Stremio has no standard
  mechanism for pushing these changes into an already-open stream list.

All playable options remain listed regardless of whether they were measured. Failed
or incomplete checks are never guessed SLOW. If another provider's extraction takes
longer than cache TTL, preserve links extracted for the current request instead of
dropping them to wait for additional information.

## Background maintenance

The [minimum-first scheduler](PREDICTIVE-STREAM-PREFETCH.md) finishes all providers
for the priority four before optional enrichment, then expands minimum coverage to
other live matches. Due link refreshes outrank the next probe. Maintenance considers
links within ten seconds of their 20-second expiry, with at most two concurrent
extractions; an in-flight sample never blocks ready-cache reads.

Sampling retains the three-distinct-provider cap per match, serialized bandwidth
checks, per-check/active-probe budgets, and the four-match priority shortlist. A cold
selection overrides unrelated preparation and probing, without discarding completed
cache entries. Idle maintenance continues until stopped/reset or paused for playback;
there is no 90-second interest cutoff. After playback's quiet period it resumes
automatically. A cold selection may still extract links while another stream is
playing; it does not start a bandwidth test.

## Freshness and safety

- Cache expiry is independent of measurement freshness. A sample never extends a
  cached URL's lifetime. Expired cached results require normal link extraction;
  links already extracted for the current request are not dropped because of TTL.
- Fresh observations may be reused after extraction only when URL, credential
  headers and playback-session instance all still match. Refreshing links does
  not reset sample timestamps or download the same fresh samples again.
- Old measurements expire independently (at most 60 seconds from their oldest
  observation); fresh links and their free minimum metadata remain usable, but
  expired grades disappear until another check supplies evidence.
- Configuration changes invalidate the revision and cancel obsolete extraction
  without returning old configuration data. Work and cache size remain bounded.

This reduces waiting, but cannot guarantee that every match is always ready: initial
startup, browsing after inactivity, provider outages, expiring credentials, and slow
extraction can still cause a cold wait or missing details. Resolution/bitrate are
shown only when supported by provider/playlist/segment evidence; no values are
invented to fill a compact stream row. Transport grades do not prove freeze-free
native playback.
