### Docs — doors from Plan to Map: the closing sweep

Phase D6 of `docs/engineering/plan-to-map-doors-plan.md`, the series' last phase. §0 flips to
COMPLETE with the phase log's commit column filled in against `origin/main`'s tail (D1 `dffd764a`
#762, D2 `399a7e36` #763, D3 `f52a6013` #764, D4 `a4964ed8` #765, D5 dropped unbuilt); §4's twelve
disagreements-on-purpose were re-checked against the tree and every cross-reference still resolves,
so nothing needed renumbering; §6's open items are reframed as the list that survives the series
(the three owner decisions stay DECIDED, untouched); §7's verify matrix states what each check
actually ran and measured rather than only how it would be measured; §8 gains a PR-number column;
§9 gains a "how to run" note for the D4 Playwright sweep, which stays in the tree as a regression
check and stays out of CI.

CLAUDE.md's Map tab (v2) bullet gains the three-part handover (`App.openMapTabFromPlan` on the
existing `mapTabHandoff`/`tabRequest` nonce channel, one nonce-keyed door effect in `MapView`,
`MapBreadcrumb` mounted above the frame with its carrying clause and app-wide `clear`) and the
`driveMinutesFor`/overlay-fetch split; the Plan tab bullet's dialog-stack paragraph gains the two
doors beside the settings cog as routes that close every Plan dialog before they leave.
`map-tab-v2-plan.md` §6's O-6 records that the location sheet footer has moved off the overlay
while every other producer stays. O-18 was REWRITTEN rather than appended to: this sweep was
drafted against D4 on 2026-09-04, and #774 closed O-18 the other way the following day —
`Four days here ›` opens the sheet over the map. The draft had read the doors series' Q2 as a
general precedent for landing on the destination tab plain, which that closure falsifies; the
entry now records why the two routes differ, using Q2's own rule — the destination decides — so
the pair does not read as an inconsistency for a later pass to harmonise. CLAUDE.md's handover
sentence took the same correction: it describes the `source: 'plan'` door and now says so,
because #774's `inPlace` door rides the same channel carrying no lens fields and no tab request.

Adversarial review (3 read-only lenses — truth against the tree, plan self-consistency, CLAUDE.md
conventions): truth and conventions raised zero charges, every code claim re-verified by file:line
citation. Consistency found one real defect, upheld by a dedicated refuter — §7 row 3's Phase
column named only D1, though its own "How" text cites a browser check D3 actually ran, breaking the
convention row 1 already established — fixed to `D1 (unit), D3 (browser)`. A second charge (O-D2
filed under a header now saying every item "survives" as open, while O-D2's own text says "Closed
with Q2") was refuted: §6 already mixes DECIDED and closed entries into an "OPEN items" list
throughout, unchanged by this phase.
