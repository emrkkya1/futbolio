# Taraftarium24 adapter

Follows the labeled entry link, discovers channel links and the published match
API, and resolves its own player mapping. Optional API failure keeps channels
available with a warning. All network access uses the injected validated port;
no script execution or domain scanning.

Tests: sanitized `providers/taraftarium/landing.html`, fixture API/relay tests and
preroll exclusion. Run actual-provider verification only with `:engine:liveTest`.
