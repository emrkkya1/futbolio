# Stream listing metadata

Futbolio performs bounded HLS transport checks on selection and proactively warms up
to four likely Live Now matches during app startup/catalog browsing. It does not run
continuous all-catalog speed tests. Ordinary results use a 20-second monotonic cache;
measured warm-ups use at most 60 seconds from the oldest observed sample. Both share
the resolver's single-flight lifecycle. See [predictive prefetch](PREDICTIVE-STREAM-PREFETCH.md).

## What users see

- **GOOD**: both sampled segments had at least 2× transport headroom.
- **MEDIUM**: both were measured, but the worst was between 1× and 2×.
- **SLOW**: two samples were measured and at least one arrived slower than real time.

These are the only measured connection levels. Unsupported formats, fewer than two
samples, and timeouts are **Not rated**. Transport/media/key failures are **Not rated ·
check failed**, not a guessed SLOW rating. Measured results show their check age.

Streams are ordered in the above order, with untested streams before buffering-risk
and failed streams. Within measured categories, greater headroom comes first; unknown
choices remain in provider order. Failed checks do not remove playback options.
The Stremio name is `Provider · GOOD` (or the corresponding grade/unrated label).
The title has at most two lines: `1920x1080 · 50fps · 5.2 Mb` and `4s ago`.
Missing values are omitted. Commentary titles, codecs, headroom, sample counts, and
explanatory text are not displayed in this compact listing. Duplicate streams merge
provider attribution and retain the strongest available assessment.

Master playlists can additionally supply resolution, frame rate, codecs, advertised
bitrate, and whether multiple adaptive renditions exist. The highest-bandwidth rendition is
sampled: a fast low-quality rendition must not imply that the player can sustain the
highest quality. These are playlist declarations, not decoder-verified specifications.

## Measurement and resource policy

- Use the same validated upstream, headers, and renewable session as playback.
- Follow at most three nested playlists, each bounded to 512 KiB.
- Sample the last two advertised segments, reading at most 512 KiB each.
- Request `bytes=0-524287`. Accept both correctly formed 206 responses and servers
  that ignore ranges with 200. Reject truncated bodies and nonzero range starts.
- For a complete segment, headroom is media duration / request-and-download time.
- For a partial segment, use its total size from `Content-Range` (206) or
  `Content-Length` (200), its playlist duration, and measured transfer speed to
  extrapolate headroom. With no total size, fall back to an advertised bitrate.
  Require at least 32 KiB of evidence for either estimate; otherwise stay unknown.
- Extrapolate payload transfer time only; include request/header delay once. Scaling
  startup latency with byte count unfairly penalizes high-latency, fast connections.
- Direct media playlists can therefore supply **segment-estimated bitrate**.
  Internal metadata distinguishes estimated and advertised values; the compact
  Stremio listing shows only the bitrate, not its provenance or headroom.
- Report the worse of the two measurements, not an optimistic average.
- Require recognized TS/MP4 signatures; for identity AES-128 media, fetch the sampled
  keys and decrypt only the signature prefix using each segment's key/IV binding.
  Handle key rotation and clear segments explicitly. This is not a decode test.
- Leave byte-range, gapped, and unsupported-encryption playlists untested.
- Default limits: one bandwidth check active globally per runtime, four choices per foreground listing,
  six seconds per check, seven seconds total assessment budget per listing (including
  semaphore waits). Completed results survive the batch deadline. Provider resolution
  has a separate deadline, so a slow check never removes successfully resolved streams.
- Background link preparation overlaps up to two providers independently of speed
  sampling. Measure at most three distinct providers per match, with two checked
  choices per measured provider and a 45-second whole-batch deadline. Initial match
  passes have an eight-second window and publish ready links/observations promptly.
  Selecting a match promotes all its pending
  providers together, cancels unrelated speculation, and never inherits the longer
  background assessment deadline. Playback cancels/pauses probes; it is followed by
  a 30-second quiet period. Details and data limits are in the prefetch document.
