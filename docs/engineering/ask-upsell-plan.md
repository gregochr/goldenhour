# Ask PhotoCast's Lite lock becomes a worded upsell — plan

> Owner decision, 2026-10-24 (tide Pro-gate plan, Q13): "the upsell becomes the house pattern; Ask
> follows in a separate plan." This is that plan.
>
> Status: **plan, reviewed, not built.** Two Sonnet-built client PRs, **U1 → U2** (§7). There is no
> backend phase, because the Ask backend refusal stays exactly as it is (§2). The plan had one adversarial
> round with three read-only lenses: shell focus and Escape order, the phone Map and role leakage, and
> accessibility, CSS and tests. Their findings are folded in, marked *(rev)*. The owner questions are in
> §8. Each has a default, which is what gets built if it is not answered.
>
> Line references were correct at `3538b83a` (main, 2026-10-25). **The builder must re-read every
> reference before relying on it.** ⚠️ marks a trap the code sets for the builder.

## 1. Where we are

Ask became Pro and Admin only on 2026-10-24 (`ask-pro-gating-plan.md`: F1 #1357, B1 #1358). F1 built the
lock the drive filter uses, the greyed lock that was then the house pattern:

| Entry | Component | Today, for LITE |
|---|---|---|
| Phone, Plan and Coming up | `AskBar` (`components/ask/AskBar.jsx`), the fixed 48px bar | `disabled`; `k`/`q`/`go` inside `.wf-ask-bar-inner.wf-lens-locked`; the `wf-lens-pro` pill inside the bar (`ask-bar-upsell`); no `aria-haspopup`/`aria-expanded` |
| Phone Map | `MapPeekAsk` (`components/map/MapPeekAsk.jsx:170–201`), the peek row's entry | `disabled`; the entry in `.wf-map-peek-ask-lockwrap.wf-lens-locked`; the pill is the row's sibling (`wf-map-peek-ask-upsell`) |
| Tablet (640–1023px), Plan, Coming up, Map | `AskField` width 260 → would open `AskSheet` | `disabled`; in `.wf-askf-lockwrap.wf-lens-locked` inside `.wf-askf-lockrow`; the pill outside the grey (`ask-field-upsell`) |
| Desktop and wide (≥1024px), every tab but Operations | `AskField` 260/340 → would open `AskDock` | as tablet; no `aria-keyshortcuts`, no `<kbd>/</kbd>` |
| The `/` key (≥1024px) | `WindowFirstShell.jsx:1915–1933` | refused at `if (askDisabled) return` **before** `preventDefault`, so the key reaches the browser (Firefox quick-find) |

Where the lock comes from:

- `context/AskContext.jsx:456–458`: `if (!isPro && (availability === 'on' || availability === 'down')) availability = 'locked'`.
  `pending` and `off` are never turned into `locked` (no flash on a flag-off server).
- `WindowFirstShell.jsx:1436`: `askLocked = ask.availability === 'locked'`. It is folded into
  `askDisabled` (`:1466`), which is the controls' `disabled` **and** the `/` refusal.
- The openers refuse: `openAskSheet` (`:1482`), `openAskDock` (`:1680`), `MapView`'s `openAskSection`
  (`MapView.jsx:6034`).

The backend: `POST /api/ask` and `GET /api/ask/ready` return 403 `NOT_ENTITLED` for LITE with the flag on
or off. `GET /api/user/settings/ask` stays 200. `REFUSAL_SENTENCE` maps `NOT_ENTITLED`
(`reference/roles-and-gating.md:25, 58`).

The template is the tide teaser's upsell (`tide-pro-gate-plan.md` §6, built in #1380):

- `components/map/TideUpsell.jsx`:
  - `TideUpsellContent`: the pill, an `h3` heading with `tabIndex={-1}`, the body, and a static preview
    built from constants only;
  - `TideUpsellPopover`: `role="dialog"`, `aria-labelledby` and `aria-describedby`, **no
    `aria-modal`**, inline and never portalled;
  - focus goes to the heading on open;
  - Escape is handled in the popover, guarded by `foreignModalOverPaneOf`;
  - `onBlur` closes the popover without moving focus when focus leaves for something that is not a door;
  - a `Not now` ghost button.
- `MapView.jsx:4129–4240`: the open state is `openMapMenu === 'tideUpsell'`. The opener ref is recorded,
  and focus goes back to it only when focus was lost (`returnTideUpsellFocus`, `:4178`). The popover
  closes on an outside press, a map click, a hand pan or zoom, and the teaser going away.
- The trigger (`MapTideStrip.jsx:607–631`) is a live `<button>` with `aria-haspopup="dialog"`,
  `aria-expanded` and `aria-controls`. It is **not** greyed, and the pill is inside it.
- On the phone there is no floating popover. The peek's Tide section draws the same content inline
  (`MapPeekTideSection.jsx:67–76`, `.wf-tide-upsell-inline`).
- There is no link and no "upgrade" text anywhere. The App Store forbids pointing at a web purchase,
  `/upgrade` is not a route, and `UserSettingsModal.test.jsx` pins that no `/upgrade/i` text appears.

## 2. The target

**For PRO and ADMIN, nothing changes.** Every existing PRO suite stays green with no assertion edits.

**The backend does not change.** The 403 `NOT_ENTITLED` on `POST /api/ask` and `GET /api/ask/ready` stays
the real enforcement, and `REFUSAL_SENTENCE`'s `NOT_ENTITLED` entry stays (a demoted PRO session still
reaches it, `ask-pro-gating-plan.md` §4.1 step 4). The upsell is static client text and fetches nothing.

