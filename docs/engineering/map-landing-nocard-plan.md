# Map tab: remove the landing card, add "is better" to the window pill (implementation plan)

**Source**: the design bundle vendored at `docs/design/map-landing-nocard/`. Its `README.md` is the spec,
and `VENDORING.md` says what was copied. The bundle amends `docs/design/map-landing/`, which was built as
`docs/engineering/map-landing-plan.md` L1–L7 (#792). Read the spec before either phase.

This document is the **port** plan. It covers four things:
- what the code has today;
- what goes;
- what stays because something else reads it;
- the decisions where the spec and the codebase's rules meet (§4).

**The one-sentence version.** The "Tonight, or tomorrow?" card goes, along with its state, its Escape
listener and its reopen row. The card made one comparison the pill could not ("the next window is better"),
and that becomes a short tappable line beside the pill. The line shows only on the first solar window, only
when the second solar window is in a strictly higher tier, and never on a phone.

**Status**: plan, 2026-10-25, reviewed by three read-only lenses (P1 completeness, P2 correctness and
accessibility, brief and test quality). P1 and P2 are not started.

| phase | branch | PR | what |
|---|---|---|---|
| P1 | `chore/map-landing-card-removal` | — | Remove the card, its state, its Escape listener, the reopen row and the leftovers |
| P2 | `feature/map-pill-is-better` | — | Add the "is better" line |

Owner rule (CLAUDE.md):
- Sonnet builds each phase in its own worktree.
- The planning session reviews the diff read-only.
- Each phase is one PR. P2 branches from `main` after P1 merges.
- A PR merges on green CI plus Codex 👍 on the exact head commit.

Changelog file dates are the commit date.

---

## §1 What the code has today (and the corrections to the spec's picture)

1. **The card.** `components/map/MapLandingCard.jsx` is mounted by `components/MapView.jsx` (~6826) while
   `landingOpen` is true. Its state, all in `MapView`:
   - `LANDING_SEEN_KEY = 'mapLandingSeenRun'` (localStorage), with its doc (~99–117);
   - `landingSeenKeyFor` (~146–154);
   - the state block at ~1804–1855: `landingSeenRun`, `landingReopenedRun`, `landingRunStamp`,
     `landingOpen`, `dismissLanding` and `reopenLanding`;
   - a **document** `keydown` effect (~1856–1904);
   - `selectLandingRow` (~6179–6190).

   The card is already off on the phone (`!isMobile && …`).
2. **The reopen row.** `WindowControl`'s `landingLabel` / `onReopenLanding` props and the
   `wf-win-landing` button at the top of the menu (~455–495).
3. **`utils/mapLanding.js` has a second reader.** `MapView` derives
   `landingModel = landingCardModel(...)` once (~3665). `landingModel.header` is **also** the heading of
   the phone peek sheet's Windows section (`MapPeekWindowsSection heading=…`, ~7033). The spec leaves that
   section unchanged, so `landingRows`, `landingHeader` and `LANDING_ROW_COUNT` **stay**. Only the
   card-only exports go: `elsewherePicks`, `nextWorthIt` and `landingCardModel`.
   - The swap is exact: `landingCardModel().header === landingHeader(landingRows({ events, verdicts }))`.
   - Keep the aurora-only fallback term (`|| (noSolarEvent ? …)`); `MapViewAuroraOnlyTab.test.jsx`
     ~256–262 pins it.
4. **Shared CSS that must survive.** Other components borrow these card classes:
   - `.wf-land-x`: the ✕ of `MapWindowPanel` and `MapRegionPanel`;
   - `.wf-land-pick`, `.wf-land-pick-glyph`, `.wf-land-pick-words` and the `.wf-land-pick[data-pick=…]`
     halves of the grouped selectors (~5818–5833): the medallion in both panels. This includes the
     `@media (max-width: 400px) .wf-land-pick-words` rule (~6280);
   - `.wf-win-landing` and `.wf-win-landing-txt`: the menu's footer drilldown row (`wf-win-more`,
     `WindowControl.jsx` ~611, ~628).

   Keep these rules and their names. Renaming them would touch more files and change nothing a user sees.
   Rewrite their comments so they no longer describe a card.

   Delete every other `.wf-land*` rule:
   - the card-only `.wf-land-pick-row`, `-pick-lbl`, `-pick-win` and `-pick-go`;
   - the `.wf-land-verdict…` halves of the grouped selectors at ~5696–5724 (keep the `.wf-win-verdict`
     halves);
   - the base and phone `.wf-land` blocks (~5970, ~6692);
   - the `.wf-map-tab[data-topic-strip] .wf-land` rule (~4276) and its comment block (~4263–4274).
