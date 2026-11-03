# PapazSports adapter

Follows the published entry link and parses portal attributes or restricted
literal-data scripts. An optional data-script recovery is explicitly a listing
fallback, not proof the player domain works. Its own extractor owns the published
channel/test mapping; shared static code contains no Papaz branches. The named
VIP player protocol supplies only the supported published player authentication.

Entry resolution reads Bitly's literal button data and its explicit "Continue
to destination" interstitial, with bounded navigation and no script execution.
Listing/browser URLs use the resolved portal domain, never the shortener. Script
recovery is refused when only a shortener URL is known. VIP sessions renew on
media traffic before the published 40-minute refresh interval, or once after a
401/403; opaque relay capabilities preserve current tokens on playlists/media.

Tests: sanitized `providers/papaz/catalog.js`, parser/player regression cases,
and opt-in `:engine:liveTest`. Website-only is a non-pass for media verification.
