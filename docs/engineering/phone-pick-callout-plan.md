# Phone Map: a pick opens its callout, and a Layers door on the minimised answer — plan

**Status:** plan, P0. Owner requests, 2026-10-17, from the Caw Gap episode on an iPhone ("I can't see
the map filters"). Built and merged without further interaction unless a product decision blocks it.
Phone only (`hooks/useIsMobile.js`, below 640px); tablet and desktop are unchanged and pinned so.
Reviewed adversarially before merge (three read-only lenses: runtime state, accessibility and layout,
tests and docs); §8 records what the review changed.

## 1. What changes

**P1 — a pick chip opens its callout (the owner's change).** On the phone Map, tapping an Ask pick on
the map (its numbered chip, or its pin in Pins mode) opens that place's normal `MapCallout` for the
pick's own window and leaves the Ask answer MINIMISED on its 112px line. Today it expands the answer
to 470px and opens no callout.

Owner's words: "tapping a pick chip would open its normal callout and leave the chat minimised,
instead of expanding the chat. The one wrinkle is that the design currently forbids the callout and an
expanded sheet showing together. The minimised line is allowed to sit under a callout, so this would
be consistent with that."

**Decided, from those words:** a chip tap minimises the answer from EVERY state, an expanded answer
included, because the callout opens and an expanded sheet and a callout never coexist. A reader who
wants the cards back taps the minimised line, as today.

**P2 — a Layers door on the minimised answer line.** The minimised line gains a second control beside
it, a Layers door, which opens the peek sheet's Layers section (Show, Regions, Filters, Tide mode)
with the answer kept. Today, while an answer is settled, the sheet's Other windows · Tide · Layers
buttons are replaced by the answer, so the filters are reachable only through the window pill.

Unchanged: a pick CARD in the expanded answer (and its "Show on map ›") still chooses the pick, moves
the window and the camera, and opens no callout; an ordinary chip still opens its callout over the
112px line; on tablet and desktop a pick tap still opens no callout
(`docs/engineering/ask-photocast-plan.md` §2.7).

## 2. How it works today (the seams)

- `MapView.handleSelectAskPick(rank)` (the chip and pin handler): calls `onSelectAskPick(rank)` (the Ask
  selection: card, window, camera), then on the phone (`askRowOn`) clears the selection and sets
  `openMapMenu` to `'peek:ask'` unless a drilldown, Regions or Filters is open.
- `AskContext.selectPick(rank)` bumps a private `selectionSeq` and dispatches `PICK_SELECTED`; the
  `askWindow` memo (`WindowFirstMapPane`) carries `{date, eventType, nonce}`.
- The window-follow effect runs once per nonce while the pane is visible (and, for a night, only once
  its row exists), clears `selectedLocationName` first, finds the pick's row and moves the window (or
  keeps a night `App` would refuse local, or stops when the row is missing or not forwardable).
- The selection-collapse effect turns any open `peek:*` value to null when a selection is installed, so
  an expanded answer minimises. "Minimised" is DERIVED (a settled answer, no section), never stored.
- `MapCallout` stands over the 112px line by reading `--psh` (`bandKey={peekResting}`); an open sheet
  (356/408/470px) and a callout never coexist. A callout is not gated on `openMapMenu`, so an ordinary
  chip already opens one over an open drilldown.
- `MapPeekAsk.MinimisedLine` is ONE button that expands the answer (three variants: picks, can't/error,
  plain summary). Opening Layers over a settled answer already works (mode `section`, the buttons back,
  the answer kept); closing it returns to the line.

## 3. Design

### P1 — the pick callout

1. **The intent rides the choice, not a side ref.** `AskContext.selectPick(rank, { callout })` stores
   `callout` in the `PICK_SELECTED` action beside `selectionNonce` (`askConversation.js`), and the
   `askWindow` memo carries it with the nonce and the pick's `locationId`. Because it is atomic with
   its nonce, a choice that is deferred (a night whose row has not arrived, a pane not yet visible)
   opens its callout when it is finally applied, a later CARD choice (`callout` false) can never
   inherit it, and a refusal that restores the same nonce cannot re-fire it (`appliedAskNonce` already
   blocks a second application). A first draft kept a phone-only ref in `MapView`; the review showed the
   effect's early returns would leave it stale and a later card choice would consume it.
2. `handleSelectAskPick` on the phone calls `onSelectAskPick(rank, { callout: true })` and, in the same
   batch, turns any open `peek:*` value to null (it stops writing `'peek:ask'`). Collapsing in the
   handler, not only through the selection-collapse effect, means the camera's bottom inset is already
   the 112px line when `AskCameraController` flies (otherwise it flies for 470px and snaps mid-flight),
   and no commit holds a callout beside an open sheet. It leaves a drilldown, Regions or Filters open,
   as today. It clears the selection only when the tapped place is not the current selection, so a
   re-tap of a `1·2` place does not tear its callout down and rebuild it.
3. The window-follow effect, after its existing clear and once the pick's row is found and followed
   (or kept local, for a night), sets `selectedLocationName` to the place, looked up by
   `askWindow.locationId` in `locations` (the name as fallback), when `askWindow.callout` is true and
   the view is the phone (`askRowOn`, a closure read at run time; a comment says so). The callout so
   describes the pick's OWN window. When the row is missing or not forwardable, no callout opens and the
   answer stays minimised: the tap chose the card and the camera flew.
4. A callout opens over an open drilldown exactly as an ordinary chip's does today.
5. A two-pick place (`1·2`) steps its rank per tap; each tap follows that rank's window and keeps the
   callout on the place.
6. A tap in the "Plan this" view leaves the plan for the answer (`PICK_SELECTED` clears `planPick`, as
   today) and the answer minimises under the callout: the plan view is discarded, not hidden.
7. Focus stays on the pressed chip, as an ordinary chip's does. The callout opens silently to assistive
   technology, as an ordinary chip's does (§6).

### P2 — the Layers door

1. **Markup.** `MinimisedLine` renders a row wrapper (`.wf-map-peek-mini-row`,
   `data-testid="wf-map-peek-mini-row"`): the existing expand button, then a SIBLING door button
   (`.wf-map-peek-mini-lay`, `data-testid="wf-map-peek-mini-lay"`), in all three variants. A sibling,
   never a child, so it never fires the expand and the HTML stays valid. `MapPeekAsk` gains an
   `onOpenLayers` prop (PropTypes on `MapPeekAsk` and `MinimisedLine`); `MapView` passes the Layers
   button's own handler (`() => handlePeekPress('lay')`), which clears a standing callout first (an open
   sheet and a callout never coexist) and opens Layers with the answer kept.