5. **Things that exist only because the card did.**
   - `MapTopicStrip`'s `forceCollapsed` / `forceReason` props. Their one caller is MapView ~7255–7256.
     `topicStripMounted` stays, because other code reads it.
   - `data-topic-strip` on `.wf-map-tab`. Its only CSS reader is the `.wf-land` max-height rule.
   - **`useChromeBlHeight` stays.** Its nonce is part of `MapLabels`' `chromeKey` (MapView ~1314, ~6495).
     After P1, `--wf-chrome-bl-h` has no CSS reader. Say so in the hook's doc. Its "the counts footer can
     read it" line is already false (index.css ~4272), so correct that too.
   - `MapLabels.jsx`'s obstacle entry `[data-testid="wf-land"]` and its comment.
   - `runId`. `MapView` reads it only at ~1825, in the effect deps at ~1904 and in PropTypes at ~7628.
     `WindowFirstMapPane.jsx` (~858) only passes it through.
   - `openDrilldown`'s `dismissLanding()` call and its comment (~5648–5657).
6. **`paneIsOffScreen` stays.** It has a second caller: the drilldown-close focus restore (~1734). Rewrite
   its doc (~119–139) and the comments at ~1707 and ~1726. All three justify it by the card.
7. **The Escape listener does one thing besides close the card.** At ~1883–1893 it handles the case where a
   topic is selected and focus is on `<body>`. That happens in Safari, which does not focus a clicked
   button. The listener then clears the topic, spends the topic door and announces "Topic selection
   cleared." It does this only while the card is open. See D6.
8. **The verdict source for "is better".** The spec says "API regional verdict, with the field fallback at
   ≥ 3.7 / ≥ 2.8". The pill has no field fallback in production. It reads `evVerdicts`
   (`utils/mapVerdict.buildEvVerdicts`): served `displayVerdict`s, narrowed to the map's scope. Banding a
   number on the client is banned (CLAUDE.md "Backend-heavy"). **Use `evVerdicts` only.**
9. **"First solar window" means `landingRows(...)[0]`**, not `events.findIndex(kind === solar)`.
   - The EV list can lead with a past row, with a D-13 filler sunrise that has already passed
     (`served: false`), or with an away day. `landingRows` skips all three.
   - It is the same "your next two" the card compared.
   - `activeEvIndex` is `findEvIndex(mapEvents, eventType, nightDate)`. A served solar row's
     (eventType, date) pair cannot collide with a night, hw or past row, so `activeEvIndex === rows[0].index`
     is the right test.
   - A Plan door, an Ask landing or `‹` back onto `rows[0]` all show the line. That matches the prototype's
     `S.ei === first`.
10. **Pressing the line resets the reader's filters.** It calls `selectEvRow`, which resets filters on any
    SUNSET↔SUNRISE change (~5693–5705). Tonight's sunset → tomorrow's sunrise is the common case.
    Choosing that row from the menu or stepping with `›` does the same, and the spec asks for exactly
    that ("do the same as choosing it from the week menu"). **Not a bug. Do not "fix" it.** P2 tests it.

## §2 The rule, stated against the code

