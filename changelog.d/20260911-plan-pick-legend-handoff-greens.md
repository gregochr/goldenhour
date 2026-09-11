### Changed — the Plan card's BEST BET / ALSO GOOD legend takes the handoff's own greens

The matrix card's border legend now reads `#B6D49F` for BEST BET and `#8CA87A` at 600 for ALSO GOOD
— the values every bundle from plan-matrix through map-tab-v2 that draws the Plan card specifies
(`.lg.wb` / `.lg.wa`). It had shipped as `--color-badge-go` (`#A8C795`) and `--color-verdict-go`
(`#8AAE72`) with no reason recorded anywhere; the pixel audit marked it open and the owner chose the
handoff. ⚠️ "Every bundle" needs its qualifier: `temperature-scale` falls inside that range and
draws no card — the second time that bundle has falsified an unqualified claim in this series.

**Measured on the grounds a pick can actually occupy** — the legend inherits the card's verdict
tint, resting, hovered and open, and ALSO never sits on a Poor card because the runner-up is
suppressed there:

| | before | after |
|---|---|---|
| BEST | 6.84–8.79:1 | 7.84–10.07:1 — up on every ground |
| ALSO | 5.09–5.93:1 | 4.86–5.66:1 — ⚠️ down on every ground |
| BEST vs ALSO | 1.34:1 | 1.61:1 — further apart |

ALSO still clears AA, but its margin over 4.5:1 shrinks from 0.59 to 0.36 at a hovered Worth-it
card. The first draft of this change gave BEST a before-and-after and ALSO only its floor, which
presented the trade favourably by omission; an adversarial review caught it.

**What it costs, and why it may be worth revisiting.** BEST BET used to read `#A8C795`
*identically* on four surfaces — this legend, the popup's pick badge, `WindowPickDialog`'s kind
label and the Map medallion. This moves the legend alone, so BEST now differs from the other
three. The handoff specifies exactly that (its popup badge is `#A8C795`, and map-landing gives the
medallion the same), so it is the design rather than drift — but it breaks a match the code had.
And the codebase has turned this shade down once before: `.wf-seg-rating .wf-seg-btn.on` declines
the handoff's `#B5CFA3` as "a difference nobody can see", and `#B6D49F` is 6.5 RGB units from it.
ALSO GOOD was never one colour — its popup badge and dialog are `--color-badge-also` (`#B3BEEA`,
blue) — and that split is unchanged.

Verified in real headless Chromium, with the legend built inside real card ancestry so
`background: inherit` resolved against a genuine verdict tint: BEST computes to
`rgb(182, 212, 159)` at 700, ALSO to `rgb(140, 168, 122)` at 600, and the tint gradient is
confirmed inherited (not just the base colour). The Map medallion and landing pick were probed as
controls and are unchanged at `#A8C795` / `#8AAE72`. No test pins any of these colours; the
legend's tests assert tag, text and `data-pick` only.
