# Weighted match interest

Catalogue ordering and automatic stream preparation share one deterministic scorer
and identical tie-breaking. It uses local editorial rules plus already-available
fixture data, not new requests, betting odds, standings or a current-form model.

The score is an additive breakdown (`MatchPriorityScore`):

- **Favourites:** 10,000 if Galatasaray, Fenerbahçe or Beşiktaş is present, plus
  1,000 when two of them meet. This is dominant: even the highest possible
  non-favourite matchup cannot outrank one of these clubs.
- **Competition:** Champions League 2,400; Europa League 2,000; Conference League
  1,700; Süper Lig 1,500; TFF First League 1,350; Turkish Cup 1,300; Premier League
  1,200; LaLiga 1,100; Bundesliga 1,000; Serie A 950; Ligue 1 850; Portuguese league
  750; Eredivisie 700. Unknown competitions receive a neutral 100, subject to normal
  catalogue visibility/curation rules.
- **Both teams:** a small explicit local list assigns 250 or 140 points per known
  senior club, otherwise zero. This is an editorial prominence list, not a claim
  about current performance. Squad qualifiers are never stripped to borrow senior
  popularity. User aliases and existing provider aliases are respected.
- **Matchup:** 350 for two 250-point clubs, plus 250 for a known derby/rivalry.
  Home/away reversal does not affect these components.
- **Activity:** live 180, halftime 160. Upcoming fixtures receive 120 within 15
  minutes, 80 within one hour, or 30 within four hours. Ordinary schedule-based
  live estimates receive the same base live bonus as verified-live games.
- **Verified urgency:** only a fresh ESPN live fixture with a valid clock and score
  qualifies. From minute 60, a draw gets 40 or a one-goal game 20, plus 25 before
  minute 75 or 50 afterward (at most 90 total). Missing, stale, future-dated or
  malformed evidence is neutral; no urgency is inferred from elapsed wall time.

This is weighted relevance rather than an absolute league ladder: Liverpool–Bayern
in the Europa League can outrank an ordinary Champions League fixture. Favourites
remain dominant regardless. Missing ESPN details cannot generate a negative penalty;
the optional verified-urgency bonus is deliberately small.

Ties use live/halftime status, kickoff ascending, title and stable ID. Scores and
normalized team keys are memoized per sort rather than recalculated at every
comparison. Channels keep a neutral score. Visibility, genre/search filters and
pagination stay independent; ranking happens before pagination.
