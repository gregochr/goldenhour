### Docs — the Plan popup's desktop region rail keeps 128px, and the spec records why

An audit flagged the desktop rail's 128px minimum as drift: the prototype's rule for a rail in the
popup's side column is `.wside .rrail` at **112px**, and since the rail only ever renders there,
that is the value the prototype shows. The README's 128px is the bare `.rrail` rule it loses to.

Measured in real headless Chromium before anything was changed, loading the built stylesheet and
building the popup's actual desktop chain:

| | 128px (kept) | 112px (prototype) |
|---|---|---|
| with the field map | 3 × 143px | 3 × 143px — identical |
| without it | 5 × 145px, meta on one line | 6 × 120px, **meta wraps on every card** |

With the map — the only layout the prototype ever draws — the popup is capped at 780px, the rail is
442px wide, and both minimums fit exactly three tracks, so the two render the same. Without it —
the full-width rail shown while the heat catalogue loads or has failed, which the prototype never
draws — 112px fits a sixth column and every card's `best 5★ · 10 in reach` breaks onto two lines,
growing the cards from 63–76px to 90px. **The value has no visible effect where it was designed and
a visible cost where it was not**, so 128px stays (owner decision, 2026-09-14).

The rule now carries that reason in its own comment, so the next audit does not "fix" it back, and
`docs/design/plan-matrix/README.md` records it beside the region-card spec. That line also said
**iPad `150px`**, which has been wrong since #821 shipped 130px — the prototype's `.wside .rrail`
again beating its bare rule — and is corrected here. With this the three tiers read, in both the
spec and the code: 128px desktop (deliberate), 130px iPad, two up on phone.

⚠️ This also corrects an earlier claim: the rail was described as matching the prototype "on iPad
and phone only". Visually it already matched on desktop too — in the only layout the prototype
draws, both values render 3 × 143px. The 128/112 difference was in the rule's text, not on screen.

Documentation and a comment only; no rendered value changes.
