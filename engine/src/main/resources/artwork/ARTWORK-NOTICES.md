# Futbolio artwork

## Background photograph

Arena Corinthians 2, Jorge Morales Piderit (2018).
https://commons.wikimedia.org/wiki/File:Arena_Corinthians_2.jpg
CC0 1.0: https://creativecommons.org/publicdomain/zero/1.0/
The photograph is cropped, reduced, darkened and overlaid in match posters. The
venue is illustrative and is not represented as the venue of any listed match.

## Team and competition marks

The index records the source URL and identity of each bundled logo, obtained
from ESPN's public football metadata/CDN and the Turkish Football Federation's
official club/logo pages. These are third-party identification
marks, not Futbolio artwork. Copyright and trademark rights remain with their
respective owners. Public availability is NOT a redistribution licence or an
endorsement of Futbolio. This development asset pack does not establish rights
for public/commercial distribution; obtain applicable permissions and review
each mark before publishing a release. No blanket open-source licence is claimed.

Assets are snapshotted at development time with scripts/import-artwork.ps1 and
scripts/import-tff-artwork.ps1. The separate index-tff.json preserves reviewed
TFF aliases/crests when the generic ESPN pack is regenerated. The TFF snapshot
covers the 20 clubs listed on https://www.tff.org/default.aspx?pageID=142;
each entry records its official club page and image URL. The competition mark
is from https://www.tff.org/default.aspx?pageID=1314. PNGs are proportionally
resized to 192×192 with transparent padding; the marks are not redrawn.
Neither builds nor poster/catalog requests download or update artwork. Coverage
is finite and season-dependent; missing/ambiguous identities use neutral badges.

## TV channel marks

`index-channels.json` records 24 channel identification images, their original
source URLs and SHA-256 digests. The snapshot was obtained from the published
PapazSports channel artwork directory using `scripts/import-channel-artwork.ps1`.
It covers beIN 1–5 / Max 1–2, S Sport 1–2, Tivibu Spor 1–4, Spor Smart 1–2,
NBA TV, TRT 1/2/Spor/Yildiz, A Spor, TV8.5 and Eurosport 1–2.
Provider hosting is not evidence of ownership or permission to redistribute.
These marks remain owned by their respective broadcasters; public/commercial
releases require a separate rights review, just like the club/competition pack.
Futbolio is not endorsed by these broadcasters. Runtime lookups are exact
reviewed aliases (ignoring punctuation/quality suffixes), never network requests
or guesses between numbered channels. Unknown channels keep the text fallback.
