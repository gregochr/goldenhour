### Fixed — lunar eclipse L0: moonset/moonrise pairing and a CodeQL null-guard

Two review findings against the L0 catalogue/calculator (PR #911), fixed in place.

**`LunarEclipseCalculator`** picked moonrise and moonset as two independent "nearest to the umbral
window" lookups, each querying only the window's own London civil date(s). For a same-day evening
eclipse (2028-12-31: rises in shadow at 15:36 GMT) that civil date's only moonset candidate was a
leftover ~08:34 GMT set from the *previous* arc — hours *before* the rise, not after it — producing
a chronologically impossible sight. Moonrise and moonset are now derived as one paired arc: the
calculator queries a full day of margin either side of the umbral span (up to four civil dates),
picks the operative moonrise as the most recent rise at or before U4, then derives moonset as the
earliest set strictly after that specific moonrise — which makes `moonset > moonrise` true by
construction rather than a fact to remember to check. `LunarEclipseSight` also validates the
invariant defensively in its own compact constructor, so a future regression fails at construction
rather than shipping.

**`LunarEclipseCatalog.LunarEclipse`**'s compact constructor had a CodeQL-flagged possible-null
dereference of `u2`/`u3` in the TOTAL contact-ordering check: the presence guard
(`kind == TOTAL implies u2/u3 non-null`) lived in one `if` block and the dereference in another,
which a static analyser cannot connect. The presence guard and the ordering check that depends on
it now live in a single per-kind block, so the null check immediately precedes the dereference it
protects.
