# FutbolVivo adapter

Discovers the agenda JSON and published channel links. Source timezone defaults
to UTC through registry metadata; alternate player/language hints are retained.
Generic bounded static extraction handles published inline media. No downloaded
scripts execute; unsupported players fall back to the external website.

HTTP redirects establish the actual browser/player referer base. The current
agenda also marks some NHL/NFL rows as football; title-level sport exclusions
prevent those rows from masquerading as active football verification targets.
Continuous channels can be verified separately when no football match is active.

Tests: sanitized `providers/futbolvivo/agenda.json`, UTC/variant/extraction tests,
and explicit `:engine:liveTest`. No unguarded provider-specific live test exists.