```
rows = landingRows({ events: mapEvents, verdicts: evVerdicts })   // already computed for the sheet heading
show iff
  !isMobile
  && rows.length >= 2
  && activeEvIndex === rows[0].index
  && rank(rows[0].verdict?.tier) and rank(rows[1].verdict?.tier) both defined
  && rank(rows[1]) > rank(rows[0])
  && neither row is a thin sample (thinCounts.get(row.id) == null for both; Q4)
  where rank: STAND_DOWN 0, MAYBE 1, WORTH_IT 2; any other tier (UNRATED, AWAITING) or no verdict → undefined
```

Copy: `` `${dayLabel} ${eventWord(eventType)} is better` ``, followed by an `aria-hidden` ` ›`.
- `dayLabel` is `row.dayLabel ?? row.label`, the pill's own formatter.
- `eventWord` is `utils/windowFirstCards.js`'s.
- Real data gives `Tomorrow sunrise is better`, `Today sunset is better` (when rows[0] is this morning's
  sunrise) and `Thursday sunset is better`.
- **"Tonight" is unreachable here.** "Tonight" is only the lead card's kicker, and rows[1] is never the
  lead. The spec's example `Tonight sunset is better` therefore cannot occur with the pill's formatter. See Q3.

Scope (`heatArea`) changes `evVerdicts`, so it changes the line. Reader filters do not touch `evVerdicts`
(map-tab.md: "The verdict moves with SCOPE and never with a reader filter"), so they do not change it.

This is a new client-side comparison over two scope-narrowed served verdicts, the same kind as the
`nextWorthIt` it replaces. It is licensed in `architecture-decisions.md`'s Map-tab scope class (P1 and P2
both edit it; see their Docs).

## §3 Phases

### P1: remove the landing card (`chore/map-landing-card-removal`)

**Code**
1. Delete `components/map/MapLandingCard.jsx`.
2. `MapView.jsx`:
   - Delete everything in §1.1.
   - Delete the `<MapLandingCard>` mount and its comment.
   - Delete the `dismissLanding()` call in `openDrilldown`.
   - Delete `forceCollapsed`/`forceReason` on `MapTopicStrip`.
   - Delete `data-topic-strip` and its comment.
   - Delete the `runId` prop and its PropTypes entry.
   - Keep `paneIsOffScreen` and rewrite its comments (§1.6).
   - Replace `landingModel` with `const nextTwo = landingRows({ events: mapEvents, verdicts: evVerdicts })`,
     and pass `landingHeader(nextTwo)` where `landingModel.header` was read.
   - Change no behaviour except the card's and D6's.
3. `WindowFirstMapPane.jsx`: stop passing `runId` (~858), and fix the comments at ~700 and ~855.
4. `WindowControl.jsx`:
   - Delete `landingLabel`, `onReopenLanding`, their PropTypes and the reopen button.
   - Rewrite the comments that mention them: the `aria-controls` note, the EARLIER group's note,
     `loadOlder`'s doc, the `pillRef` doc and the foreign-modal list (~274).
   - The menu now opens on the EARLIER group when there is one, then the window rows. It still ends with
     `wf-win-more`.
5. `utils/mapLanding.js`:
   - Delete `elsewherePicks`, `nextWorthIt` and `landingCardModel`.
   - Rewrite the module doc. It now describes "your next two solar windows" and their heading, read by the
     phone sheet (and, from P2, by the pill).
   - Remove the `allPoor`/`nextWorthIt` licence paragraph.
6. `MapTopicStrip.jsx`: delete `forceCollapsed`, `forceReason` and their branch.
7. `MapLabels.jsx`: delete the `wf-land` obstacle entry.
8. `index.css`: delete the card's rules and keep the shared ones (§1.4). Done means
   `grep -n "\.wf-land" frontend/src/index.css` lists only:
   - `.wf-land-x`;
   - `.wf-land-pick` (with `[data-pick=…]`);
   - `.wf-land-pick-glyph`;
   - `.wf-land-pick-words`;
   - comments that say why those keep the old name.
