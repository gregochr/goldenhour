### Changed — every dialog in the app now dims the page with the design's scrim

`Modal` and `BottomSheet` — the app's two dialog primitives — both drew their backdrop with a
Tailwind default nobody chose: `bg-black/60` and `bg-black/50`, so they already disagreed with each
other. Both now use one class, `.app-scrim`, at `rgba(8, 6, 5, 0.74)`.

That value is not one tab's preference. Every bundle in `docs/design/` from heat-map onward that
draws a dialog specifies it exactly, on its `.scrim{position:absolute;inset:0}` — heat-map,
plan-matrix, coming-up, field-geography, matrix-axis, map-tab-v2 and map-landing — and window-first
carries the near-identical `.72`. ⚠️ **Only with the qualifier "that draws a dialog".** The first
cut of this change said "every bundle from heat-map onward", and a fact-check found
`temperature-scale` has no scrim at all — it is a colour-ramp handoff with no dialog in it.

**This is app-wide by design, and it was a decision rather than a side effect.** It restyles
settings, confirmations, the Operations dialogs and the Map tab's phone sheets, not only the Plan
tab's. It was raised as a blast-radius question first and taken deliberately. `BottomSheet` was
added when an adversarial review pointed out that changing `Modal` alone would have *widened* the
gap between the two scrims — a warm 0.74 beside a cool black 0.50 — while the new comment claimed a
single settled value. `map-tab-v2`, which governs `BottomSheet`'s phone uses, defines no separate,
lighter scrim for them. `MapOverlay` keeps its own inline `rgba(8,6,5,.72)` with a blur — the
window-first value, on a separate surface.

Warmer and denser than both defaults, so the page behind a dialog is darker and anything drawn on
top of it gains contrast. One named class rather than two inlined values, so a later bundle that
wants a different scrim changes it in one place and knows it is changing all of them. Unlayered
CSS, which beats Tailwind's layered utilities regardless of specificity, so a future `bg-*` on the
backdrop cannot quietly override it.

Verified in real headless Chromium: both backdrops compute to `rgba(8, 6, 5, 0.74)` and keep their
own positioning (`absolute` for `Modal`, `fixed` for `BottomSheet`); the retired class name
resolves to transparent, so nothing still leans on it. No test pinned either old class — every
backdrop test targets a `data-testid`. ⚠️ What was not seen: an *opened* dialog. The login form
carries a Cloudflare Turnstile challenge, so a fresh browser context cannot sign in, and that was
not worked around — the computed values were read on the unauthenticated page, where the global
stylesheet is already loaded.
