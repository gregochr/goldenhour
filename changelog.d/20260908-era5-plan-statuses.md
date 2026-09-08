### Docs — the measurement-program plans say whether they shipped

Seven records of the 2026-08-13 → 2026-08-18 ERA5 measurement program landed on main carrying the
status they were written with, and two of them were by then false: `veto-demotion-plan.md` and
`blanket-confirmation-plan.md` both read **"PROPOSED — awaiting user go/no-go"** for changes that
shipped on 2026-08-18 in v2.18.11. A reader arriving at either plan would have concluded the veto
demotion was still an open question, which is the opposite of the truth — and the veto doc itself
mentioned v2.18.11 nowhere at all.

Both now record what happened, keeping their original framing beneath it: the demotion shipped with
one amendment its supervised session's adversarial review found (`golden_hour` carries the approach
penalty too), and the blanket rewording shipped alongside it because its gate fired — the
blanket-precision cut measured 53.6% of promptable blanket calls over an observed-open corridor,
double the pre-registered 25% threshold. Four sibling recut plans gain the status line they never
had (#522, #528, #529, and the session brief), the veto doc gains two pointers to the release that
carried its §9 conclusions, and `trend-peak-reconstruction-plan.md` is marked unexecuted with its
precondition now met — re-verified against main rather than copied forward.

Recovered from a local-only branch that predates `changelog.d/`; the rest of that branch was
already on main, and where main had moved further — the cross-vendor physics corrections of
2026-08-27 — main's version stands.
