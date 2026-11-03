# Phone Map tab — compact chrome: implementation plan

**Source**: the design bundle vendored at `docs/design/map-mobile-compact/` — `README.md` (the spec),
`CLAUDE_CODE_PROMPT.md` (the designer's order and Verify list), `Map Mobile Compact.html` (the
reference; right phone = proposed), `screenshots/01–06`. Read the spec first; this document is the
*port* plan: where the spec meets code that has moved since, the decisions the spec leaves open, and
how the work splits into two Sonnet-built PRs.

**One sentence.** On a 390 × 844 phone the map gets about a third of the screen; the masthead goes to
one 34px line with a ⋯ menu, the origin row disappears at Home, the tabs shrink, the Ask bar folds into
a fourth 44px peek button, and the attribution goes to one tappable line — giving the map about half the
screen. One change is cross-platform: the unset-postcode nudge turns red everywhere.

---

## §0 Status

| phase | branch | PR | date | notes |
|---|---|---|---|---|
| C0 | `claude/map-screen-improvements-06044b` | — | 2026-11-03 | Vendored the bundle; this plan. No code. |
| C1 | `feature/map-mobile-compact-c1-chrome` | — | 2026-11-03 | Built, committed locally, not pushed: §6 red nudge (all widths), §1 phone masthead + `MastheadMenu`, §2 origin row (`hideAtHome`), §3 tabs. R1–R10 followed; the health panel is shared dot/menu-line state in `HealthStatusContext`. Not browser-verified. |
| C2 | `feature/map-mobile-compact-c2-peek` | — | 2026-11-03 | Built (not yet pushed): Ask is the 3rd of four peek buttons, the entry row and 126/112/408 heights gone, sheet rests at 74; one-line tap-to-expand attribution (`AttributionToggle`). S1–S11 applied; LITE button muted, no pill. |

---

## §1 Where the spec meets the code

All paths under `frontend/src/`. Re-verify line numbers before editing; `MapView.jsx` is ~7,300 lines.

1. **The masthead is the shell's, not the Map tab's.** `WindowFirstShell.jsx` ~2341–2480 renders
   `.wf-mast`: `BrandLockup variant="masthead"`, `healthPill` (admin only), the ⚙ cog
   (`window-first-settings`), Sign out (`window-first-signout`), `MastheadLight` (the gradient rule),
   then `MastheadTickLine` (origin group + ⌂ + sun times). It is rendered on every tab.
2. **The origin row is `MastheadTickLine.jsx`.** Its group is `.wf-tick-group` with
   `data-away='true'` when away, a nudge (`.wf-tick-origin.wf-tick-nudge`) when no home postcode is
   set, the ⌂ `.wf-tick-home`, and the times `.wf-tick-times`. CSS at `index.css` ~845–1030.
3. **The nudge's colour** is `.wf-tick-nudge { color: var(--color-plex-coral); }` (`index.css` ~950):
   label only. §6 moves the colour onto the group.
4. **Tabs** are `.wf-tabs` / `.wf-tab` (`index.css` ~1086–1160, phone block ~1300–1335 already sets
   `padding: 8px 11px; font-size: 12px; min-height: 36px` and a `::before` hit-area extender).
5. **The Ask bar above the peek row is `MapPeekAsk.jsx`'s entry row** (F4,
   `ask-photocast-plan.md` §2.7). Heights live in `utils/askPeek.js` `PEEK_HEIGHT`
   (`plain 74 · collapsed 126 · minimised 112 · open 356 · openWithAsk 408 · ask 470`) **and** in
   `index.css`; `askPeekCss.test.js` asserts the two agree. `--psh` (the resting height) is written
   inline by `MapView` and read by the Leaflet corner padding, `.wf-map-chrome-bl`,
   `.wf-map-empty-low` and `MapCallout`.
6. **The attribution** is styled at `index.css` ~629–670 (`.leaflet-control-attribution`), lifted
   above the sheet by `padding-bottom: var(--psh, 74px)` on the phone.

## §2 Decisions the spec leaves open (made here; the owner can overturn any)

