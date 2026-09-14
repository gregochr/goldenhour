### Added — the Plan tab has an iPad tier, 640px to 1023px

The plan-matrix handoff specifies three tiers — desktop, iPad, phone — and the Plan arm had two, so
every iPad value in the spec resolved to the desktop one. This adds the third, as one unlayered
`@media (min-width: 640px) and (max-width: 1023px)` block at the end of `index.css`.

**Why that range.** Not invented: `BrandLockup`'s masthead wordmark has carried a tablet tier on
exactly this band all along (Tailwind's `sm` 640 and `lg` 1024). It sits flush against the phone
query (`max-width: 639px`, also `useIsMobile`). An iPad in portrait (820–834px) lands inside it; in
landscape (1180–1194px) it lands on desktop, matching the handoff's own 1180px desktop frame.

**What it changes** — values from the prototype's `.wrap.pad` rules, which is what renders: the
masthead's top padding to 13px; the legend's desktop-only clause hidden; the popup's region rail to
a 130px minimum; the "All regions" cell onto its own full row, as on phone; the prose slot to 112px;
the ranked spot strip to 2.6 across. The strip's own note had deferred exactly this ("widening it is
the responsive pass's call").

**Two places the README and the prototype disagree, settled by the prototype:**
- Region cards: the README says 150px. The prototype has 150px on `.rrail` *and* 130px on
  `.wside .rrail`, and `regionRail()` is only ever called inside `.wside`, so 130px renders.
- The masthead: the prototype pads it `13px 20px 0` but gives the tab bar, lens bar and body no iPad
  rule, so they keep 22px. That would put the masthead 2px in from everything below it on this band
  alone, which is exactly what the shared `--wf-gutter` exists to prevent. The masthead takes the
  13px top and **stays on the 22px gutter** — the one deliberate departure, and one value to change.

⚠️ **A defect the first cut shipped to itself, and caught by measuring.** It set the popup to
`max-width: none`. Measured inside a real `Modal`, the popup then filled the viewport — 968px at
1000, 991px at 1023 — and snapped to the desktop cap of 780px at 1024: widening the window one pixel
shrank the popup by 211px. The spec only ever drew an iPad popup on an 834px frame (834 − 28 =
806px), so that is the cap now: 802px at 834, 806px across the rest of the band, and a 26px step to
780 at the desktop edge instead of 211.

**Not changed, and recorded:** the wordmark (already on this band, at sizes 2px above the spec at
*every* tier — a brand decision, not an iPad gap); the tick-times gap (already right for iPad —
it is the desktop that drifted onto the iPad value). And a **phone** defect found in passing: the
popup's region rail renders 3 columns where the spec says 2, because the phone rule's `.wf-rrail`
(0,1,0) loses to the unscoped desktop `.wf-wsh .wf-rrail` (0,2,0). This block writes its rail rule
at the higher specificity so it cannot fall into the same trap; the phone is left for its own fix.

**Verified in real headless Chromium**, reading computed styles at 639 / 640 / 834 / 1023 / 1024 /
1180px: every rule applies inside the band and not outside it. The rail's 130px is proven by track
count in a 398px box — 2 tracks at iPad, 3 at desktop — after a first probe at 384px turned out to
be **blind**, giving 2 tracks at both because it ignored the rail's 6px gap. Blast radius checked by
tracing every selector to its component: all popup-only except the shared masthead, and the
drill-down's cards are kept out by the child combinator.

⚠️ **What was not seen:** the popup rendered with real data. It needs a signed-in session with
forecast ratings, and the login carries a Cloudflare Turnstile challenge that a fresh browser cannot
pass and that was not worked around. The two automated review agents also stalled out on a machine
running at a load of 60–86 on 8 cores, so the adversarial checks were done by hand instead.