9. **Comments.** After the edits,
   `grep -rn -i "landing card\|welcome card\|MapLandingCard\|landingCardModel\|landingOpen\|dismissLanding\|mapLandingSeenRun" frontend/src --exclude-dir=test`
   must return nothing that describes a live card. Known sites:
   - `WindowFirstShell.jsx` ~1615;
   - `FiltersPopover.jsx` ~89;
   - `RegionsJump.jsx` ~103;
   - `MapLegendPanel.jsx` ~115;
   - `MapPeekSheet.jsx` ~344;
   - `MapWindowPanel.jsx` ~27, 35, 138, 154;
   - `MapRegionPanel.jsx` ~134;
   - `utils/mapForeignModal.js` ~7, `hooks/useOutsideDismiss.js` ~36, `utils/mapEvents.js` ~287,
     `utils/mapScopedTime.js` ~21, `utils/mapVerdict.js` ~40 and ~300, `utils/briefingDisplay.js` ~54;
   - `hooks/useChromeTlHeight.js` ~6;
   - MapView ~1726, ~3652, ~5004, ~5036, ~6313;
   - index.css ~5277, the z-ladder block ~5940–5968 ("card 1050"), ~13963.

   A comment that records history ("the card this replaced") is fine. One that describes the card as
   present is not.

**Tests** (read `docs/engineering/frontend-test-standards.md` first)

Drive the list with:
`grep -rnE "wf-land|wf-win-landing|mapLandingSeenRun|onReopenLanding|landingLabel|runId|forceCollapsed|forceReason|data-topic-strip|MapLandingCard|landingCardModel|nextWorthIt|elsewherePicks" frontend/src/test`.

Known files:
- **Delete** `MapLandingCard.test.jsx` and `mapLandingCardCascade.test.js`.
- `mapLanding.test.js`: delete the `elsewherePicks` / `nextWorthIt` / `landingCardModel` cases. Keep every
  `landingRows` and `landingHeader` case.
- `WindowControl.test.jsx`:
  - Delete the reopen-row cases (~176–260).
  - In the UNRATED cascade test (~759), drop only the `.wf-land-verdict` half.
- `MapViewHeat.test.jsx`: the landing describe block (~3433–3680) and ~2988–3001.
- `MapViewSelectionOrdering.test.jsx` ~455–478.
- `MapViewTopicStrip.test.jsx`: ~376–382, and the `data-topic-strip` assertions ~245, 253, 266.
- `MapViewTopics.test.jsx`: ~254–260 and ~447–477. The three Escape cases there change under D6: rewrite
  them as stated there.
- `MapViewMobileQuietStart.test.jsx`: the `mapLandingSeenRun` cases and the reopen-row case (~165–209).
- `MapViewBigSeas.test.jsx` ~215–222 (the frame contains `wf-land`).
- `WindowFirstMapPaneHeat.test.jsx` ~302–309: assert that `runId` is passed. Delete this.
- `MapViewAuroraOnlyTab.test.jsx` ~201.
- `MapTopicStrip.test.jsx`: the `forceCollapsed` cases.
- Cascade tests that compare against `.wf-land`:
  - `mapEmptyStateCascade.test.jsx`;
  - `mapWindowPanelCascade.test.js` (only the z comparison against `.wf-land`; keep every `.wf-land-x`
    assertion);
  - `bigSeasCascade.test.js` ~161–207;
  - `mapTopicStripCss.test.js` ~48–65;
  - `MapLabels.test.jsx` ~1172.
- Strip the now-meaningless `runId` props from these fixtures. They won't fail, but they are noise:
  - `MapViewBigSeasChips`, `MapViewHighWaterRealCallout`, `hwEventSweep`, `MapViewTopics`, `MapViewBigSeas`.
- Fix the comment at `mapForeignModal.test.js` ~13.

Where a case dismissed the card (`wf-land-close`) only to reach something else, delete the dismissal line
and keep the case. Where a case tests only the card, delete it.

**Add** `MapViewNoLandingCard.test.jsx` (spec check 7). Use a desktop fixture with two served solar rows.
- Assert `queryByTestId('wf-land')` is null at each of these points:
  - at load;
  - after `Escape` fired at `document.body`;
  - after `Escape` fired at `document.activeElement` inside the pane;
  - after the pill menu opens and closes.