2. **Look.** The door is the Layers button in miniature, so the reader recognises it: a 58px-wide,
   44px-tall slot with the caption `LAYERS` (the `.wf-map-peek-k` style) over the `☰` glyph, with the
   1px border and radius of `.wf-map-peek-ask-x`. The glyph is `aria-hidden`; the accessible name is
   "Layers", the same as the sheet's Layers button (the two never coexist, and a voice-control reader
   says one word for both). `aria-expanded="false"` and `aria-controls="wf-map-peek-body"`, at parity
   with the three peek buttons when their section is closed.
3. **Layout (CSS, in `index.css` with the rest of the peek sheet).** The row wrapper owns the line's
   10px side margin and `min-height: 44px`; the expand button drops its own margin and takes
   `flex: 1; min-width: 0`, so a long name ellipsises instead of pushing the door out; the door is
   `flex: none`, `box-sizing: border-box`. Below 400px wide the `when` text (`Sat PM 19:42`) is hidden:
   the window pill above already says it, and the name needs the room. Arithmetic at 320px (to be
   measured, §5): 300px row − 58px door − 8px gap − 8px padding leaves about 226px; rank 20 + "3 picks ▴"
   about 59 + two 8px gaps leaves about 130px for the name, and about 117px for "Plan this ▴".
4. **Height.** The line stays 44px and the sheet stays 112px (14 handle + 52 ask row + 44 line + 1
   border = 111); `askPeekCss.test.js`, which today reads only `.wf-map-peek-mini`, is extended to the
   row wrapper and the door.
