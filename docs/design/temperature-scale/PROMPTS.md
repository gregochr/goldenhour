# Implementation prompt

> **Repo note, added when this bundle was committed — read `docs/engineering/heat-scale-unification-plan.md`
> §2 before following anything below.** Paths in this file were rewritten to the committed
> locations (the bundle arrived rooted at `design_temp_scale/`, and the spec was renamed to
> `temperature-scale.html`). More importantly, four of the changes below are stale or mis-targeted
> against the current tree — including **Change 1, which names the wrong module**: the app's
> `frontend/src/utils/heatField.js` contains no `STOPS`, and the ramp it must edit is
> `RAMP_STOPS` in `frontend/src/utils/scoreRamp.js`. §2 of the plan lists all four with the
> evidence. The prose below is preserved as Design sent it.

Paste into Claude Code from the repo root, with this folder available.

---

> Open `docs/design/temperature-scale/temperature-scale.html` in a browser at 1100px or wider and read it top to bottom — it is the spec, and §07 is the change list. `docs/design/temperature-scale/heat-field.js` is the reference kernel: the ramp work is already done in it, so diff it against the app's `heatField.js` rather than reimplementing. Where this prompt and the doc disagree on a measured number, the doc's live scan wins — it computes in the page.
>
> **Read this first.** The document is drawn in the *proposed* Kodachrome skin, but `frontend/src/index.css` still ships the blue-grey plex palette. Every warm hex in the doc is a proposal; only hexes the doc attributes to a file are shipped. Do not copy the document's chrome colours into `index.css`, and do not touch `--color-verdict-*` — those are saturated web colours used for verdict words (`#16a34a` / `#d97706` / `#b91c1c`) and have nothing to do with the muted ramp in `heatField.js`, despite the older handoff notes claiming otherwise.
>
> **Change 1 — two ramps in the kernel.** Replace `STOPS` with `STOPS_TEMP` and `STOPS_VERDICT` plus a module-level `MODE`, read by `ramp()`. Everything downstream of `ramp()` is untouched. Take the stop list verbatim from the reference kernel:
>
> `[[1,[58,92,112]],[2.2,[80,104,120]],[2.8,[146,140,128]],[3,[196,148,64]],[3.2,[201,146,48]],[3.9,[223,107,42]],[4.3,[214,58,38]],[5,[242,96,52]]]`
>
> **The stops are deliberately uneven and three of them are load-bearing.** Regional means occupy roughly 1.9–4.6, so evenly spaced stops spend the blue and the red on values that never survive the blur and render every night the same orange. The `2.2` stop is held dark so the bone marker label clears 4.5:1 against it. The `3` stop exists because `rating` is an integer 1–5 and 3★ is likely the most common value in the catalogue — interpolating 2.8→3.2 put it on a dun khaki. Do not even out the spacing, and do not lighten `2.2` without re-checking marker contrast.
>
> **Change 2 — tokens.** Add `--color-heat-1 … --color-heat-5` to `index.css`, sampled from the ramp at whole stars: `#3A5C70`, `#4C6677`, `#C49440`, `#DD5F29`, `#F26034`. These five are for discrete uses; the field itself calls `ramp()` and interpolates.
>
> **Change 3 — retire the last duplicate.** `RATING_COLOURS` was already deleted in D3; do not re-add or re-delete it. What remains in `components/markerUtils.js` is `scoreColour()`, a stepped 0–100 twin driving cluster badges and any marker with scores but no rating. Retire it in favour of `HeatField.ramp()`, gated on the new `markersFollowScale` preference.
>
> **Change 4 — do NOT touch the marker ink.** #627 already fixed this, and fixed it better than this brief originally specified: `readableInkOn` derives the ink per fill rather than flipping at a hard 3★ threshold. Implementing a threshold now would be a regression. The pair it chooses between — `#0F172A` / `#FFFFFF` — is also the right pair; do not substitute a bone or off-white ink, which measures worse.
>
> **Change 4a — snap label-bearing fills to whole stars.** This is the design decision that was blocking, and it is decided: **any fill that carries a label samples the ramp at whole stars; only label-free surfaces interpolate.** `readableInkOn` picks the better of two inks, which only helps where one of them clears 4.5:1 — and every ramp through mid-luminance has a band where neither does. Markers already sample at whole stars. Cluster badges do not: `starsFromAverage` returns `avg / 20`, continuous, so a cluster paints an interpolated fill and puts a count label on it. **Snap the cluster fill.** It costs a cluster nothing — a cluster is a zoom artifact you resolve by zooming — and it returns the fill to the resolution the data actually has, since `rating` is an integer 1–5.
>
> One correction on the measurement, reconciled against the planning session's own scan. Against the ink pair the app ships — `#0F172A` and `#FFFFFF` — at 0.01★ resolution, the temperature ramp has **three** sub-AA runs totalling **13.2%**: 2.48–2.60★, 4.10–4.24★, 4.37–4.61★. **State the convention whenever you quote that number:** it is the share of the 401 samples that fail, not the summed width of the runs — span-summing drops one 0.01★ step per run and reports 12.5% for the same scan. §05 of the doc computes it in the page on the sample-count convention, and also reports the bone-ink figure (21.7%, two runs) for the record, because that was this document's earlier error — it scanned with its own palette rather than the app's, and bone is the worse ink: it gives up contrast on the dark stops that white holds. The app's white needs no change.
>
> **Two of the three runs are in the hot half**, which is the part worth carrying forward: the failures cluster where 4★ and 5★ ratings live, not at the cold end. The `4.3` stop `#D63A26` is the worst case for both inks at once — dark ink 3.82:1, white 4.67:1 — so the `3.9 → 4.3 → 5` leg crosses the dead zone twice.
>
> The decision to snap does not depend on the width, and neither does the ramp survive on "we measured it and it was fine". **The interior is not safe; it is merely no longer sampled by anything that carries text.** The heat field keeps interpolating because nothing in it is labelled. If a labelled surface is ever made continuous, fix the `4.3` stop first. And the existing guard `it.each([1, 2, 3, 4, 5])` passes today by luck — it tests the only five values never at risk — so snapping makes it correct by construction. Add one test asserting the invariant directly: no label-bearing surface is ever handed a fractional star.
>
> **Change 5 — collapse the duplicated score bar.** `ScoreBar.jsx` was deleted in D4, so ignore any reference to it. The live duplication is **`PlanScoreBar`** (Plan side, gradient fills) against **`PopupScoreRow`** (`components/MarkerPopupContent.jsx`, map popup, four buckets — so 26 and 49 are the same colour). Collapse them into one component with a **continuous solid fill** sampled from the ramp and the number tinted to match. Solid, not a gradient — a bar has one value, and a gradient across a ramp that starts cold is a five-hue rainbow; `PlanScoreBar`'s gradients are the thing being removed. Note the doc's §06 shows the popup ladder read from source but not `PlanScoreBar`'s gradient stops. Update whichever tests cover the two components.
>
> **Change 6 — calibrate the two 0–100 metrics before shipping the bars.** The ramp is indexed 1–5 because ratings are; the potentials are 0–100, and assuming they span the full ramp is wrong — every fixture in the test suite sits between 50 and 78, which the naive mapping squeezes into 3.0–4.1: gold to orange, never cold, never hot. Use `HeatField.rampPct(value, lo, hi)` from the reference kernel, with **one `lo`/`hi` pair per metric taken from a percentile over stored `fierySkyPotential` and `goldenHourPotential` values** — roughly the 5th and 95th. The doc's 25–85 is an illustration, not a value to ship. **This stage needs production data** — local H2 has no representative distribution, so it cannot be completed from a dev environment.
>
> **Change 7 — the preference. Note this one is full-stack.** A new **Map Colours** section in `components/UserSettingsModal.jsx`: `mapColourScale: 'temp' | 'verdict'` defaulting to `temp`, and `markersFollowScale` defaulting on. Follow the modal's existing pattern — a `<section>` with an uppercase `text-xs font-medium text-plex-text-muted tracking-wide` heading, matching Profile / Home Location / Drive Times. The modal has **no toggle or checkbox pattern today**, only text inputs and `btn-primary` buttons, so this control is new work; §03 of the doc mocks it in the real plex palette. Persisting through `settingsApi` rather than `localStorage` means the full chain — migration, entity, DTO, service, controller — which the design doc does not say out loud. Leave it **outside the `isPro` gate**: reading the map is not a Pro feature.
>
> **Change 8 — a one-time notice on the map**, dismissible: "Colours now run cold to hot." This is the part that reaches the person who was misreading the old map; the preference does not, because they will never open Settings to discover they were wrong. Not a setting, a sentence.
>
> **Change 9 — the legend** redraws its 60×6px bar from the active ramp. The words `poor → worth it` do not change on either scale: the bar shows the metaphor, the words carry the meaning.
>
> Both surfaces — Plan thumbnails and Map tab — must read `MODE` from one place so they can never disagree about what a colour means, the same rule the planning area already follows. Note there is no map toolbar to put anything in: `ViewToggle.jsx` is the Plan/Map/Manage tab bar, and `MapView.jsx`'s only overlays are a bottom-left upsell chip and a bottom-centre legend over a fixed 500px map.

---

## Checklist

- [ ] `ramp()` reads `MODE`; nothing downstream of it changed
- [ ] Stop list matches the reference kernel exactly, uneven spacing intact
- [ ] `--color-heat-1 … 5` defined; `--color-verdict-*` untouched
- [ ] `scoreColour()` gone; `RATING_COLOURS` left alone (already deleted in D3)
- [ ] `readableInkOn` untouched — no hard-threshold ink flip reintroduced
- [ ] Cluster fills snapped to whole stars; test asserts no fractional star reaches a labelled fill
- [ ] `4.3` stop left as-is, with its constraint recorded — fix it first if a labelled surface ever goes continuous
- [ ] One score bar component, continuous solid fill, both call sites, `PlanScoreBar` gradients gone
- [ ] `lo`/`hi` per metric measured from **production** data, not 25–85
- [ ] Map Colours section persists via `settingsApi` end to end, ungated, defaults to Temperature
- [ ] One-time cold-to-hot notice appears once and dismisses
- [ ] Legend bar follows the active ramp; wording unchanged
- [ ] Plan and Map read the same `MODE`
- [ ] Existing installs default to Temperature, no migration prompt
