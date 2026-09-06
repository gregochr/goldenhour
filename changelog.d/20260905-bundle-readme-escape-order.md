### Docs — the plan-matrix design bundle's keyboard spec now matches what shipped

Closes the loop on #789 and #790, which corrected seven source comments that described a
three-layer dialog stack M5 refuses. Those comments cited "the bundle README's stated order", and
the citation was accurate: `docs/design/plan-matrix/README.md` really did say

> `Esc` closes search → then the location sheet → then the window popup, in that order

leaving the handoff spec asserting a stack the code contradicts in three files. The README is the
pixel-level spec the whole M1–M5 series was built against, so a reader who reaches for it as the
authority found the code looking wrong rather than the doc being superseded.

The Keyboard paragraph now states shipped behaviour, and an **Adaptation (M5)** paragraph beside it
records what the handoff asked for, why it changed (a Tab walk out of the topmost sheet reached the
masthead's search button on press 17 — the only route into a third layer, and one that bypassed the
guard `/` had carried since M3; and it rendered wrong, since every dialog is `fixed inset-0 z-50`,
so paint order is DOM order) and where the ruling lives (plan-matrix §4 A22). Rewriting the line
without that note would have destroyed the record of a deliberate divergence, which is the thing
that section of the plan exists to hold.

Three other clauses in the same sentence were checked against the code rather than assumed, and two
were also overstated: `/` opens search from the **Plan tab**, not "from anywhere" (it is refused off
that tab, inside a text field, under a foreign dialog, and while the shell is disabled by a dead
backend), and `← →` step between windows only while a popup is open **and nothing is over it**.
`↑ ↓` and `Enter` were correct as written and are unchanged.

Scope: the keyboard/dismissal claim only. The other 300 lines of that spec were not audited, and no
other design bundle carries this sentence — checked across all seven `docs/design/*/README.md`.
Documentation only; no code, and the source comments already agree with the corrected text.
