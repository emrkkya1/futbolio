# PPV resolution status

Discovery works; automatic inline playback is **not verified working**.

The duplicate `PpvProvider.kt` introduced during investigation broke compilation and contained an unused resolver with unsupported headers. It has been removed; the existing adapter in `Providers.kt` remains authoritative.

Playback now refreshes `/api/streams/<match path>` using the configured API base, reads bounded iframe/HLS variants, and falls back to the catalog's players if that lookup fails. Existing iframe extraction and website fallback remain in place. Signed media hosts and tokens are not hardcoded.

## Evidence

- The examined `taifood-blog.asia/embed/...` response has no plain HLS source. It loads `/js/bundle-jw.js` (or the Clappr variant).
- Offline inspection of encoded literal data in that bundle identifies another obfuscated player module at `/js/wasm/gasm.js`. No provider JavaScript was executed and TLS verification was not disabled in the app.
- A PPV-only live probe discovered 15 listings and tried Athletic Club / Real Sociedad and Atlanta United / Toronto. Both returned zero inline choices. Website fallback was correctly treated as failure, not playback success.
- Six new deterministic tests cover fresh API variants, embed Referer/Origin, multiple signed CDN hosts, invalid sources, refresh failure, cancellation, and obfuscated-only fallback.

## Fast reproduction

Run `:engine:test --tests app.futbolio.engine.PpvProviderTest` for fixtures. Set `FUTBOLIO_PPV_LIVE=1` to additionally run the live segment probe (60-second overall bound, at most two active listings). The live test must fetch media bytes to pass; another provider cannot make it pass. The environment flag is a Gradle test input.

## Remaining work

The user's `all-har` capture contains 56 request entries, including the per-match API response, HLS playlists, and media responses. The API wraps player sources in `data.sources`; the adapter and fixture now handle this envelope as well as flat source payloads. No HTML document, embed-page, bundle, or gasm request entries are present in this export, although their script URLs appear in initiator stacks (including `gasm.wasm`). It therefore does not contain the earlier stream-resolution input/module responses. Do not commit the HAR: it contains signed URLs and potentially sensitive request data.

Recover the fresh stream-resolution request/data transformation from the player module and implement its data protocol in Kotlin. A browser Network capture of the request immediately preceding the signed `index.m3u8` request (including request body and response) would narrow this substantially. The final signed playlist URLs alone do not reveal how to obtain fresh per-match tokens. Do not add an executable JS runtime, bypass TLS checks, or reuse expired sample tokens.
