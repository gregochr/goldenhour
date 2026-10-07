# What is in here, and what is deliberately not

Vendored verbatim from the Claude Design canvas handed back for `docs/design/coming-up/day-chips-brief.md`,
2026-10-07. **Do not edit these files.** Where they and the codebase disagree, `docs/engineering/coming-up-plan.md`
§11.25 wins.

| file | what it is |
|---|---|
| `Main.dc.html` | The Spring tide run card viewed 7 Oct: 9 and 10 Oct live, the peak (11) dimmed, 12–14 dimmed. |
| `PeakReachable.dc.html` | Viewed 9 Oct: the first box reads `Today`, the peak is live. |
| `BeyondForecast.dc.html` | The run entirely beyond the forecast: every box dimmed, the peak outlined, the all-dimmed caption. |
| `Phone.dc.html` | 390px: the lead word above the row, 38px boxes. |
| `States.dc.html` | Every state side by side (live, hovered/focused, today, peak live, peak dimmed, dimmed), a month-crossing run, and what stays a single line. |
| `canvas.json` | The canvas manifest the five boards were exported from. |

**The inline styles ARE the tokens** — they are the app's own (`--color-plex-*`, `--font-mono`, the family
accent via `--wf-cu-accent`, gold `#C9A24B` = `var(--color-home)`), so the CSS in `frontend/src/index.css`
(`.wf-cu-days`, `.wf-cu-day*`) was written from them.

**Not copied, on purpose.** The boards load `./support.js` (the canvas runtime), which was not part of the
hand-back; they render as source only without it. Nothing in the app reads these files.

**Deliberate deviations from the boards** (recorded in plan §11.25, not edits to these files):

- Month words use the house short form `Sept` (plan §11.22), where the boards print `Sep`.
- The chip row wraps (`flex-wrap`) and the card track is `minmax(0, 1fr)`: six 38px chips did not fit the
  phone card as drawn (measured in Chromium at 390px and 360px).
- A day that has already gone is a seventh state the boards do not draw: dimmed, disabled, named `… — gone`.
- The caption is reworded (`dimmed · no forecast yet`; `no forecast for these days yet`) and never names a
  forecast horizon, which is not a client fact.
- The live peak chip's hover fill is its own gold tint, because the accent tint dropped the gold number below AA.
