# The verdict on the count line — plan

Source: the design page `design_handoff_lens_verdict_line/Lens Verdict Line.html` in the PhotoCast
design project (claude.ai artifact `475972e7-…`). One PR, frontend only.

## What changes

When the lens (drive tier and rating floor) empties the whole plan, the page today draws a
callout card above the matrix (`.wf-clash`, `window-first-conflict`, built by
`buildPlanConflict` in `utils/planConflicts.js`, rendered in `WindowFirstShell.jsx` ~:2950). The
card goes. Its message moves onto the lens bar's readout line, the line that already says what the
filter cost ("0 of 181 spots within reach across 1 window"), with **one** action button. The days
ahead move up by about 200px on a phone.

Phone, rating shut (the screenshot's state):

```
0 at 4★+ this week · best is 3★, Angel of the North · Drop to 3★+ →     (?)
```

Desktop: the right-aligned summary (`.wf-lens-res`) swaps its text for the same verdict; the text
ellipsises, the button never does (`flex: none`, outside the ellipsising span). The `?` stays after it.

The lead `0` is amber (`--color-badge-maybe`, the colour the card headline already uses) **only** in
the verdict state; the ordinary count keeps its ink `<b>`.

## Every state the line can be in (the design's table, mapped onto the existing branches)

`buildPlanConflict` keeps its branch logic, guards and causal order (reach first, then rating). It
gains one field on its result, `line: { lead, text, action, announce }`, and keeps `id`. `headline`,
`body` and `actions` are deleted along with the card (no other reader; `WindowSheetDialog` only
reuses the `.wf-clash-act` CSS class, which stays).

| When | `lead` · `text` · `action.label` |
|---|---|
| Rating shut, ceiling is itself a chip (`step` found) | `0` · `at 4★+ this week · best is 3★, Angel of the North` · `Drop to 3★+` |
| Rating shut, ceiling carried only by outliers (`ceiling.spot` null) | `0` · `at 4★+ this week · best is 3★` · `Drop to 3★+` (drop the place clause; never "with no one place named") |
| Rating shut, ceiling below every chip (no `step`) | `0` · `at 3★+ this week · best is 2★` · `Show all ratings` (id `ANY_RATING_ID`) |
| Rating shut, nothing rated (`ceiling` null) | `0` · `at 4★+ · nothing rated yet` · `Show all ratings` (no "this week") |
| Reach shut, a wider tier helps | `0` · `within 45 min · nearest is Angel of the North, 55 min` · `Widen to 1h 30min` |
| Reach shut, nearest has no measured drive | `0` · `within 45 min` · `Widen to …` |
| Reach shut, away, no wider tier helps | `0` · `within 45 min of Keswick` · `Plan from home` |
| Reach shut, home, no wider tier | `0` · `within 45 min` · no action (defensive; unreachable while scope is non-empty) |
| No conflict | `buildPlanConflict` returns null; the readout is unchanged |

Rules carried over from the current code, unchanged in meaning:
- **"Plan from home" is offered only when it is the only fix** (the design's rule). In the rating
  branch there is always a lens action, so the origin action is never offered there. In the reach
  branch it is offered only when `helpfulTier` returns null and an origin is set. `homeAction` and
  the "Or" wording go.
- The reach "of Keswick" names `origin.baseName` only in the no-wider-tier away row (the design
  shows it only there); the other reach rows name no place, since the drive caption above already
  says "From Keswick".
- Wider-tier label: `wider.label` as the chip spells it, lower-cased as today (`Widen to 1h 30min`).
- Nearest drive via `formatDriveDuration`, as today.
- Ratings render as `N★`; the existing code already emits U+2605.
- The §6 clause-7 reach-word rule: no rating row says "within reach" or names a tier, so
  `gatingTierLabel` is no longer used by the line; `reachMeasured` still decides the reach branch's
  inputs exactly as now.
- Every action is still tested against the data before it is offered (`helpfulTier`, `step`).

`announce`: one plain sentence for screen readers, no `·` and no arrow, e.g. `Nothing at 4★+ this
week. Best is 3★, Angel of the North.` / `Nothing within 45 min. Nearest is Angel of the North,
55 min.` / `Nothing at 4★+. Nothing rated yet.`

## Rendering

- `WindowFirstShell` passes `verdict={conflict?.line ?? null}` and
  `onVerdictAction={applyConflictAction}` to `WindowFirstLensBar`, and deletes the
  `window-first-conflict-slot` block. `applyConflictAction` is unchanged (it takes `line.action`,
  which keeps `{kind, id, label}`), including the focus move to the first `wf-heat-card-button`:
  the button that was pressed unmounts with the verdict, so the move is still needed.
- `WindowFirstLensBar`, when `verdict` is set:
  - Phone (`wf-lens-count`, add modifier class `wf-lens-count--verdict`): `<b>{lead}</b>`, ` {text}`,
    then ` · ` and the action `<button type="button" data-testid="window-first-lens-verdict-act"
    data-loosen={kind} className="wf-clash-act wf-lens-verdict-act">{label}<span aria-hidden> →</span></button>`,
    then the existing `GuideLink`. The line may wrap to two rows on a 390px phone; that is fine.
  - Desktop (`wf-lens-res`, modifier `wf-lens-res--verdict`): the ellipsising `<span>` holds
    `<b>{lead}</b> {text}`, and the action button is a sibling after it (not inside the span), so it is
    never ellipsised.
  - `data-variant` on the readout becomes `verdict` in this state (`count` / `summary` otherwise);
    the readout keeps `data-testid="window-first-lens-readout"`.
- **Live region.** The card's slot was an always-mounted `role="status"`. Move that duty into the
  lens bar: an always-mounted visually hidden `<span role="status" aria-live="polite"
  data-testid="window-first-lens-verdict-status" className="sr-only">` holding `verdict?.announce ?? ''`.
  It must be mounted whenever the bar is, never conditionally (a role added to a node that mounts with
  its content is not announced). The visible line itself is not a live region (it changes on every
  chip press; announcing the count each time would be noise).
- **Delete** `.wf-clash`, `.wf-clash b`, `.wf-clash span`, `.wf-clash-acts` CSS (check
  `WindowSheetDialog` first: it uses only `wf-clash-act`, which stays) and
  `.wf-conflict-slot:empty`. `windowFirstPaneStatusGap.test.js` pins the slot's gap-cancelling margin
  and its siblings ("the three rules now have to travel together"): update that test and the comment
  above `.wf-conflict-slot:empty` so the remaining rules still cancel exactly one gap each, and prove
  the first visible element under the bar still sits where it did when there was no conflict.
- New CSS: `.wf-lens-count--verdict b, .wf-lens-res--verdict b { color: var(--color-badge-maybe); }`;
  `.wf-lens-verdict-act { flex: none; white-space: nowrap; }`; on desktop give the button a small
  left gap (8–10px). Phone: the button keeps `wf-clash-act`'s 26px `min-height` rule from the phone
  block (WCAG 2.5.8), as an inline-flex so the row doesn't grow taller than needed. Check the sticky
  bar's height reserve (`useLensReserve`) still measures it (it measures the live bar, so a taller
  verdict line is picked up; verify with a test or in the browser).
- No role gating changes: LITE is pinned to "Any" reach, so it never sees a reach verdict; the rating
  floor is ungated.

## Tests (frontend standards: `docs/engineering/frontend-test-standards.md`)

- `planConflicts.test.js`, `planConflictsEdges.test.js`, `reachUnroutable.test.js`: rewrite the
  `headline`/`body`/`actions` assertions to `line` for every row of the table, including the
  outlier-only ceiling, the no-step ceiling, nothing rated, nearest without drive, away with and
  without a wider tier, and "no origin action in the rating branch".
- `WindowFirstShell.test.jsx`, `planOriginShell.test.jsx`: the card is gone (no
  `window-first-conflict`), the verdict renders in the readout, the button applies the action and
  focuses the first `wf-heat-card-button`, and the status span carries `announce`.
- `WindowFirstLensBar` tests: phone and desktop verdict rendering, amber modifier only in verdict
  state, button outside the ellipsis span on desktop, status span always mounted (empty without a
  verdict), `GuideLink` still present.
- CSS pin tests touching `.wf-clash` / `.wf-conflict-slot` updated.
- Gates: `npm run lint && npm run test:coverage && npm audit --audit-level=high && npm run build`,
  the changed-lines `diff-cover` check, and the E2E smoke.

## Docs

`docs/engineering/reference/plan-tab.md` (lines citing `planConflicts`) and
`architecture-decisions.md` line 12: say the conflict message is the lens bar's verdict line, not a
card. `landing/guide/at-a-glance.html` does not describe the card; no guide change.
Changelog: `changelog.d/20261109-lens-verdict-line.md`, `### Changed — …`.

## Review round 1 corrections (these override the text above where they differ)

Logic and copy:
- **The away "Plan from home" action is removed, not made conditional.** `helpfulTier` can always
  walk to "Any" while the scope holds anything, so "reach shut, no wider tier" is unreachable; the
  table's two no-wider-tier rows are defensive only (the line renders with `action: null`), and
  tests must not assert them with contradictory inputs. The rating branch offers no origin action
  either. This deliberately drops the way home from the message: the place picker at the top is the
  route home (the design's reason). `planConflicts.test.js` `ratingAway` flips to assert its absence.
- **"this week" only when the plan's windows span more than one date.** On a single-date plan, use
  the ceiling card's `windowPhrase` instead (`0 at 4★+ tonight · best is 3★, Angel of the North,
  sunset 16:13` is too long; use `0 at 4★+ · best is 3★, Angel of the North, tonight sunset 16:13`),
  and for "nothing rated" just `0 at 4★+ · nothing rated yet`.
- **"best in reach is 3★"** when `reachMeasured && limitMinutes != null` (the ceiling is read from
  the reach-gated pool, so a bare "best is" would overclaim); otherwise "best is 3★".
- Labels match the chips: `Show any rating` (not "Show all ratings"); when the helpful tier is
  "Any", the label is `Show any distance`; otherwise `Widen to <tier label as the chip spells it>`.
- Reach with no nearest (every drive unroutable): `0 within 45 min` and the widen action only.
- `announce` is specified for every row (tests pin each).

Accessibility:
- **The ★ glyph is not spoken by NVDA** (see `WindowSheetDialog.jsx` ~617-626, `mapDrilldown.js`,
  `movement.js`). Render the visible `text` and the button `label` through one small helper/component
  that wraps each `★` as `<span aria-hidden="true">★</span><span className="sr-only"> stars</span>`
  and each ` · ` separator in `aria-hidden`. Reuse an existing helper if one exists. The button's
  accessible name must contain its visible words plus "stars" (WCAG 2.5.3); no `aria-label`.
  `announce` spells ratings in words ("4 stars or better", "3 stars") with no glyph.
- The status span is a **direct child of `.wf-lens`**, outside the `isMobile` branch (so a resize
  does not remount it) and never a direct child of `.wf-lens-res` (its `> span` rule would clip it).
  Known limit, accepted: on a first load or a tab return the bar mounts with the verdict already in
  it, so nothing is announced then; it is announced when a chip press creates or changes it.
- **Focus fallback:** after an action, if no `wf-heat-card-button` exists (the next state is still
  empty, e.g. a rating fix that reveals a reach verdict), focus the new verdict button, else the
  readout (`tabIndex={-1}` on the readout only in the verdict state). Never leave focus on `<body>`.
- Target size: `.wf-lens-verdict-act` is `display: inline-flex; align-items: center; min-height:
  24px; font-size: inherit` at every width (phone keeps 26px from the existing media rule).

Layout and CSS:
- **`scroll-margin-top: 0` for the verdict button** via `.wf-lens .wf-lens-verdict-act` (the grouped
  `.wf-clash-act` scroll-margin rule would make focusing a button inside the sticky bar scroll the
  page). Pin it in a CSS test.
- **Desktop below 1024px** the verdict readout takes its own row under the controls
  (`.wf-lens-res--verdict { flex: 1 1 100%; justify-content: flex-start }`, text may wrap); at
  ≥1024px it stays right-aligned on the controls' row with the text ellipsised and a `title` holding
  the full sentence. The button can never be squeezed out of the box.
- Place the amber rules after `.wf-lens-count b` (same specificity).
- Deleting `.wf-conflict-slot:empty` and the slot needs no replacement margin; update the comment
  (three rules become two) and `.wf-pane-status:empty`'s "travel together" wording.
- Tests that change: `windowFirstPaneStatusGap.test.js` (drop the slot case, fix header),
  `WindowFirstShell.test.jsx` (~376-400 first-child pin; ~1205-1288 conflict ids),
  `planOriginShell.test.jsx:510`, `WindowFirstLensBar.test.jsx` (~455-475 `data-variant`),
  `WindowFirstShellPicksRail.test.jsx:231` (readout regex), plus the three planConflicts suites.
  Re-run `WindowFirstShellSticky.test.jsx` and `safeAreas.test.jsx`. No E2E spec references the card.