- In the open menu, assert:
  - `queryByTestId('wf-win-landing')` is null;
  - the first `[data-ev-id]` row is the first window row;
  - the last child is `wf-win-more`.
- Make the absence non-vacuous: in the same test, assert that the pill and its verdict cell rendered.
- Add one case each, with the key fired at `document.activeElement` and never at the panel node:
  - Escape closes the window panel;
  - Escape clears a selected location.
- **Add** a phone-sheet heading case on `mockIsMobile`. The sheet's Windows heading must read the solar
  header (`Tonight, or tomorrow?` or the same-day form) from the MapView level. P1 rewires that expression,
  and today only `MapPeekSheet.test.jsx` pins it, with a literal string.

**Docs** (same PR):
- `reference/map-tab.md`:
  - Line 1 (the title): drop "and landing card".
  - Line 11: the landing listener and the topic Escape branch.
  - Line 15: `forceCollapsed` / "Closed while the welcome card is open", and `.wf-land` reading
    `--wf-chrome-bl-h` under `data-topic-strip`.
  - Lines 22 and 26: `.wf-land` as a chrome-height reader.
  - Line 28: rewrite the bullet as "the verdict and the picks". Replace "The tab opens on an answer…"
    with one sentence: the card was removed on 2026-10-25 by this plan, P1.
  - Line 30: the card's auto-open and `mapLandingSeenRun`.
- CLAUDE.md's reference-index row: "verdict and landing card" becomes "verdict and picks".
- `reference/plan-tab.md` ~129: the foreign-modal predicate list names the card's listener.
- `reference/ask-photocast.md`: the Ask popover's `stopPropagation` is justified by the card's document
  Escape listener. Rewrite the reason, keep the code, and check whether the `stopPropagation` is still
  needed. If it isn't, say so in the PR and leave the code as it is.
- `reference/architecture-decisions.md`, the Map-tab scope class:
  - Keep the letters, because code comments cite "(e)" and others.
  - Mark **(b)** and **(c)** "retired 2026-10-25 with the landing card (`map-landing-nocard-plan.md` P1)".
  - Change "nine figures" / "those nine" / "None of the nine" to **seven**.
  - In entry (e), remove "the landing card" from the list of places a window time is printed.
- Add a status line to the top of `map-landing-plan.md`: "L4's card was removed by
  `map-landing-nocard-plan.md` P1."

Changelog: `changelog.d/YYYYMMDD-remove-map-landing-card.md`, headed
`### Removed — the Map tab's "Tonight, or tomorrow?" card`. Say that the stale `mapLandingSeenRun` key is
left in storage (D5), and state D6's user-visible result.

**Gates**: `cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build`,
judged on exit codes. No backend change.

**Browser check** at 1280 and 820 (the map-landing-plan §9 recipe or the built harness), reporting numbers:
- `[data-testid="wf-land"]` count is 0 at load, after Escape, and after the menu opens and closes;
- the menu's first element is the EARLIER header or a window row.

### P2: the "is better" line (`feature/map-pill-is-better`)

**Code**
1. `utils/mapLanding.js`: add a pure `betterNextWindow({ rows, activeIndex, thinCounts })`.
   - It returns `{ row, index, text, tier }` or `null`, applying exactly the §2 rule minus the `isMobile`
     term, which belongs to the caller.
   - Export the tier rank as a named frozen object, not inline numbers.
2. `MapView.jsx`:
   - Compute `betterNext = isMobile ? null : betterNextWindow({ rows: nextTwo, activeIndex: activeEvIndex, thinCounts })`
     and pass it as `betterNext={betterNext}`.
   - The press reuses `selectEvRow`, through `onSelect`.
   - The phone is withheld by not passing the prop (D3).
