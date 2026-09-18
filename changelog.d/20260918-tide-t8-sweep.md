### Docs — Map tab "tide on the window": close out the series (T8)

`docs/engineering/tide-window-plan.md`'s T8 (the final phase of eight) sweeps the port plan and
its vendored spec now that T1–T7 have all merged (#876–#882): §0 flips to complete with every
phase-log commit column filled in from `git log` (including T3's pre-merge Codex fix, `1507aa3c`,
named separately since the squash-merge hides it from `main`'s own history), §1 #12's licensed
client-derivation count is corrected from five members to six (T6 added `siblingEventTime` without
updating the count), and §4's 18 numbered disagreements-with-the-spec were re-verified directly
against the shipped code — `TideRepresentativeSelector`, the gate's `HARD_CONSTRAINT_REASONS`, the
strip's mount point and tokens, the absent `'near'` tier, the rating-stage bypass — and found
accurate; no renumbering was needed.

§6 gains a preamble mapping the design spec's five `OPEN` items onto what shipped, and a new **Q9**
recording an accepted, narrow risk T5's own review found: the callout/sheet block's jump and denial
text read the location's *live* wanted-water set while the served fit phrase's own "wants" clause
is frozen inside the cached forecast row, so the two can drift apart between an admin's edit and
the next pipeline cycle. §7 gains a `§7b` table transcribing the phase log's own measured numbers
(chip contrast, strip geometry at desktop and phone widths) rather than leaving them buried in
prose.

CLAUDE.md now documents the shipped increment: the Map tab (v2) bullet describes the tide strip,
the dimmed-not-dropped chip and the rule that the map asks one tide question (the preference one);
the Locations bullet notes the map reads `TideType` as the wanted water; a new seventh Backend-heavy
licensed client-derivation class names the six tide-fit helpers (`stripModel`, `dominantWantCount`,
`nextAlignedRow`, `tierOf`, `wantPhrase`, `siblingEventTime`); and the "Two tide axes" bullet notes
that the same "neither axis may answer for the other" rule now also separates the map's preference
question (`tideAligned`) from the pre-existing on-the-light fact (`tideOnTheLight`).

`docs/design/tide-window/VENDORING.md` records the plan's final §4 count (18). No code changes.
