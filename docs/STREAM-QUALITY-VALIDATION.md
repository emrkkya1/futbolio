# Stream quality validation

## Scope

`LiveStreamQualityValidationTest` is an opt-in online experiment, separate from
offline unit tests. It compares the short production assessment against three
independent full-segment transfers per choice. Providers and choices are tested
sequentially to avoid dividing available bandwidth between our own probes.

The test retains normal authentication, renewable sessions, source headers, and
validated transport. Full transfers are bounded to 8 MiB and 18 seconds per sample.
Raw stream URLs and credentials are not logged. Local decrypted media samples used
for test-only inspection are deleted after probing.

These measurements are transport evidence, **not native-player verification**.
They cannot establish a false-positive/false-negative rate for actual rebuffers.
The full transfers happen after the snapshot; network conditions and CDN caching
can change between the two. Three samples are also insufficient to prove sustained
reliability. A fastest/highest-quality rendition is not the same as the rendition
an adaptive player would actually choose.

## Initial live results, 7 November 2026

The first run tested five playable choices across four providers, with 15 complete
segment downloads. PPV produced no playable current candidate through the existing
resolver and is excluded rather than counted as a bad stream.

- PapazSports: GOOD snapshot, 2.58 seconds; full-transfer headroom 7.84–10.34×.
- Taraftarium24: LIMITED snapshot, 2.18 seconds; full-transfer headroom 1.41–1.55×.
- FutbolVivo choice 1: UNKNOWN after 6.01 seconds and one sample; full-transfer
  headroom 3.62–5.44×. A timeout must not be interpreted as a bad stream.
- FutbolVivo choice 2: LIMITED snapshot, 5.08 seconds; full-transfer headroom
  1.79–4.78×, showing why a single optimistic sample is not enough.
- 720pstream: SLOW snapshot, 2.88 seconds; full-transfer headroom 0.41–1.31×,
  with two of three full downloads slower than real time.

No GOOD-versus-repeatedly-slow or SLOW-versus-consistently-high-headroom
contradiction occurred in that small run. This is not evidence of zero classification
errors in general. Network and stream changes require repeated testing.

Later repeated rounds confirmed substantial variation: a previously slow 720p
choice became borderline, and several short checks reached their six-second deadline
even when full transfers subsequently showed usable headroom. One FutbolVivo option
had an inconclusive baseline because its media could not be fetched. Such cases
remain UNKNOWN/INCONCLUSIVE, not false evidence of a bad stream. The report flags
potential GOOD/repeatedly-slow and SLOW/consistently-fast contradictions for review;
they are not a statistically calibrated playback error rate.

## Corrected estimator bias

The previous partial-download extrapolation scaled the entire elapsed request time
by `full size / sample size`, incorrectly multiplying TLS/auth/request startup delay.
It now separates time until response headers from payload transfer time:

```text
estimated full transfer = header delay + payload time × full bytes / sampled bytes
headroom = segment duration / estimated full transfer
```

A regression test models 800 ms startup plus 25 ms for a 512 KiB sample of a 3 MiB,
3-second segment. The old calculation reported 0.61× (SLOW); the corrected estimate
is 3.16× (GOOD), consistent with a full transfer taking about 950 ms. This corrects a
specific mathematical false-negative mechanism; partial downloads still extrapolate
throughput and cannot predict later stalls or rate changes.
Decryption, header parsing, and partial-response cleanup are also excluded from the
network transfer timer, so probe CPU/cleanup work does not penalize the connection.

## Bitrate and resolution

Segment-average transport bitrate is `total bytes × 8 / EXTINF duration`. A proper
206 `Content-Range` or 200 `Content-Length` can supply total size without fetching
the whole segment. This includes audio and container overhead; it is neither pure
video bitrate nor proof of picture quality. Variable bitrate means adjacent segments
can legitimately differ. It must remain labeled a segment estimate.

Resolution is structural metadata, not something to guess from bitrate. A bounded
pure-Kotlin parser now reads H.264 SPS dimensions from up to 64 KiB of the existing
TS sample, including PES reconstruction, packet continuity, high profiles, cropping,
and interlacing. It performs no extra HTTP request and does not invoke a decoder.
Observed segment-header dimensions are labeled separately from playlist declarations.
HEVC parameter sets and fMP4 initialization metadata are possible extensions, not
implemented by this fallback. Encrypted HLS first needs its authorized identity key and correct IV.
Missing parameter sets, unsupported codecs, and insufficient prefixes must stay
unknown. The live experiment includes an optional external `ffprobe` comparison on
the full segment and 16/64/512 KiB prefixes; ffprobe is test-only, not a production
dependency. Inspection failures are reported explicitly, not counted as successes.
The system ffprobe in this environment cannot create its OpenH264 decoder. Its failed
inspections do not establish that the streams are broken. Header-parser results can
still be compared with available playlist declarations, but independent ffprobe
resolution verification remains outstanding with a functional decoder build.

The pure header parser was exercised on four real choices: Papaz 960×540,
Taraftarium 1280×720, FutbolVivo 640×360, and 720pstream 1280×720. All four exposed
the same dimensions from 16 KiB as from a larger segment sample, with local parser
times of approximately 362–508 microseconds in that round. Available FutbolVivo and
720pstream declarations agreed with the header dimensions. This proves a useful
fast path for these observed AVC/TS streams, not universal prefix sufficiency.

## Match-page overhead

The current implementation awaits assessment before returning `/stream/...json`:
up to six seconds per active check and seven seconds overall per listing, with two
checks active globally. The first live run added 2.18–6.01 seconds of assessment
after provider resolution had taken 82–1,954 ms. A slow check can dominate the wait.

The `/meta/...json` path does not run these stream probes, so the match's descriptive
metadata is not directly delayed by them. The playable stream list is. Cached
resolution responses avoid repeat probes for 20 seconds.

Recommended next design: serve cached evidence immediately, bound any inline work
to a small response budget, and collect richer evidence from actual relay traffic.
Do not silently assume that background work can update an already-rendered Stremio
stream list: its resource response is a snapshot, not a push subscription. Background
results can improve a later request, while the first uncached list may say unknown.
Avoid additional active benchmarks competing with playback.
Passive relay measurements must distinguish upstream transfer delay from time
blocked writing to a slow/paused player, or they can introduce another false-slow bias.

## Reproduction

```sh
FUTBOLIO_VALIDATION_CHOICES=2 ./gradlew :engine:liveTest --tests '*LiveStreamQualityValidationTest'
FUTBOLIO_VALIDATION_PROVIDER=papaz ./gradlew :engine:liveTest --tests '*LiveStreamQualityValidationTest'
```

Reports are timestamped JSON files under `engine/build/reports/` with the prefix
`live-quality-validation-`. For stronger calibration, repeat across different times,
networks, adaptive renditions, and controlled bandwidth/latency profiles, then compare
with actual Android TV/Stremio startup and rebuffer events over a full match.
