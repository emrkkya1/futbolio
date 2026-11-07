# Provider schedule corrections

## FutbolVivo (7 November 2026)

The live portal now redirects from `futbolvivo.online` to `pelotalibre.pro`. Its
`/api/agenda` returns separate date and time fields without an offset. Discovery
already follows the portal redirect before resolving the agenda endpoint.

The API's wall times match US Eastern time, not UTC. Independent ESPN comparisons:

- Galatasaray–Amed: API 11:00 → 16:00 UTC → **19:00 Europe/Istanbul**.
- Arsenal–Hull and Fulham–Newcastle: API 10:00 → 15:00 UTC.
- Peterborough–Reading: API 15:00 → 20:00 UTC.
- Toluca–Monterrey: API 20:00 on 7 November → 01:00 UTC on 8 November.

The default source zone is now `America/New_York`, so summer dates use daylight
saving rules instead of a hardcoded five-hour shift. The adapter continues to honor
explicit custom source timezones. Defaults revision 5 upgrades the old bundled UTC
setting only; custom endpoints, instances, zones, and removed sources are preserved.

## Duplicate grouping and provisional times

The live audit exposed specific naming variants: Amed/Amed SK/Amedspor/Amed SFK,
Sporting de Lisboa/Sporting Lisbon/Sporting CP, Estac Troyes/Troyes,
Peterborough/Peterborough United, Oviedo/Real Oviedo, Tigres/Tigres UANL, and
Defensa/Defensa y Justicia. These have exact aliases, not broad fuzzy matching.
Observed league spellings such as Spanish LALIGA and Portekiz Liga NOS are also
normalized to avoid creating false competition conflicts.

Reversed home/away listings with modest schedule differences can now use the same
exact-pair grouping rule as forward listings. Existing date, ambiguity, youth,
women's, and reserve-team protections remain.

When no verified fixture exists, agreement between independently configured sources
now takes priority over a lone explicit timestamp. Multiple alternate rows from
the same source count once, not as extra votes. Fixture-confirmed kickoff remains
authoritative, and conflicting provisional times still request fixture verification.

Sanitized real agenda extracts live under
`engine/src/test/resources/providers/states/2026-11-07/`. Regression tests cover the
reported kickoff, naming variants, provider consensus, UTC date rollover, migration,
and daylight-saving behavior. Run the metadata-only online comparison with:

```sh
./gradlew :engine:liveTest --tests '*LiveScheduleAuditTest'
```

The audit reports groups sharing a team, not automatically proven duplicates:
different opponents, dates, and competitions can legitimately share a club. It
never resolves or logs stream/player credentials.
