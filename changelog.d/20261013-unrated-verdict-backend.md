### Changed — a weather-triage pass is no longer served as "Worth it"

`DisplayVerdict` gains `UNRATED` ("the weather screen did not rule it out, and no trustworthy rating
has called it"). With no rating, a weather-triage `GO` or `MARGINAL` now resolves to `UNRATED` instead
of `WORTH_IT` / `MAYBE`; a triage `STANDDOWN` still reads `STAND_DOWN` (a decision against the slot)
and no triage at all still reads `AWAITING`. A rating still decides exactly as before. Since the
verdict sample gate (2026-09-29) that fallback was the common case at T+2 and T+3: on 2026-10-13
Northumberland & Tyneside's Friday sunrise read "Worth it" on weather alone over 38 ratings the gate
had withheld (mean 3.6), and its sunset read "Maybe" over a withheld 2.1 mean, while a region with no
ratings at all was blanked to "Not scored at this range" — more evidence buying a bigger claim. A
gate-failing region, an unrated slot and a window whose only regions are unrated now all read
`UNRATED`; none can be a BEST BET or ALSO GOOD pick (`verdictEligible()` is unchanged), the honesty
filter still words a blanked region `AWAITING` or `STAND_DOWN` by reason, and stars, `meanRating`,
`sampleSufficient` and `forcedSample` are untouched. No migration (no column holds a
`DisplayVerdict`; `briefing_region_snapshot.display_verdict` is a free-text `VARCHAR(20)`), and the
serve path re-derives every region's verdict, so the API is right from the first request after
deploy. Ask's tools serialise the verdict by name, so the model can see `UNRATED` on `list_windows`;
the system prompt lists no verdict words, so nothing was added to it, and a stored Ready answer whose
window moved from `WORTH_IT` to `UNRATED` is withheld once by the existing freshness check. The
client wording and treatment are a separate change.
