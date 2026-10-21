# Plan card verdict honesty — three small fixes (2026-10-13)

## 1. What prompted it

The owner read Friday 16 Oct's sunrise card as "EPIC" and the map as "bang average". Production
data (`cached_evaluation`, queried 2026-10-13) said: Friday SUNRISE was scored in ONE region,
Northumberland & Tyneside, 38 rated, 23 at 4★+, **mean 3.53** — a hair over the 3.5 line
`BriefingRatingStats.resolveRegionDisplayVerdict` draws for WORTH_IT. Friday SUNSET (the window the
map screenshot was actually on) had 26 rated, 1 at 4★+, mean 2.08. From DH6 at the 45-minute tier
nothing rated was in reach, so the card read "none rated yet" / "not scored yet" under a "Worth it"
that belonged to a region beyond the ring. Both surfaces were right about their data; three things
let the card say more than the data did.

## 2. The three fixes — each its own phase, each its own agent

### H1 — The card shows the mean behind the verdict word

A region averaging 3.53 wears the same word, tint and bloom as one averaging 4.6. `buildWindowCards`
already publishes `card.topMeanRating` — the served `BriefingRegion.meanRating` of the window's
top region (scoped to the origin's region when away). Render it beside the verdict word, one
decimal (the mean's own precision; `bestRating` is an integer and "4.0★" was banned for FALSE
precision — this is not that), and carry it into the card's accessible sentence. Silence when
null (rule 6: degrade is silence): a triage-fallback verdict has no mean, and showing none is
itself the disclosure that no rating stands behind the word.

⚠️ The mean beside the word MUST be the mean of the region that produced the word. The projector
ranks regions eligible-first, then mean, then coverage, then name (`PlanWindowProjector.BY_RANK`);
the client's `topRegion` in `windowFirstCards.js` must select the same region, or the card prints
one region's word beside another's figure. Verify, and align the client selection if it diverges
(a selection among served records, the licensed `mapVerdict` shape — never a threshold).

Ground rules in force: `docs/engineering/plan-matrix-plan.md` §3 (rule 1: the mean is served,
never derived; rule 5: no "N of M scored"; rule 9: tokens in `@theme static`; rule 16: the
frontend test standards). Measure the value grid at 320/375/640px as A27 did — the row must not
wrap or overflow.

### H2 — The card says whose verdict it is when nothing in reach is rated

State: the reach-gated pool is non-empty, none of it is rated, and the window DOES carry a rated
verdict (`topMeanRating != null`). The word then describes `card.hotRegionName`, every rated spot of
which the reach tier gated out. Today the BEST row prints "not scored yet", which is false of the
window and true only of the pool. Replace it, in exactly that state, with a line naming the region
and the fact that it is beyond the active tier (the tier label as the lens bar prints it, e.g.
"45 min"); carry the same into the accessible sentence. "Beyond" is only claimable when
`card.reachMeasured` is true and a tier is active (an unknown drive passes every tier, so without
a measured drive nothing was gated — plan-matrix §3 and `bestReachLine`'s own rule); the state
cannot arise otherwise, and a test must pin that it does not.

### H3 — The map's "This event is not scored yet" must not fire over 26 ratings

`MapView.windowUnscored` (solar path) tests `heatWindow?.bestRating == null`, where `heatWindow` is
the Plan's SERVED window. A D-13 filler row — a date beyond the six rendered events but inside the
forecast horizon — has no served window, so the line fires while the chips and the counts footer,
which read the scores endpoint, draw real stars ("14 rated"). The line's own doc says it answers
"the forecast rated nothing here"; make it answer that: on a filler row consult the score index
for the row's date/event (the existing `briefingScores` `|date|eventType|` predicates in `MapView`
are the model) and stay silent when any rating exists. The colour key stays withheld on a filler
row — no field is painted there (`heat.pointsByKey` is keyed by the rendered windows), and the key
describes the field, not the chips. Pin all four states: served window rated, served window
unrated, filler with ratings, filler without.

Known limit, recorded not fixed: the heat FIELD is not painted on a filler row at all, even where
ratings exist — `buildHeatSpots` scores each spot per rendered event only. A follow-up, not this.

### H4 — A blanked stand-down says the weather called it (2026-10-21)

(Numbered H4 because H3 above is the Map's `windowUnscored` fix; this is the third Plan-card
change of the series.)

State: the window's word is `STAND_DOWN` ("Poor") and the region it came from is BLANKED by the
honesty filter, i.e. its served `slots` are empty. Since the UNKNOWN-reason fix the filter words an
unknown cause AWAITING, so a blanked STAND_DOWN can only be weather triage. The card nonetheless drew
"Poor" over an all-hatched field with nothing beside it (the mean is deliberately silent beside a
stand-down), and on 2026-10-19 the owner read a T+3 sunrise as a rating.

The rule: `card.verdictBlanked` is true exactly when `verdict === 'STAND_DOWN'` and the word's own
region has an empty `slots` array. "The word's region" is `verdictMeanOf`'s selection (the origin's
region when scoped, else `topRegion`); with no mean anywhere to rank by it falls to the projector's
own order among mean-less regions (a region with slots ahead of a blanked one, a blanked real
stand-down ahead of a blanked AWAITING, then the name). Where it holds, the card prints `too
unsettled` in the mean's own cell, ink and size, and its sentence reads "Poor, too unsettled to
forecast" (the honesty filter's own label, so the card and the popup's region rail speak one
vocabulary). The popup head prints the same note beside its badge and in its live message.

Deliberately NOT done: no new backend field (the served `slots` and `displayVerdict` are the
server's statement), no change to the word, the tint, the spread row or the best-reachable line, no
qualifier for a stand-down region that still has slots, and none for AWAITING or UNRATED. The note is
text, not a figure: at a 320px viewport (value column 70.3px) it ellipsises before the word does, and
carries the full phrase as its title and in the spoken sentence.

## 3. Process

Each phase: build → tests (`npm run lint && npm test && npm run build`) → changelog.d entry →
adversarial review of the diff before the commit. Backend untouched. Agents never commit.