5. **Focus.** The door unmounts under its own press (the minimised line becomes the section view), so
   without a hand-off focus falls to `<body>` and the pane's Escape ladder goes quiet, the defect this
   repo has fixed five times. So:
   - after the door press, focus moves to the Layers peek button (`layersPeekBtnRef`, mounted in the same
     commit), in a layout effect guarded by "focus has actually been lost", as `MapPeekAsk` does for its
     other unmounting controls; the Layers button reads `aria-expanded="true"`, the right cue;
   - the door gets a ref plumbed like `entryRef`; when Layers, Regions or Filters close back to the
     minimised line, focus returns to the door: `MapPeekSheet`'s rescue target becomes
     `askMode === 'minimised' ? doorRef : askEntryRef`, and both `restoreFallback`s become
     `layersPeekBtnRef.current ?? doorRef.current ?? askEntryRef.current`;
   - if a typed question goes busy while the door holds focus (the line unmounts), focus goes to the Ask
     entry, as for the line today.
6. **Focus ring.** The door is the last thing in a sheet that ends at 111 of 112px, so it takes the
   line's inset ring (`outline: 2px solid var(--color-plex-gold); outline-offset: -2px` on
   `:focus-visible`), an `outline` so forced-colours keeps it.
7. **Copy.** CLAUDE.md's "with one minimised Tide and Layers are a step further (through the pill's
   Windows section)" becomes: Layers has its own door on the minimised line; Tide is still through the
   pill's Windows section.

## 4. Phases

| phase | scope |
|---|---|
| P0 | this plan |
| P1 | the pick callout; tests; docs; changelog `### Changed —` |
| P2 | the Layers door; tests; the 320px measurement; docs; changelog `### Changed —` |

Each phase: build; the four frontend checks (`npm run lint && npm test && npm audit --audit-level=high
&& npm run build`); a headless-Chromium check at 390px and 320px on a throwaway Vite page that mounts
the real `index.css`, `MapPeekSheet` and `MapPeekAsk` with the conversation stubbed (the method F4
used, `ask-photocast-plan.md`; uncommitted, deleted after), stating which claims were seen and which
were tested; an adversarial review of the diff by read-only agents (anything that mutates gets its own
worktree); fixes; PR; CI and Codex green; merge.

## 5. Tests

`AskPeekStateTable` passes a no-op provider and no `askWindow`, so its pick rows cannot be flipped in
place: they are REWRITTEN with a harness step that plays the provider's part (a rerender with
`askWindow={{ date, eventType, nonce: n + 1, callout: true, locationId }}`).

**P1 — rewritten (phone):** from minimised, from expanded, from under another place's callout, and for a
pick on another window than the pill's: the answer is minimised, the card is selected, and after the
follow the callout is the pick's place for the pick's window. `AskPeekChain` "a pick chip pressed while
minimised EXPANDS…" and "a chip pressed on the map leaves the plan for the answer" → minimised, the
callout open, the plan left.

**P1 — mutant-killing (new):**
- (a) the handler still writing `'peek:ask'`: with the no-op provider, a chip press leaves the mode
  `minimised` and calls `onSelectAskPick` once with the rank and `{ callout: true }`; and the
  missing-row case (`2026-01-16`) also asserts `minimised`.
- (b) the callout opened before the window follows: TODAY's SUNRISE pick against a SUNSET pill; every
  `MapCallout` render for the place carries `SUNRISE`, the last one included.
- (c) a stale intent: a chip on an unfollowable row, then a card choice (`callout: false`, new nonce) →
  no callout; a chip → callout → Escape → a card choice → no callout; a chip whose choice never yields an
  `askWindow` → a later card choice opens no callout.
- (d) desktop or tablet: a StateTable row with `mockIsMobile = false` and a provider-simulated nonce with
  `callout: true` → no callout (beside `AskMapLinkage` "a pick CHIP chooses its card", which stays).
- a chip press with Windows, Tide or Layers open → the section closes, the callout opens.
- a drilldown open → it stays, and the callout opens over it (ordinary-chip parity).
- the camera's bottom inset when it flies is 112, not 470.
- a `1·2` re-tap keeps one callout mounted (no unmount between taps).
- Escape clears the callout and leaves the line (already pinned; stays).

**P1 — re-titled, not deleted:** `MapPeekAsk.test` "does not take focus from a control the reader
already holds (a pick chip that opened it)" (no chip route expands any more; the test of the expand
effect stays). `MapViewAsk.test`'s comment "the phone wraps it to expand a minimised answer" is updated.

**P1 — unchanged, must stay green:** the desktop/tablet rows of `AskPeekStateTable`, `AskMapLinkage`,
`MapViewAsk`, `MapLabelsAsk`, `PinsLayerAsk`, `askPickChip`, the ordinary-chip rows, `AskPeekChain`'s
"a callout may stand over the minimised line".