- **D-1 Which tabs get the compact masthead and tabs.** The masthead and tab bar are shell chrome on
  every tab, so §1 and §3 apply **on the phone on every tab** (a header that changes shape when you
  switch tabs is worse than either shape). **§2's Home-hidden origin row applies on the Map tab
  only**: the reasoning in the spec ("the HOME marker and reach rings on the map show the origin") is
  only true there, and on the Plan tab the tick line is search's only way in. On a phone, other tabs
  keep the tick line, minus the sun times (they moved into the masthead).
- **D-2 The ⋯ menu.** A popover, not a dialog: `role="menu"` is not used (rows are plain buttons in a
  labelled group), Escape and an outside press close it, focus returns to the ⋯ button. Rows:
  status line (`● UP · v2.36.0`, the same health data `healthPill` reads; **admin only**, since the
  pill is admin only today — non-admins get no status line and **no status dot** in the masthead),
  **Settings** (the cog's handler, unchanged: `selectTab(effectiveTab); onOpenSettings?.()`),
  **Change home postcode** (the nudge's handler: `selectTab(effectiveTab); (onSetPostcode ?? onOpenSettings)?.()`),
  **Sign out** (`onSignOut`). Rows ≥ 44px. Keep the `window-first-settings` / `window-first-signout`
  test ids on the menu rows so existing route tests keep meaning. The menu closes before its row's
  handler runs, so settings never opens over it. It reads `utils/mapForeignModal.js` like every other
  Map popover only if it is mounted inside `.wf-map-tab` — it is not (it is in the shell), so instead
  it must be closed by `selectTab` (every route out closes it) and must not be open at the same time
  as a dialog: opening any dialog closes it.
- **D-3 Ask section height.** The spec's mock opens Ask at 356 like the others; real answers carry
  pick cards, so the Ask section keeps its **470** (clamped `calc(100% - 64px)` as now).
- **D-4 Minimised answer.** Not in the spec. The minimised line (and its `LAYERS ☰` door) keeps
  replacing the buttons, and with no Ask row above it the sheet rests at **74** with an answer
  minimised too. So `--psh` is 74 in every resting state and the three-valued resting height
  collapses to one. Callout over the minimised line (D-7's one exception) is unchanged.
- **D-5 Expanded Ask.** The four buttons stay at the top with Ask pressed (`aria-expanded="true"`), and
  the Ask body below them holds the field row (field + ✕, unchanged) then the conversation — the
  same order as screenshot 05. While an answer is settled and the section is expanded the buttons
  are shown (the old "an answer replaces the buttons" applies to the minimised line only now).
- **D-6 Ask button.** 44px wide, key `ASK`, glyph ✦ in `#EBD9A8`. Rendered only when Ask is offered
  (`askOffered`, as the row was). LITE (locked): a 44px box cannot carry the "Pro" pill, so the
  button stays live (aria-label "Ask PhotoCast, Pro") and opens the section, which draws the existing
  worded `AskUpsellContent` exactly as the locked entry row does today.
  Opening focuses the field (or the upsell heading when locked), exactly as `openAskSection` does now.
- **D-7 Attribution vs Esri's terms.** Esri requires its attribution be visible; the truncated line
  begins `Leaflet | Tiles © Esri — Esri, HERE, …`, so "Esri" is always visible and the full text is
  one tap away. **Owner to confirm before release** (spec §5 asks the same).
- **D-8 Sun times on the phone masthead** read the same `light` object `MastheadTickLine` uses
  (sunrise/sunset only — the golden times), with `sr-only` "sunrise" / "sunset" words.

## §3 Phases

### C1 review corrections (adversarial plan review, 2026-11-03) — these OVERRIDE C1 and D-2/D-8 below where they differ

- **R1 Health data.** The shell gets `healthPill` as an opaque node (App.jsx ~1026, admin-gated there;
  no `isAdmin` crosses into the shell). App supplies two more admin-gated nodes, `healthDot` and
  `healthStatusLine` (from `HealthStatusContext`; version `h.appVersion`, hidden when `'dev'` as
  `HealthIndicator.jsx` ~240 does). The status line, pressed, opens the existing HealthIndicator
  diagnostic panel so phone admins keep it. No dot while `status` is null.
- **R2 Hide at Home without dropping focus.** Do not stop rendering `MastheadTickLine`. Give it a
  `hideAtHome` prop: it renders nothing in the home-statement state (its own `noHome` rule decides,
  ~line 269; the shell never re-derives it) and its focus hand-off (~235–262) falls back to a
  `fallbackFocus` ref (the ⋯ button) when the origin slot is empty. App's settings
  `restoreFocusFallback` (`originSlotNow`, App.jsx ~1011) must also fall back to the ⋯ button. Test
  ⌂ (Away → Home) and nudge-save (Unset → Home) routes: focus never on `<body>`.
- **R3 Menu must not leak into the Map.** Escape is handled on the menu container with
  `stopPropagation` (and `preventDefault`); an outside dismissal goes through a transparent
  full-viewport catcher under the popover that swallows the dismissing press.
- **R4 Closing.** `MastheadMenu.jsx` is controlled (`open` / `onOpenChange`); its state lives in the
  shell so `selectTab` closes it (`setMastMenuOpen(false)`, legal there: own setters only,
  WindowFirstShell.jsx ~862–869). It also closes on `focusout` leaving its wrapper. Drop "opening
  any dialog closes it".
- **R5 Focus on menu rows.** Before calling a row's handler, focus the ⋯ button synchronously, so the
  dialog that opens records it as its opener; Change home postcode passes `() => menuBtnRef.current`
  as the `returnFocus` argument App forwards.
- **R6** Delete C1 step 5: `--wf-mast-h` no longer exists (`useLensReserve.js` ~46–61); the map
  height chain is pure flex. Just measure before and after.
- **R7 Whose light.** The masthead times are always the HOME light. On the phone, when away, prefix
  them with a small `⌂` (aria-hidden) and keep `light.label` as sr-only text always.
- **R8** Use `BrandLockup variant="compact"` (exists: 20px, no kicker); what goes on the phone is the
  kicker. `MastheadLight`'s 2px/6px is a phone-only override that must not touch its `sm:` classes.
- **R9 Tab hit area.** Body 34px; `::before` at `inset: -6px 0 -4px` so the target is ≥ 40px; ~41px
  on Map, where the bottom 4px lies under the map (mind the bar's `margin-bottom: -4px`); assert in the
  cascade test.
- **R10 Tests.** jsdom's `matchMedia` defaults to desktop (`test/setup.js` ~44–58), so desktop suites
  keep passing; phone tests `vi.mock('../hooks/useIsMobile.js')`. Update `mastheadColours.test.js`
  (and its comment ~15–17) for the nudge colour; add a phone twin of
  `WindowFirstShellMasthead.test.jsx` ~97 for the dot.

### C1 — shell chrome (§6, §1, §2, §3)

1. **§6, all platforms.** `.wf-tick-group` in its nudge state (add `data-unset='true'` on the group
   from `MastheadTickLine` when the nudge is shown) gets border `1px solid rgba(200,69,47,.75)`, bg
   `rgba(200,69,47,.12)`, text and pin `#EE8064`; hover border `rgba(200,69,47,.95)`, text `#F49A84`.
   Delete the coral-only `.wf-tick-nudge` colour rule. Update `mastheadColours.test.js` if it pins
   the old colour.
2. **§1 phone masthead** (`useIsMobile()` in the shell): one 34px row — `BrandLockup` at 20px with no
   tagline (add a `compact` variant or prop; desktop variant untouched), sun times
   `↑07:10  ↓16:29` (mono 10.5px `#E0A542`, arrows 9px at 85%), the 8px status dot (admin only, D-2),
   the ⋯ button (40×40 hit, 30×30 box). Then `MastheadLight` at 2px with `margin-top: 6px`. The cog and
   Sign out are not rendered on the phone. New component `components/MastheadMenu.jsx` for the
   button + popover.
3. **§2 origin row (phone).** On the Map tab at Home: no tick line. Away / Unset: one 36px row (⌂ 32×28
   + the group, single line, the place name ellipsised). On other phone tabs: the tick line as today
   without `.wf-tick-times`. Desktop/tablet: unchanged, times included.
4. **§3 phone tabs.** 13px, `padding: 7px …` (≈34px), bar `padding-top: 6px`; keep the hit-area
   `::before` so the touch target stays ≥ 44px tall; Operations keeps its dashed border.
5. **`useLensReserve` / `--wf-mast-h`** measures `.wf-mast`; confirm it re-measures (it uses a
   ResizeObserver per its comments) and the Map tab's height chain fills the freed space.

Tests: a `MastheadMenu.test.jsx` (opens, closes on Escape/outside/row press, focus returns, each row
calls its handler, the status line admin-only); extend `WindowFirstShellMasthead.test.jsx` (phone vs
desktop render; desktop provably unchanged: cog, Sign out, tagline, tick line with times);
`MastheadTickLine.test.jsx` (data-unset, Home-hidden on Map phone, times withheld on phone). A
CSS-cascade test in the style of `mapPhoneChromeCascade.test.jsx` for the phone tab metrics and the
red nudge.

### C2 review corrections (adversarial plan review, 2026-11-03) — these OVERRIDE C2 and D-3..D-6 where they differ

- **S1 Focus targets by destination.** Close-to-minimised hands parked focus to the minimised line's
  door (`askDoorRef`), never to the Ask button (not mounted then); close-to-collapsed hands it to the
  Ask button. Same split for `restoreFallback` (MapView ~7207) and `MapPeekAsk.jsx` ~132–134.
- **S2 Switching away from an expanded Ask.** The buttons now stay visible while Ask is expanded, so
  Windows/Tide/Layers/Ask presses can unmount the field, ✕ or a pick card while focused. Every peek
  button (Ask included) focuses itself on press (`focus({preventScroll:true})`, as the door does at
  `MapPeekAsk.jsx` ~399, for Safari/iOS), and the Ask body reports unmount-with-focus (layout-effect
  cleanup) so focus goes to the pressed button or the fallback, never `<body>`.
- **S3 Opening focuses the field.** On open with an empty, non-busy phase, focus the field whatever
  holds focus (the Ask button stays mounted, so the old "only when on `<body>`" rule never fires).
  With something settled, keep today's rule (pane node). Locked: the upsell heading.
- **S4 A refusal while closed.** The Ask button carries `data-error="true"` (a small coral dot) and
  the sentence in its `aria-label`; the status region still announces once. Pin it in
  `MapPeekAsk.test` / `AskPeekStateTable`.
- **S5 Who renders the Ask button.** `MapPeekAsk` renders it (it has `useAsk`: availability, locked,
  error) and `MapPeekSheet` takes it through a slot (`askButton` node) placed between Tide and
  Layers. `disabled` when availability is `'down'`, as the entry is today; `askEntryFocusRef`'s
  getter (MapView ~1647) keeps its `.disabled` check, re-pointed at the button.
- **S6** The Ask body gets `id="wf-map-peek-ask-body"`; the Ask button's `aria-controls` names it.
- **S7 Placement.** `MapPeekSheet` renders the Ask body BELOW `PeekButtonRow` (today `{ask}` sits
  above it, ~line 137). The minimised line replaces the button row.
- **S8 LITE.** The 44px button is too small for the "Pro" pill, so per the gating rule it is drawn
  greyed (muted ✦, `data-locked`) yet stays pressable and opens the worded upsell section, as the
  locked entry row does today; aria-label "Ask PhotoCast, Pro".
- **S9 Attribution.** Phone `.wf-map-chrome-bl` becomes `calc(var(--psh, 74px) + 14px + 8px)` to sit
  over the one-line attribution (update `mapPhoneChromeCascade.test.jsx` ~719). Expanded, the
  attribution has an opaque background and a z-index above `.wf-map-chrome-bl`'s and below the
  sheet's 1120. Toggle ignores presses on `<a>`. Expansion is a visual aid for pointers: the full
  text is already in the DOM for assistive tech, so no keyboard control is added (recorded here).
  The listener is attached in an effect keyed on `isMobile` and removed on cleanup, and the class is
  removed when leaving the phone.
- **S10 Docs.** Also fix: `map-tab.md`'s hot-topics "no fourth peek button" sentence and the minimised
  paragraph's "Tide is still a step further"; `index.css` ~5140–5155 and `MapPeekSheet` JSDoc
  citations of 126/112/408.
- **S11 Tests that must be rewritten**: `AskPeekChain.test.jsx`, `AskPeekStateTable.test.jsx`,
  `AskPeekPlanChain.test.jsx`, `AskLocked.test.jsx` (~226/233/277, `wf-map-peek-ask-entry`),
  `askCss.test.js` ~264, `mapPhoneChromeCascade.test.jsx` ~116 (`RESTING_HEIGHTS`) and ~719,
  `askPeek.test.js` ~85, `askPeekCss.test.js` ~107, `MapPeekSheet.test.jsx` ~277–301 (the key text).
  `MapCallout` bandKey and `AskCameraController` tests stay. Step 4's camera clause needs no code.

### C2 — peek row and attribution (§4, §5)

1. **`utils/askPeek.js`**: drop `collapsed 126`, `minimised 112`, `openWithAsk 408`; the resting
   height is 74 in every state; open sections 356; Ask expanded 470. `peekRestingHeight` returns 74
   (keep the function so callers and `--psh` stay one route); `peekTargetHeight` maps
   `expanded → 470`, `section → 356`, else 74. Update `index.css`'s sheet rules and
   `askPeekCss.test.js` together.
2. **`MapPeekSheet.jsx`**: a fourth button `ASK` (44px, `flex: none`) after Tide and before Layers
   (spec order: Other windows · Tide · Ask · Layers), and Layers becomes 44px too. `gap: 5px;
   padding: 0 8px 10px`. Keys 8px mono .08em; values 12px/600. Other windows' verdict and Tide's
   value must not truncate at 390px (verdict `flex: none` already; the day text ellipsises). The
   Tide key text becomes `TIDE` (was `TIDE AT THIS LIGHT`; M3's "never `CLOSE`" rule stands). The
   Ask button has `aria-expanded` and `aria-controls="wf-map-peek-body"` like its siblings, and its
   press is `openAskSection` when closed, close (`setOpenMapMenu(null)`) when open — the toggle the
   other buttons have.
3. **`MapPeekAsk.jsx`**: no entry row. `collapsed`/`section` modes render nothing of their own (the
   status region stays mounted). `expanded` renders, under the buttons, the field row then the
   conversation (D-5). `minimised` renders the existing `MinimisedLine` in place of the four buttons
   (D-4). Every focus hand-off that targeted the entry button (`entryRef`, `askEntryFocusRef`,
   `restoreFallback`'s `?? askEntryRef.current`) now targets the **Ask peek button**; keep the
   "never falls to `<body>`" rule and its tests.
4. **`MapView.jsx`**: `peekResting` is always 74 on the phone; the camera inset reads
   `peekTargetHeight`. `fallbackFocusRef` is the door when minimised, else the Ask button (or the
   Windows button when Ask is not offered). Nothing else about the sheet's sections changes.
5. **§5 attribution (phone)**: single line, 8.5px, `white-space: nowrap; overflow: hidden;
   text-overflow: ellipsis; max-width: 240px`, just above the collapsed sheet; tap toggles a class
   for the full wrapped text (a click handler on the control's container, added once through
   `map.attributionControl.getContainer()`; links inside still work). Its z-index stays below the
   sheet's 1120 so an open sheet covers it. Desktop untouched.
6. **Docs**: update `docs/engineering/reference/map-tab.md`'s peek-sheet bullet (the heights and "an
   answer replaces the buttons" sentences) in the same PR.

Tests: `askPeek.test.js` for the new heights; `MapPeekSheet.test.jsx` (four buttons, Ask toggle,
aria wiring, absent when not offered); `MapPeekAsk.test.jsx` focus rules re-pointed at the Ask
button; `askPeekCss.test.js`; the phone cascade test for the attribution rule and its z-index below
the sheet; desktop-unchanged assertions.

## §4 Verify (both phases, browser at 390 × 844 and 1280 × 900, local backend on :8083)

The README's six checks, plus the map-visible height before and after (measure the map container
minus the collapsed sheet). Say plainly what was seen and what was only tested.