- Metadata and individual sample results are published progressively so a deadline
  does not erase an already-observed resolution, bitrate, or buffering-risk signal.
- Responses are closed on completion, failure, and cancellation. No raw URLs,
  credentials, or provider error messages are added to listing metadata.

## Limits of the signal

This is a short transport snapshot from the device running Futbolio. It cannot
promise freeze-free playback or measure a different Stremio device's connection.
Server load, network conditions, adaptive selection, device decoding, DRM, stale
playlists, and future segment availability can still affect playback. The listing
explicitly says it is not a playback guarantee. Non-HLS sources remain untested.
Partial samples assume throughput is representative of the remaining segment. Request
latency and other network traffic can make estimates conservative. One sample
alone cannot earn any connection-quality grade. No resolution or frame rate is inferred
from a provider's brand, `HD` label, language, or segment size.

## Observations from real providers

The opt-in `LiveStreamMetadataAuditTest` was run against all five bundled providers
during development. These are observed examples, not hardcoded provider guarantees:

- **720pstream:** renewable session; master playlist with three renditions: 1280×720
  at 3.75 Mbps peak, 864×480 at 1.2 Mbps, and 432×240 at 0.55 Mbps. AVC/AAC codecs
  were declared, but frame rate was not. Media used AES-128 keys and ~3-second segments.
- **PapazSports:** renewable session; direct media playlist; provider-supplied `HD`/
  `SD` and `tr` labels; typically 10-second segments. No declared resolution/bitrate.
  Valid playlists used `text/html`, and TS segment responses used `image/jpeg`.
- **Taraftarium24:** direct media playlist, ~4.8-second segments; no declared resolution/
  bitrate. Also served playlists as `text/html` and TS segments as `image/jpeg`.
- **FutbolVivo:** sampled ESPN channel returned a direct media playlist, ~5.255-second
  segments, no declared resolution/bitrate. Agenda entries expose embed name/language.
- **PPV:** catalog/detail JSON includes source tags, locale, viewers, and source
  variants. Three sampled current matches produced no playable choices through the
  existing resolver, so their media metadata could not be verified. Viewer count is
  not used as a connection-quality proxy. This audit is not evidence that every PPV
  listing is unavailable.

All four reachable media sources supported 206 byte ranges with total segment size.
This evidence drove the direct-playlist size/duration estimator. The original
two-second deadline produced almost exclusively unknown results and was replaced.
The audit also exposed an OkHttp body-close/read race on cancellation: production
transport now cancels the HTTP call first and closes the body after its reader unwinds.

Re-run the redacted audit with:

```sh
./gradlew :engine:liveTest --tests '*LiveStreamMetadataAuditTest'
```

The audit logs schemas and allowlisted media metadata, not raw credentials or media
URLs. Network/provider failures are reported independently; an audit task completing
does not mean every provider or stream played successfully.

## Further opportunities

See [live quality validation](STREAM-QUALITY-VALIDATION.md) for measured results,
estimator limitations, bitrate/resolution extraction, and stream-list latency.

1. **Passive relay telemetry:** use actual segment transfer times, errors, and playlist
   sequence advancement to improve confidence without extra probe traffic. Isolate
   samples by authenticated stream identity; expire quickly and avoid mixing users.
2. **Playlist freshness:** detect repeated media sequences and stale program dates
   over time. A single fetch cannot reliably establish that a live playlist is stalled.
3. **Live delay:** use program date/time only where trustworthy and clock-aligned;
   distinguish actual latency from playlist-window size.
4. **Verified audio metadata:** surface language, codec, and channel count from HLS
   rendition declarations without guessing languages from provider location.
5. **Player feedback:** confirmed startup time, rebuffers, and decoding errors would
   be stronger evidence than transport alone, but require a supported player feedback
   path. The addon stream API does not supply these events.
