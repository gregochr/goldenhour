### Docs — the tide-plan-card sweep (C4)

Closes out `docs/engineering/tide-plan-card-plan.md`: flips §0 to complete, fills the phase log's
commit column with the four PR numbers (C0 #888, C1 #889, C2 #891, C3 #890 — kept in phase order,
noted as not the merge order), and reconciles §4 against the shipped code — two entries added
(C3's declined `heightAtWindow` popup fact; C2's chip tooltip states the served window tide's
state and direction only, never a height or a clock time) that the phases recorded but the plan
had not yet numbered.

Answers the design spec's four `OPEN` items in §6 with what shipped (fully reach-bound marks; no
run vocabulary to share, since Hot Topics' `SPRING RUN n/N` chip no longer exists; the location
`TideType` set already expresses "both extremes"; the phone measurement's actual numbers), and adds
§7b, transcribing the phase log's measured-versus-tested figures into one table rather than
re-deriving them.

CLAUDE.md: the Backend-heavy bullet's Plan-tab reach-scoped class gains `card.tideFit` and the
strip's `tideRun`, and nothing else; the Plan tab (day × event matrix) bullet gains one note on the
wave glyph, the `N on tide` chip and the silence rule below its gate; the "Two tide axes" bullet
notes the Plan card's glyph and chip read the same preference axis (`tideAligned`) as the Map tab's
own chip. None of the tide-window series' own T8 additions to these bullets are touched.

Docs only — no code changed.
