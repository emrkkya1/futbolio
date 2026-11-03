# PPV adapter

Discovers the public football JSON catalog and refreshes player variants at
selection time. Generic static HLS/DASH/iframe extraction supplies inline media;
JavaScript-only/protected players fall back to the website. No account or DRM
bypass. Stateless adapter; playback state belongs to each resolved session.

Tests: sanitized `providers/ppv/catalog.json`, `PpvProviderTest`, and the explicit
`:engine:liveTest` per-provider case. Discovery is not verified media or playback.