**P2:**
- (e) the door is not inside the expand button: clicking it calls `onOpenLayers` once and `onOpen`
  never; `door.closest('button') === door` and the line does not contain it; in all three variants.
- (f) the door clears a standing callout: ordinary chip → callout → door → no callout and mode
  `section`.
- focus: after the door press `document.activeElement` is the Layers button, and Escape fired AT
  `document.activeElement` closes Layers; after Layers, Regions and Filters close, focus is on the door;
  a busy question while the door holds focus lands on the Ask entry.
- `AskPeekStateTable` "Regions and Filters return focus to … the Ask row" is updated to the door.
- `askPeekCss.test.js`: the row wrapper's and door's 44px, the 112px budget, the door's inset ring, the
  `when` hidden below 400px.
- the 320px measurement (§4): door right edge inside the sheet, name width above zero, no horizontal
  overflow, for the longest `when` ("Mon night 20:40"), "Plan this", and a long error text.

## 6. Accepted limits

- A pick whose row cannot be followed opens no callout; the tap still chooses the card and the camera
  flies. Picks come from the rendered windows, so this is rare.
- The callout is silent to assistive technology and is portalled outside the chip's tab order, exactly
  as an ordinary chip's is; a keyboard reader reaches its buttons by tabbing on.
- The door clears a standing callout (an open sheet and a callout never coexist), and opening and
  closing Layers moves the camera's inset twice.
- On a tap from an expanded answer the sheet animates down (.28s) while the callout is already placed
  for 112px, so the sheet can briefly paint over the callout's lower edge.
- A running phone page keeps the JS it loaded until reload (CLAUDE.md's standing note).

## 7. Docs that change

- `docs/engineering/ask-photocast-plan.md`: §2.7's table row "Pick chip press" (phone: minimise, callout
  opens, card selected); the §2.7 sentence "Selecting a pick … does not open a callout" (except a phone
  chip, where the follow effect opens the pick's own callout); the F4 as-built and "For F4" notes,
  marked superseded by this plan with the date.
- `docs/engineering/map-mobile-sheet-plan.md` D-7: the pick route joins the callout-over-the-line state;
  line 225's "every route that opens the callout is a map tap" gains the follow-effect route.
- CLAUDE.md: the phone peek-sheet bullet (the door; a phone pick opens its callout over the line) and the
  Ask client bullet ("picks are numbered chips and pins … a press steps through them", plus "and opens
  its callout, on a phone").
- Code comments: `handleSelectAskPick`'s JSDoc (rewritten), the follow effect's JSDoc, `MapPeekAsk.jsx`'s
  header (a fourth unmounting control; no chip route to expand), `MinimisedLine`'s JSDoc, `MapLabels.jsx`
  (a pointer).
- The vendored design READMEs (`docs/design/ask-photocast/`, `docs/design/map-mobile-sheet/`) are not
  edited; the door and the pick callout are recorded here as deliberate divergences from them.

## 8. What the review changed

Adopted: carrying the callout intent on the choice (`selectPick(rank, { callout })`) instead of a
side ref that the effect's early returns would leave stale; collapsing the sheet in the handler so the
camera flies for the 112px inset; looking the place up by `locationId`; not clearing a `1·2` re-tap's
callout; ordinary-chip parity over an open drilldown (the draft invented a "drilldown rule"); the
expanded-answer case stated as decided; the door's focus hand-off to the Layers button and back, its
ref, its inset ring and forced-colours outline; a LAYERS-captioned, bordered door instead of a bare
glyph; the full layout CSS and hiding `when` below 400px; rewriting (not flipping) the StateTable rows
with a provider step; the mutant-killing tests; the docs list; changelog entries; and a throwaway Vite
page for the width check instead of the label-obstacle harness. Considered and not adopted: a polite
live-region announcement for a pick callout (an ordinary chip's callout has none; recorded in §6).

## 9. Owner's check on a real phone (no session can sign in)

1. Ask a question with picks on the Map; tap a numbered chip from the minimised line and from the
   expanded answer: the place's callout opens, the answer sits minimised below it, the map shows the
   pick's window.
2. Tap LAYERS on the minimised line: Layers opens with the answer kept; close it: back to the line.
3. On an iPad or desktop, tap a pick chip: no callout, as before.
