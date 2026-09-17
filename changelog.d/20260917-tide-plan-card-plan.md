### Docs — Plan tab "tide alignment on the window card": port plan and session prompts

`docs/engineering/tide-plan-card-plan.md` is the port plan for the owner's `design_handoff_tide_plan`
bundle (vendored verbatim at `docs/design/tide-plan/`, with a `VENDORING.md`), and
`docs/engineering/tide-plan-card-prompts.md` is one paste-ready kickoff prompt per phase for Sonnet
sessions. Four phases (C1–C4): the data on the card's pool, the two marks on the card, the popup fact
and the phone check, the sweep.

The plan's §1 records where the codebase already answers the spec: the fit model the spec names as a
prerequisite was never built as a level formula (the app's tide axis is time-based and served), so
`match` reads the served `tideAligned` and the run ranking's tiebreak is the served nearest-extreme
offset; the card's spread histogram and best-reachable line already share one reach-gated pool, and
the new chip counts that same pool; and the counts are reach-scoped, so they join CLAUDE.md's named
reach-scoped client class with `reachMeasured` gating the words "in reach". No backend change.

No code changes.