**For LITE, every Ask entry becomes a door to a worded upsell:**

1. **The entry is a live trigger, not a greyed control** (Q1). It looks like the PRO entry and keeps its
   prompt text, with the `wf-lens-pro` pill **inside** the button, as on the tide teaser. It has no real
   `disabled` for the lock (it is still `disabled` for the shell's own reasons, §3.2) and no
   `.wf-lens-locked` grey or `pointer-events: none`. It carries `aria-haspopup="dialog"` and
   `aria-expanded`, plus `aria-controls` while the upsell is open.
2. **Pressing it opens the Ask upsell:** the pill, a heading, a body, a static preview and `Not now`.
   - **Tablet, desktop and wide:** a non-modal inline popover anchored under the tab row's field.
   - **Phone, Plan and Coming up:** a non-modal popover anchored above the fixed bar (Q2).
   - **Phone Map:** the peek's Ask section opens and draws the same content inline, as the phone Tide
     section does.
3. **Nothing about Ask itself is reachable.** No sheet, no dock, no `AskConversation`, no
   `AskInputRow`, no request to `/api/ask*`.
   - `openAskSheet` and `openAskDock` still refuse while locked.
   - `askTyped`, `openReady` and `retry` still refuse on `!isPro` (`context/AskContext.jsx:349, 362, 402`).
   - On the phone Map, `openAskSection` **does** open the section (§4.4). There, the conversation is
     refused by `MapPeekAsk`'s render itself *(rev)*.
4. **The `/` key stays refused and is not swallowed** (Q3). The field still carries no
   `aria-keyshortcuts` and no `<kbd>` hint while locked.
5. **No link and no "upgrade" text** (tide Q1, fixed by the house pattern).
6. **The copy is a placeholder** until the owner signs it off (Q4):
   - heading: `Ask about the forecasts in plain English`;
   - body: `Ask where and when to go for the light, and get an answer drawn from the forecasts, with the spots shown on the map.`
7. **The preview is a static sketch** (Q5). It is a committed SVG, built from constants only and
   `aria-hidden`: a question line and two answer bars with two numbered marker dots. It contains no
   place name, no score and no text. ⚠️ It must never take a prop. A sketch that showed served data or a
   real place would read as a live answer.

The house rule this applies is now recorded in `roles-and-gating.md:59`: "a Pro feature's locked
surface opens a worded upsell … the greyed lock stays for controls too small to carry one". An Ask entry
is never too small: the smallest is the peek row's entry.

⚠️ Two older statements of the rule still describe the greyed lock as the general pattern *(rev)*:

- `CLAUDE.md:207`: "PRO features are shown greyed to LITE with a 'Pro' text pill";
- `roles-and-gating.md:52–53`: opacity .45, `pointer-events: none`, `disabled`.

U1 rewords both (§6). The planning session may write that CLAUDE.md line, since it is docs.

## 3. State and wiring (U1)

### 3.1 The content component — shared, with the tide DOM untouched

- Add `components/ask/AskUpsell.jsx`, structured like `TideUpsell.jsx`:
  - `ASK_UPSELL_HEADING` and `ASK_UPSELL_BODY` constants;
  - `AskUpsellPreview` (no props);
  - `AskUpsellContent({ headingId, headingRef, bodyId, testIdPrefix })`;
  - `AskUpsellPopover({ id, rootRef, onClose, onFocusAway, placement })`, where `placement` is
    `'field' | 'bar'`.
- **Do not refactor `TideUpsell.jsx`** (memory `shared-component-blast-radius`). Its DOM, test ids and
  class names stay byte-identical, so `MapTideLocked.test.jsx` and `tideLockedCss.test.js` stay green
  unedited.
- **Share the look in CSS only.** Turn the visual rules for `.wf-tide-upsell`, `-heading`, `-body`,
  `-preview` and `-dismiss` (and their `:hover`, `:focus` and `:focus-visible` rules, `index.css:4175–4232`)
  into selector lists that also name `.wf-ask-upsell*`. ⚠️ Do **not** add the Ask classes to
  `.wf-tide-upsell-anchor` (`:4166`): that rule positions the tide popover inside `.wf-map-chrome-bl`.
  Ask gets its own anchors (§4).
- Unifying the two components into one `ProUpsell` primitive is a possible later clean-up and is not in
  this series.

### 3.2 The shell (`WindowFirstShell.jsx`)

1. **Split `askDisabled`** (`:1466`), which today means two things:
   - `askBlocked = ask.availability === 'down' || Boolean(contentDisabled) || askDialogOpen`. This is the
     controls' real `disabled`. A locked trigger is still disabled while a shell dialog or settings is
     open, or while the content is disabled, so the "the entry is disabled while a dialog stands" rule
     (`:1426–1432`) holds for the upsell trigger too. `down` and `locked` cannot both be true
     (`AskContext.jsx:458`).
   - `askKeyRefused = askBlocked || askLocked`. This is the `/` listener's refusal (`:1920`), still
     **before** `preventDefault`. `askKeyRefused ⊇ askBlocked`, so the key is never live where the
     control is not.
   - The full list of readers of `askDisabled` *(rev, grepped)*:
     - `:1466` (the definition);
     - `:1920` (the key refusal) and `:1934` (that effect's deps array). Both become `askKeyRefused`;
     - `:2376` and `:2392` (the fields) and `:2744` (the bar). These become `askBlocked`.
   - Reword the comments that describe the old fold: `:1434–1435`, `:1465`, `:1908–1909`.
2. **The open state:** `const [askUpsellFor, setAskUpsellFor] = useState(null)`, holding the kind of
   entry that opened it: `'bar' | 'field' | 'dock-field' | null` *(rev)*. Then:
   - `askUpsellOpen = askUpsellFor !== null && askUpsellFor === currentEntryKind`;
   - an id from `useId()`;
   - `askUpsellRootRef`.

   The opener is the existing `askTriggerRef`, which the bar and both fields already set.
   - `toggleAskUpsell()` (written so it can be lifted into the shell's `useCallback`s):
     - **closing** always works;
     - **opening** returns if `foreignDialogOpen(shellRef.current)`. Guard the open only *(rev)*, or the
       trigger could not close the popover while a foreign dialog stands.
   - The entries' `onOpen` becomes `askLocked ? toggleAskUpsell : openAskSheet` (or `openAskDock`). The
     openers keep their `if (askLocked) return` as a second layer.
3. **Let go during render** (the `askSheetOpen` shape, `:1452`). If `askUpsellFor !== null` and any of
   these holds, set it to null:
   - `!askLocked`;
   - `askBlocked`;
   - `askUpsellFor !== currentEntryKind`.

   Recording the kind *(rev)* is what catches **bar ↔ field**. A check for "is there an entry at all" does
   not, because a field replaces the bar. The check covers:
   - a breakpoint change that removes the entry or swaps its kind;
   - Operations;
   - a shell dialog or settings opening;
   - the role becoming PRO (it cannot mid-session today, §3.3, but it must not strand the state).

   ⚠️ **Focus.** A swap unmounts the trigger and possibly the popover that held focus, and focus cannot be
   moved during render. Add a `useLayoutEffect` keyed on `currentEntryKind` that, when focus is stranded
   (`document.activeElement` is `<body>` or disconnected, the `returnTideUpsellFocus` rule at
   `MapView.jsx:4178–4184`), sends it to the new trigger, or to `askRestoreFallback`
   (`WindowFirstShell.jsx:1554–1558`) when there is none.
4. **`selectTab` closes it** (CLAUDE.md invariant: every route out of a tab closes its dialogs through
   `selectTab`). Add `setAskUpsellOpen(false)` beside `setAskSheetOpen(false)` there. On ≥640px the field
   is drawn on Plan, Coming up and Map, so a tab press would otherwise leave the popover open over the
   next tab. Test it from each tab.
5. **Close paths** (one `closeAskUpsell({ restoreFocus })`):
   - `Not now` closes and restores focus to `askTriggerRef`.
   - **Escape inside the popover**: the popover's own `onKeyDown` calls `e.stopPropagation()`, then closes
     and restores focus.
     - ⚠️ `stopPropagation` is needed. On the Map tab the landing card's Escape listener is a bubble-phase
       `document` listener (`MapView.jsx:1971–1993`). It defers only for `openMapMenu`, a selection or a
       topic, and `foreignModalOver` matches only `aria-modal`, so it does not see this popover. Without
       `stopPropagation`, one Escape would close the popover and the landing card together. React 19
       delegates to `#root`, below `document`, so stopping the event in the React handler is enough
       (reviewed: no `keydown` listener uses capture or `window`).
     - The same stop also hides that Escape from `useSpotPeek` (`hooks/useSpotPeek.js:186`) and
       `HealthIndicator` (`components/HealthIndicator.jsx:150`) if either is open. That is accepted: one
       Escape closes one layer.
     - The guard is `foreignDialogOpen(shellRef.current)` (`utils/shellForeignDialog.js:27–29`), which
       matches **any** `role="dialog"` outside the shell root, modal or not *(rev)*. If one stands, stand
       down. Do not use `foreignModalOverPaneOf`, because the popover is not inside the Map pane.
   - **Escape on the trigger** *(rev)*. The field is the tab row's last child and the popover is its next
     sibling, so Shift+Tab from the heading lands on the trigger, and the popover stays open (step: focus
     leaving). Its Escape never reaches the popover's handler, and on the Map tab the landing card would
     take it. So, while the upsell is open, the trigger's own `onKeyDown` handles Escape the same way:
     `stopPropagation`, close, focus stays on the trigger. Do the same on the bar.
   - **Focus leaving** (the tide `onBlur`, `TideUpsell.jsx:112–119`):
     - **a null `relatedTarget` is ignored** *(rev)* and left to the outside press. Safari does not focus a
       clicked button, so a null here would close the popover, and the trigger's click would reopen it;
     - a `relatedTarget` inside the popover or on the trigger is ignored;
     - any other `relatedTarget` closes **without** moving focus.
   - **An outside press**: a `pointerdown` listener on `document` while open.
     - It returns first if `foreignDialogOpen(shellRef.current)` *(rev)*. This is the
       `useOutsideDismiss` #37 guard (`hooks/useOutsideDismiss.js:135–142`): a press inside the postcode
       prompt or the four-day sheet must not close the upsell invisibly behind it.
     - It closes when the target is outside the popover **and** outside the trigger, because the
       trigger's own click toggles it.
     - ⚠️ Do not use `useOutsideDismiss` itself. It ignores presses inside the map frame (its header
       doc), and a press on the map must close this popover, which belongs to the tab row and not to the
       map.
     - A press on a Plan card, a Coming up row or the map therefore closes the upsell. It does not
       swallow the press: the card still opens its popup on the same click.
   - **The trigger pressed again** closes it and restores focus to the trigger.
6. **Mutual exclusion with the tide upsell** (desktop and tablet Map tab, both can be drawn):
   - Opening the Ask upsell is a press on the tab row, which is outside the map frame, so the tide
     popover's `useOutsideDismiss` already closes it.
   - Opening the tide upsell is a press inside the map, which the Ask upsell's outside press (step 5)
     closes.
   - Test both directions. Never both open.
7. **The Ready list's "· free" heading, `AskEmptyState`, the allowance hook and `REFUSAL_SENTENCE` are
   untouched.**

### 3.3 The context and the stale role

- `AskContext` keeps `locked` as the availability, and nothing new is fetched.
- **One defensive addition** *(rev)*. Nothing clears the in-memory conversation when `isPro` turns
  false, so a locked reader with a settled answer would get:
  - `askMode` `'minimised'`;
  - picks on the map;
  - a `MinimisedLine` whose `onOpen` opens the upsell over a stale answer.

  This cannot happen today, because the client role is fixed for the session: a demoted session stays
  client-PRO (below). Still, add one effect to `AskContext` that dispatches `CLEARED` when `isPro` goes
  from true to false, with one test, so a future in-session role refresh cannot reach it.
- The stale-role facts in `ask-pro-gating-plan.md` §4.1 step 4 hold unchanged:
  - a promoted LITE→PRO session sees the upsell until the next sign-in (record it beside the existing
    note);
  - a demoted PRO→LITE session sees the live entry and gets the 403 sentence.

## 4. Surfaces (U1: bar and field; U2: the phone Map)

### 4.1 `AskField` (tablet, desktop, wide)

- While locked:
  - one `<button class="wf-askf" data-locked="true">` holding `k`, `q` and the pill
    `<span class="wf-lens-pro" data-testid="ask-field-upsell">Pro</span>` **inside** it, last;
  - `aria-haspopup="dialog"`;
  - `aria-expanded={upsellOpen}`;
  - `aria-controls={upsellOpen ? upsellId : undefined}`;
  - no `aria-keyshortcuts`, no `<kbd>`, and `disabled` only from `disabled`.
- Delete `.wf-askf-lockrow`, `.wf-askf-lockwrap` and their CSS, **including**
  `.wf-lens-locked .wf-askf:disabled { opacity: 1 }` (`index.css:14421–14433`) *(rev)*. A locked field
  that is disabled by `askBlocked` must dim like PRO's.
- **The accessible name** comes from the content: the `k` span is `aria-hidden` (`AskField.jsx:68`), so
  the name is "*{prompt}* Pro". **No `aria-label`** (memory `aria-label-replaces-content`). Assert the
  computed name. This departs from the tide trigger, which does use an `aria-label`
  (`MapTideStrip.jsx:620`): there the visible text is not the whole name, and here it is.
- ⚠️ **The width.** The pill now takes room inside the 260px or 340px field rather than beside it. The
  prompt is clipped, so it must ellipsize and never push the pill out. Measure at 640px, 1023, 1024, 1179
  and 1180.
  - The tab row's 34px "Ask" collapse applies only at `.wf-tabrow[data-tab-count="4"]`
    (`index.css:14483–14500`). Four tabs means an admin, who is never locked. So it never meets a locked
    field: draw nothing for it *(rev)*.
  - ⚠️ Do not add the pill to the `.wf-askf-q` rule's selector list. `askCss.test.js:146` matches
    `/\.wf-askf-q\s*\{/`, which needs the `{` straight after that selector.
- **Contrast.** `.wf-askf` uses `--color-plex-surface` (`index.css:14407`), the same surface as
  `.wf-lens`, where the 12.59:1 was measured *(rev)*. Measure only the two new backgrounds:
  - the field's expanded state, a gold .1 tint (`:14416`);
  - the bar's `rgba(20,15,12,.96)`.
- **Forced colours** *(rev)*:
  - The open state is shown only by border and background colour (`.wf-askf[aria-expanded="true"]`), and
    the bar has no expanded style at all. Add a `forced-colors: active` rule giving both triggers
    `border-color: Highlight` when `aria-expanded="true"`, as the tide trigger does
    (`index.css:4156–4158`). Pin it.
  - The CanvasText pill rule for the bar (`:14336`) exists because "a disabled button greys it". That
    reason no longer applies to the lock, and the rule would keep a blocked trigger's pill bright. Delete
    it, and let a blocked trigger's pill go GrayText with the rest of the button.
- **The prop API:** `locked` stays. Add `upsellOpen` and `upsellId`. With `locked`, `expanded` and
  `controls` are ignored.

### 4.2 The popover under the field

- Render it as the tab row's next sibling (inline in the shell, **never portalled**: a portalled dialog
  would be "foreign" to `foreignDialogOpen` and to every Map Escape rule).
- Place it absolutely under the field, right-aligned to it, 320px wide (clamped to the column minus
  16px).
- **The z-index on the Map tab** *(rev)*. `#map-container` is `position: relative; z-index: 0`
  (`MapView.jsx:6534–6540`; the ladder is at `index.css:5959`). That makes it its own stacking context,
  so Leaflet's panes and controls (400–1500) stay inside it.
  - A positioned anchor with a z-index between 1 and 49, in the shared ancestor, paints over the map and
    stays **below `Modal`'s z-50 and the sheet scrim**.
  - The map's own chrome outside `#map-container` (the `.wf-map-chrome-*` corners) has its own z-index.
    Read it, and keep the anchor above it.
  - Check it in the browser on the Map tab, over a marker and over the top-right chrome, and say what
    was seen.
- It is drawn on Plan, Coming up and Map, and never on Operations (no entry there; `askGhost` is a
  spacer, not a trigger).
- Focus moves to the heading on open (`preventScroll`).

### 4.3 `AskBar` (phone, Plan and Coming up)

- While locked:
  - drop the inner `.wf-lens-locked` span: `k`, `q` and `go` render as for PRO;
  - the pill stays a **direct child** of the bar, in the order `k`, `q`, pill, `go`, so
    `.wf-ask-bar > .wf-lens-pro` (`index.css:14333`) still matches *(rev)*;
  - `aria-haspopup="dialog"`, `aria-expanded` and `aria-controls` as on the field;
  - `disabled` only from `disabled`.
- Delete `.wf-ask-bar-inner` **and** `.wf-ask-bar[data-locked]:disabled { opacity: 1 }`
  (`index.css:14325`) *(rev)*. Otherwise a locked bar disabled by `askBlocked` (settings or a card popup
  open) would render at full strength, looking live.
- `askCss.test.js:109–129` (the bar's z-index and fixed geometry) stays green unedited.
- **The `go` arrow (↑)** suggests "send". Default: keep it, since it is the same bar. Q6 offers to hide it
  while locked.
- **The popover above the bar:**
  - `position: fixed`, with `left` and `right` taken from the bar's own sides,
    `calc(10px + var(--safe-l))` and `calc(10px + var(--safe-r))` (`index.css:14305–14306`) *(rev)*;
  - `bottom: calc(10px + var(--safe-b) + 48px + 8px)`, derived from the bar's own rule at
    `index.css:14303–14307` (read it, don't copy numbers);
  - z-index 40, the bar's, so it stays below `Modal` and the sheet scrim;
  - inline in the shell, beside the bar.
- Measure at 320px and 375px, with the safe-area inset, and in landscape. Check that the popover never
  covers the bar, so the trigger stays pressable to close it.
- Scrolling the page does not close it. It is anchored to a fixed bar, so it does not drift.

### 4.4 `MapPeekAsk` (phone Map) — U2

The tide phone precedent is an inline section in the peek, not a floating popover. ⚠️ U2 **deliberately
diverges** from it in three ways *(rev)*:

- **The surface.** Locked tide is a `sheetSection` (`MapPeekTideSection.jsx:66–76`): the sheet sits at
  `openWithAsk`, 408px, and the Windows, Tide and Layers buttons stay drawn. U2 opens `'peek:ask'`, which
  is `askMode === 'expanded'`: 470px, and `buttonsShown` is false (`MapPeekSheet.jsx:127`), so those
  three buttons are hidden while it is open.
- **The close.** Locked tide has no `Not now` (the sheet's own close does that); U2 has one.
- **Focus.** Locked tide focuses its peek button (`MapView.jsx:4223–4224`); U2 focuses the heading.

U2 uses `'peek:ask'` rather than a new `'peek:askUpsell'` value. Any `peek:*` value other than
`'peek:ask'` becomes a `sheetSection` (`MapView.jsx:5969–5972`) that `MapPeekSheet` does not know, and the
closer at `:2372–2377` would not clear it.

1. **The entry.** Same change as the bar:
   - the button holds `k`, `q`, the pill and `go`;
   - no lockwrap;
   - `aria-expanded={locked ? undefined : 'false'}` (`MapPeekAsk.jsx:178`) loses its `locked` term, so it
     mirrors the section as for PRO;
   - `disabled={availability === 'down' || locked}` (`:180`) loses its `locked` term too. `down` never
     reaches a LITE reader: it becomes `locked` (`context/AskContext.jsx:458`);
   - **`aria-label` is undefined while locked** *(rev)*. `:178` sets one when `unseenError` is truthy,
     and an `aria-label` would drop "Pro" from the name;
   - delete `.wf-map-peek-ask-lockwrap`, the pill-sibling rule and the lifted dim
     (`index.css:14769–14773`).
2. **`openAskSection`** (`MapView.jsx:6033`) no longer refuses while `askLocked`. It opens `'peek:ask'`
   exactly as for PRO: the same `foreignModalOver` refusal, the same clearing of the selection. `askMode`
   then reads `'expanded'`.

   The review verified that the rest of the Map is inert for a locked reader with `phase === 'empty'`:
   - `AskCameraController.jsx:141` returns on `answerId == null || !hasPoints`;
   - `askInset` is set only while `askActive` (`MapView.jsx:4422`);
   - `askOffered` already includes `locked` (`WindowFirstMapPane.jsx:319`);
   - `useAskAllowance` calls only `/api/user/settings/ask`.
3. **The conversation is refused by the render itself** *(rev)*. Lifting the opener's refusal leaves
   `MapPeekAsk`'s render as the only thing between a LITE reader and `AskInputRow` (`:215`) and
   `AskConversation` (`:238`). So:
   - the **first** branch of the render is `if (expanded && locked)`, which returns the upsell. It is
     never a ternary nested inside the PRO branch;
   - the upsell is
     `<div className="wf-ask-upsell-inline" data-testid="wf-map-peek-ask-upsell-inline">` with
     `AskUpsellContent` (`headingId="wf-map-peek-ask-upsell-heading"`). Use a **new** test id: today
     `wf-map-peek-ask-locked` names the lockwrap (`:196`, `AskLocked.test.jsx:204–208`). Retire the old
     id, so a stale assertion cannot pass against a different element;
   - add a `Not now` button. Its handler is `park(); onClose();`, which is `closeSection` without
     `ask.clear()`. ⚠️ Calling `onClose` alone loses focus to `<body>` *(rev)*. The return-to-entry path
     runs only when `document.activeElement === wrapRef.current` (`:120`), and the `Not now` button
     unmounts while it holds focus;
   - the row's ✕ is not drawn while locked, because `Not now` is the close (Q7);
   - `useAskReady`'s `enabled: … && availability !== 'locked'` (`AskConversation.jsx:162`) stays as
     defence only.
4. **Focus.**
   - On open, focus goes to the inline heading (`tabIndex={-1}`). The open effect
     (`MapPeekAsk.jsx:107–121`) focuses the input or parks today; while locked it focuses the heading.
   - On close, focus goes back to the entry through `park()` and the `wasExpanded` path (`:120–122`).
   - Escape (verified): the native listener (`:149–161`) parks focus, the pane rung
     (`MapView.jsx:6407–6411`) nulls `openMapMenu`, and the `wasExpanded` path moves focus to the entry.
   - **A map touch closes it** *(rev)*. `SheetDismissOnMapTouch` is armed while
     `askMode === 'expanded' && askPhase !== 'busy'` (`MapView.jsx:6711`). That is accepted, and it is
     tested, with focus not moved.
5. **The height.** `peekTargetHeight('expanded')` is `PEEK_HEIGHT.ask`, 470px, sized for an answer.
   Measure the upsell's content at 320px and 375px, and report it with the hidden peek buttons.
   - If 470px leaves a large empty sheet or clips the content, add a locked height.
   - `askPeekMode` stays unaware of the role. Pass `askLocked` into the height function as a separate
     argument from `MapView`.
6. **The minimised line cannot appear for a locked reader.** There is no answer, and §3.3's `CLEARED`
   effect covers an in-session demotion. Prove it with one test.

## 5. Tests (`docs/engineering/frontend-test-standards.md`)

**The lock suites that go red, and must be rewritten** *(rev)*. These pin the greyed lock, which is now
the wrong behaviour. They are lock suites, not PRO suites, so "the PRO suites stay unedited" still holds.
In each PR, say which assertion was deleted and which was rewritten.

- **U1:**
  - `AskLocked.test.jsx`, the bar and field blocks:
    - disabled;
    - the pill outside `.wf-lens-locked`;
    - the no-double-dim opacity checks;
    - the bar's inner span.

    Update the `RULES` list in `installLockRules` (`:80–99`).
  - `askCss.test.js:263–290`:
    - the forced-colours `.wf-ask-bar > .wf-lens-pro` block;
    - `.wf-ask-bar[data-locked]:disabled`;
    - `ruleBody('.wf-ask-bar-inner')`;
    - `.wf-lens-locked .wf-askf:disabled`.
  - `AppAsk.test.jsx:178–192`.
- **U2:**
  - `AskLocked.test.jsx:204–208`, the peek block;
  - `askCss.test.js:290–295`, the peek lockwrap;
  - `AskPeekStateTable.test.jsx:325–356`. It pins the disabled lockwrapped entry collapsed at 126px, and
    at `:344` "`openAskSection` itself refuses", which U2 removes on purpose.

⚠️ `askCss.test.js`'s `ruleBody` searches only from `index.css:13723` onward. The shared tide rules at
`:4175` are before that, so a new CSS test uses `tideLockedCss.test.js`'s whole-file helper.

Keep these:

- the PRO case `AskLocked.test.jsx:343–353` (PRO has no `.wf-lens-pro` and no "Pro" text) *(rev)*;

- no `/api/ask*` request (axios spy) across every block;
- `/` refused with `defaultPrevented === false` and `document.activeElement` unchanged (memory
  `refusal-default-prevented-observable`);
- no `aria-keyshortcuts` and no `<kbd>`;
- no sheet, no dock and no conversation mounts. Assert against a rendered control (memory
  `lazy-absence-needs-rendered-control`): open the upsell first, then check that no
  `[data-testid="ask-sheet"]` or dock exists after awaiting the lazy boundary.

New, per band (phone bar, tablet field, desktop field, wide field; U2 adds the phone Map):

- the pill is inside the trigger;
- the trigger's accessible name includes "Pro", and it has no `aria-label`;
- `aria-haspopup="dialog"`;
- `aria-expanded` flips, and `aria-controls` names the open popover's id;
- the trigger opens on click, Enter and Space;
- the popover has `role="dialog"`, no `aria-modal`, `aria-labelledby` pointing to the heading, and focus
  on the heading;
- the heading and body copy, read from the exported constants;
- **no `a[href]` and no `/upgrade/i`** anywhere in the popover;
- the preview's markup is identical across two different briefing fixtures, and the component has no
  props (assert `AskUpsellPreview.length === 0` or render it twice);
- each close path, with focus where it should be:
  - `Not now` → trigger;
  - Escape → trigger;
  - the trigger again → trigger;
  - an outside press → focus not moved;
  - Shift+Tab out → focus not moved, popover closed;
- a press on a Plan card while open closes the upsell **and** opens the card popup (one click);
- **Escape on the Map tab closes only the upsell**, and the landing card stays (the `stopPropagation`
  test). Test it twice: with focus on the heading, and **with focus on the trigger** after Shift+Tab
  *(rev)*;
- a blur with a null `relatedTarget` does not close it. This is the Safari click case: a trigger click
  then toggles it shut exactly once *(rev)*;
- with a foreign `role="dialog"` standing:
  - Escape does nothing;
  - a press inside that dialog does not close the upsell;
  - the trigger can still close it *(rev)*;
- a locked trigger disabled by `askBlocked` (settings or a card popup open) dims to PRO's opacity *(rev)*;
- the forced-colours `Highlight` border on an expanded trigger is pinned *(rev)*;
- `selectTab` from Plan, Coming up and Map closes it;
- a resize across 1024px (field → field, dock opener) and across 640px (bar ↔ field) closes it, and focus
  is not stranded on `<body>`;
- a shell dialog opening (settings) closes it;
- **tide exclusion**: on a LITE desktop Map, the tide upsell then the Ask upsell, and the reverse. Never
  both.

The phone Map (U2), in the same file or `AskPeekLocked.test.jsx`:

- the section opens and the inline content (`wf-map-peek-ask-upsell-inline`) is drawn;
- there is no `AskInputRow`, no `[role=textbox]` and no `AskConversation`;
- no axios call matches `/api/ask`;
- the old `wf-map-peek-ask-locked` id is gone;
- the entry's accessible name includes "Pro" even with an `unseenError` set;
- focus goes to the heading;
- `Not now` closes and focus goes back to the entry, not `<body>` (the `park()` test);
- the peek's Escape closes and focus goes back to the entry;
- a map touch closes it, and focus is not moved;
- no camera move (spy `flyTo`/`fitBounds` on the Ask camera path) and no minimised line;
- `AskContext`'s `CLEARED` on `isPro` true → false (§3.3).

Also:

- **CSS:** extend `tideLockedCss.test.js` or add `askUpsellCss.test.js`:
  - the shared selector lists contain both class families;
  - `.wf-tide-upsell-anchor` does **not** name an Ask class;
  - the Ask anchors' z-index stays below 50.
- **Harnesses:** both Ask harnesses default to PRO (F1 did that), so the PRO suites stay unedited.
- **`AppAsk.test.jsx`:** the LITE `renderApp` case changes from "a locked entry" to "the entry opens the
  upsell and no request is made".
- **Mutation proof** (memory `mutation-testing-never-git-checkout`: back up with `cp`). Each of these
  mutations must turn a test red:
  - remove the `stopPropagation` (both the popover's and the trigger's);
  - remove the `selectTab` close;
  - remove the `askKeyRefused` split (fold the lock back into `askBlocked`);
  - remove the entry-kind let-go;
  - remove the null-`relatedTarget` guard;
  - U2: remove the `park()` before `onClose`, and swap the `expanded && locked` branch below the PRO
    branch.

## 6. Gates, review and docs

- The four frontend commands (lint, Vitest, `npm audit --audit-level=high`, build). There is no backend
  change, so there is no Maven gate.
- **UI review cadence** (`dev-and-testing.md`): an adversarial review of each diff before it lands, with
  six read-only lenses:
  - focus and keyboard;
  - accessibility and accname, including forced colours and the pill's contrast inside the trigger;
  - phone vs tablet vs desktop geometry;
  - role leakage (any `/api/ask*` request, any Ask surface mounting for LITE);
  - dialog stacking and Escape order (the shell's, the Map's eight rules, the tide popover);
  - PRO regression.
- **Browser check** on the local backend (8083) as `admin` (the PRO path is unchanged) and as a LITE
  account, at 375, 640, 1024 and 1280px. The memory note `cannot-sign-in-for-browser-checks` applies, so
  say plainly what was seen and what was only tested.
- **Docs in the same PR:**
  - `reference/roles-and-gating.md:58`: replace "LOCKED with the drive filter's treatment … no upsell
    words, no link; nothing opens" with the upsell. Keep "no link", "no `/api/ask` request" and "the
    backend refusal is in force". Update the house-pattern sentence at `:59` ("moving Ask's lock to an
    upsell is a separate follow-up plan") to say that it is done.
  - `reference/roles-and-gating.md:52–53`, the general UI-pattern bullets *(rev)*: a Pro feature's
    locked surface opens a worded upsell, and the greyed lock (.45, `pointer-events: none`, `disabled`)
    is only for controls too small to carry one.
  - `CLAUDE.md:207` *(rev)*: "PRO features are shown greyed to LITE with a 'Pro' text pill" becomes "PRO
    features show LITE a 'Pro' pill and open a worded upsell (greyed only where too small to carry one;
    never a link; `/upgrade` is not a route)". Keep it to one line.
  - `reference/ask-photocast.md` *Ask — the client*: `locked` now opens the upsell; the `askBlocked` /
    `askKeyRefused` split.
  - `reference/map-tab.md`: the peek's locked Ask section (U2).
- **Changelog:**
  - `changelog.d/2026MMDD-ask-upsell.md`, `### Changed — Lite readers see what Ask PhotoCast does`;
  - U2: `changelog.d/2026MMDD-ask-upsell-phone-map.md`.

## 7. Order and the Sonnet briefs

- **U1:** `AskUpsell.jsx`, the CSS selector lists, the shell (§3.2), `AskField`, `AskBar`, their
  popovers, tests, docs.
- **U2:** `MapPeekAsk`, `MapView`'s `openAskSection`, the peek height, tests, `map-tab.md`. It branches
  from `main` after U1 has merged, because it imports `AskUpsellContent`.

Release safety:

- U1 alone ships a LITE phone Map that still shows the greyed peek entry while the other bands show the
  upsell. That mix is honest (both say Pro, neither leaks) and is acceptable for one release.
- There is no backend phase, so there is no PWA residual of the kind `ask-pro-gating-plan.md` §6 had. An
  old bundle shows the greyed lock, and a new one shows the upsell. Both make no request.

Each phase gets its own Agent call with `model: "sonnet"` and `isolation: "worktree"`, branched from the
updated `main`. Each brief carries:

- this plan's sections for the phase, verbatim;
- the files named in them;
- the tests in §5;
- the four frontend gates;
- the changelog file.

The planner reviews the diff read-only and sends fixes back to the same agent. Then CLAUDE.md steps 3–6
follow: a ready PR, then CI and Codex 👍 on the exact head commit, then a squash merge.

## 8. Owner questions (defaults are what gets built if unanswered)

| # | Question | Default |
|---|---|---|
| Q1 | Is the LITE trigger drawn at full strength with the pill inside (the tide teaser's look), or still greyed at .45 but pressable? | **Full strength, pill inside.** The tide teaser is not greyed, and a grey control reads as "you can't press this", which is the opposite of a door. |
| Q2 | The phone bar on Plan and Coming up opens a non-modal popover above the bar. The alternative is to open `AskSheet` (a modal bottom sheet) with the upsell content. | **The popover.** It matches the house pattern's "non-modal" and adds nothing to the two-dialog stack. The sheet would be modal, with a scrim and `inert`, which is heavy for three sentences. |
| Q3 | `/` on desktop for LITE: refused as today (the key reaches the browser), or opens the upsell? | **Refused**, with no `aria-keyshortcuts`. A global key that opens a sales popover is louder than the house pattern intends, and it would swallow Firefox's quick-find. |
| Q4 | The copy (placeholder): heading `Ask about the forecasts in plain English`; body `Ask where and when to go for the light, and get an answer drawn from the forecasts, with the spots shown on the map.` | The placeholders, until the owner signs them off. |
| Q5 | The preview: an abstract sketch (a question line, two answer bars, two numbered dots, no words), or a worded example question and answer? | **The abstract sketch.** A worded example naming a real place would read as a live answer on a forecast app. |
| Q6 | The bar's and the peek entry's `↑` "send" arrow while locked. | **Keep it** (the same bar). Hide it if the owner finds it misleading. |
| Q7 | The phone Map's locked section: `Not now` only, or the row's ✕ as well? | **`Not now` only**, as in the popover. |
| Q8 | Ship U1 and U2 in the same release, or let U1 go out alone? | Either is safe (§7). U2 follows U1 merged. |