3. `WindowControl.jsx`: a new optional prop `betterNext` (`{ row, text, tier }` or null). When it is set,
   render a **sibling** `<button type="button" data-testid="wf-win-better" className="wf-win-better">`
   between the pill and `›`, never inside the pill, which is itself a button.
   - **Content**: the text, then `<span aria-hidden="true"> ›</span>`.
   - **`aria-describedby`**: point it at an sr-only span `wf-win-better-why` holding the tier word
     (`VERDICT_LABEL[tier]`, e.g. "Worth it"). That gives screen-reader users the reason the stepper tick
     gives sighted ones. Do not use `aria-label`: it would replace the content.
   - **Press**: `setOpen(false)`, then `pillRef.current?.focus()`, then `onSelect(betterNext.row)`.
   - ⚠️ **Every route that moves the active window off `rows[0]` while the line has focus must move focus
     to the pill first.** The line unmounts itself, and a focused control that unmounts drops focus to
     `<body>`, where the pane's `onKeyDown` never fires. This defect has been fixed five times on this tab
     (map-tab.md). There are two routes:
     - the press (above);
     - `←`/`→` in `onKeyDown` while `e.target` is the line. Focus the pill **before** calling `step`.
       The `loadOlder` precedent focuses before the state change too.
   - Set `data-better` on the root `.wf-win-control` while the line shows (for the CSS in step 4).
   - Carry the active window's `data-tier` on the line, so its border matches the pill's tint.
4. `index.css`, per D1. **The pill and the line read as one frame:**
   - **`.wf-win-control[data-better] .wf-win-pill`**: `border-top-right-radius: 0;
     border-bottom-right-radius: 0; border-right-width: 0;`.
   - **`.wf-win-better`**:
     - `display: flex; align-items: center; align-self: stretch; flex-shrink: 0;`
     - `margin-left: -4px;` cancels the control's `gap: 4px`, so there is no seam. The spec's
       `margin-left: 2px` was written for a span inside the pill, and the pill's own `gap` stood in for it.
       Here the separator's `padding-left: 8px` gives the same spacing.
     - `padding: 0 10px 0 8px; border: 1px solid var(--color-plex-border); border-left: 1px solid <the #3A2C23 token>; border-radius: 0 10px 10px 0;`
     - `background: var(--color-plex-panel);`
     - The pill's mono family token, `font-size: 10px; letter-spacing: .02em; white-space: nowrap;`
     - `color: var(--color-badge-go);` (#A8C795)
     - `cursor: pointer`.
   - **Hover**: `#CFE3C2` plus an underline. Add a token if none exists.
   - **`[data-tier=…]` border-color** rules: copy `.wf-win-pill[data-tier=…]`'s colours, without the inset
     box-shadow, which belongs only on the pill's left edge.
   - **`:focus-visible`**: the same outline as `.wf-win-pill`. It must survive forced colours (memory
     "button focus in forced colours").
   - **Medallion words**: `.wf-win-control[data-better] .wf-win-pick-words` is visually hidden by the same
     technique as the ≤811px rule. A first window that carries a pick otherwise clips the verdict word even
     at 1280 (review finding: chip + label + time + medallion words + verdict exceeds the ~238px the pill
     keeps).
   - **The hide rule** (D1): `@media (max-width: <measured>px) { .wf-win-better { display: none } }`, with
     the matching pill-border reset. Use a **viewport** media query, not a container query:
     - `container-type` on `.wf-win-control` applies layout containment, which makes it a stacking context.
       That would confine `.wf-win-menu`'s z-index under the topic-chip row that follows it in
       `.wf-map-chrome-tl`.
     - Before relying on the viewport query, confirm in the browser that the Map pane spans the viewport at
       each measured width (pane width == `innerWidth`). If it does not, stop and report.
5. Change nothing else. The pill's verdict cell, region line, ticks and thin count stay as they are. The
   medallion words are hidden only while the line shows.

**Tests**
- `mapLanding.test.js`, `betterNextWindow`. Each "none" case must differ from a "shows" fixture by exactly
  the one clause it tests (memory "member + non-member doesn't pin the clauses").
  - Shows: Maybe→Worth it, Poor→Maybe, Poor→Worth it.
  - None: both rows the same tier (×3); a lower second tier; an unranked first row (null, `UNRATED`,
    `AWAITING`); an unranked second row; a thin first row; a thin second row; one row; zero rows;
    `activeIndex !== rows[0].index`.
  - Text: Today, Tomorrow and a weekday, each with sunrise and sunset. Write no "Tonight" case (§2).
- `WindowControl.test.jsx`:
  - The line renders only with the prop.
  - Its accessible name is exactly `Tomorrow sunrise is better`, and its description is the tier word. Use
    the accessible-name and description helpers, not `textContent`.
  - A press calls `onSelect` once with the second row. The menu stays closed: `wf-win-menu` is absent and
    `aria-expanded` is false.
  - After a press, re-render without the prop (what the parent does): `document.activeElement` is the pill.
  - A press while the menu is open closes it.
  - `ArrowRight` with focus on the line calls `onSelect` with the next row. After a re-render without the
    prop, focus is on the pill. Repeat for `ArrowLeft`.
  - `data-better` is on the root only while the line shows.
- `MapViewIsBetter.test.jsx` (spec checks 1–5, plus the rest):
  - Cold open with Maybe then Worth it: exactly one `wf-win-better`, text `Tomorrow sunrise is better`.
  - Same tier: zero.
  - Press: the active window is the second solar row, the menu is closed, and there are zero lines.
  - The press resets the star filter on that sunset→sunrise change, exactly as choosing the row in the menu
    does (§1.10).
  - Step away with `›`: zero lines. Step back with `‹`: the line returns.
  - Apply the 4★+ and Seascape filters: presence unchanged.
  - Flip the scope so the tiers change: presence follows.
  - Phone (`mockIsMobile`): zero lines, with the same fixture that shows one on desktop.
  - A past row and a passed filler row ahead of the first served solar row do not move the line.
  - A thin first or second row: zero lines.
- A cascade test (`readFileSync` of `index.css`, the house pattern) pinning:
  - `.wf-win-better`'s colour, size, nowrap, separator, negative margin and focus outline;
  - the `[data-better]` pill-border and medallion-word rules;
  - the hide media query.

**Docs** (same PR):
- `reference/map-tab.md`: one sentence in the verdict bullet. It names the line and its rule, and says it
  is the one sentence of the removed card that survives.
- `reference/architecture-decisions.md`: fill scope-class entry **(b)** with
  `mapLanding.betterNextWindow`, a strict tier comparison of the next two solar windows' scope-narrowed
  served verdicts, suppressed on a thin sample. (c) stays retired. The count becomes **eight**.

Changelog: `changelog.d/YYYYMMDD-map-pill-is-better.md`, headed
`### Added — the window pill says when the next window is better`.

**Browser check**, by measurement. jsdom evaluates no media query here (`css: false`). Run the seven spec
checks at 1280, 1024 and 820 with two seeded fixtures (seed them as map-landing-plan §9 did for the
all-Poor state):
- (i) tonight Maybe, tomorrow sunrise Worth it, no pick on rows[0];
- (ii) the same with "Also good" on rows[0].

Done means all of these hold, and the PR reports each number:
- `›`'s right edge is identical with and without the line;
- the control is 36px tall (one row) at all three widths;
- `scrollWidth <= clientWidth` for the day label and the verdict word, in both fixtures;
- no visible seam between the pill and the line: the line's left edge equals the pill's right edge.

Then at 390, report the line count (0) and the control height (40px). Find the hide width by stepping the
viewport down from 1024 until a clip appears, and record it in the CSS comment. **If the hide width is
above 1024px, stop and report before opening the PR.**

If the browser needs a sign-in, ask the owner. Otherwise use the built harness, and say which claims were
seen and which were only tested.

## §4 Decisions

**D1: where the line sits (OWNER, defaulted to A).** The spec puts the line *inside* the pill, before the
`▾`, and says it must never push the control onto two rows. Two shipped facts meet it:
- **The pill is a `<button>`**, so the line cannot go inside it and has to be a sibling (the spec says so
  too). The `▾` is the pill's last child, so the line sits after the caret.
- **The control is a constant 504px wide** (`.wf-win-control`, capped by the pane), so `‹ ›` never move as
  the reader steps. That is #773, the owner's own complaint. The pill fills what is left: 432px at the cap,
  against a measured worst case of 417px. The cap holds down to a viewport of about 812px.

The line is about 185–195px wide. The ways to fit it:

| | how | cost |
|---|---|---|
| **A (default)** | A sibling inside the fixed control, joined to the pill as one frame (§3 P2 step 4). The pill yields width: the region line ellipses first, the medallion words hide while the line shows, and the day label never shrinks. The line is hidden below a measured viewport width. | `‹ ›` never move. While the line shows (first window only), the pill has about 238px, so the region name is short or ellipsised. Narrow tablets may lose the line, as the phone does; the `›` tick still shows the tier. |
| B | Widen the control's constant width to fit the line (about 690px) on frames that allow it. | `‹ ›` never move and the region keeps its room. But every window's pill is about 190px wider and mostly empty, and the label-obstacle budget must be re-measured (map-tab-v2 §4 #31, harness at `scripts/measurements/label-obstacle/`). |
| C | The literal reading: let the pill grow while the line shows. | `›` jumps about 190px the moment the reader taps the line or steps off the first window. That is exactly what #773 removed. Not recommended. |
| D | Put the line on its own row under the control in `.wf-map-chrome-tl`, where the big-seas line already lives. | No compression and no hide width. But it is not "in the pill". It adds a row (the spec forbids a second row of the control group), it moves the big-seas line and the topic chips down on the first window only (and back when tapped), and `useChromeTlHeight` and the label obstacle must be re-measured. |

**D2: no field fallback** (§1.8). The spec's ≥3.7 / ≥2.8 fallback is a convenience of the prototype. The
served verdict is the pill's one source.

**D3: the phone gets nothing, because the prop is not passed.** The spec says `display: none`. Not
rendering looks the same to the reader, can be tested in jsdom, and keeps phone work phone-only.

**D4: remove the card's leftovers** (`forceCollapsed`, `data-topic-strip`, `runId`, the obstacle entry)
rather than leave them dead. Keep the borrowed class names (§1.4).

**D5: leave `mapLandingSeenRun` in storage.** It is a few bytes and nothing reads it. Code to clear it would
stay in the codebase for good.

**D6: the body-focused Escape for a selected topic goes with the card (OWNER, defaulted).** Today it works
only while the card is open. A reader who has dismissed the card already gets nothing from Escape with
focus on `<body>` and a topic selected, because the pane's `onKeyDown` cannot hear a key that lands on
`<body>`. After P1 every reader gets that. The loss is limited to Safari readers on their first visit of a
run. The alternative is a standing document listener for this one case. That would be a new document-level
listener on a pane that is never unmounted, the hazard `paneIsOffScreen` exists for, and the card's own
comment says such a widening "belongs to a phase that reviews it". Default: drop it.
- In `MapViewTopics.test.jsx`, delete the two card-ordering cases.
- Rewrite the body-focus case to assert today's post-dismissal behaviour: a body-focused Escape leaves the
  topic selected, and an Escape inside the pane still clears it.
- If the owner wants the standing listener, it is a follow-up with its own review.

## §5 Owner questions (the build proceeds on each default unless the owner says otherwise)

- **Q1 (D1)**: A, B, C or D? Default **A**.
- **Q2**: if the measured hide width means iPad portrait (768/820) loses the line, is that acceptable?
  Default **yes**: the `›` tick still shows the next window's tier. Above 1024px is a stop, not a default.
- **Q3 (§2)**: the pill's formatter gives `Today sunset is better`, never the spec's `Tonight sunset is
  better`. Keep the pill's word, so the line and the pill agree after the tap? Default **yes**.
- **Q4 (§2)**: suppress the line when either window is a thin sample (the pill shows "· N rated")? "Better"
  on two rated places claims more than the pill does. Default **suppress**.
- **Q5 (D6)**: drop the Safari body-focused Escape for a selected topic along with the card? Default
  **drop**, with a standing listener as a reviewed follow-up if wanted.
