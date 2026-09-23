# Default tab by device — Plan on a phone, Map on a tablet or desktop

**Status:** plan, not started. **Scope:** frontend only. No backend, no migration, no new endpoint.

## 1. The ask

The app opens on the **Plan** tab everywhere today. The owner wants:

| Device | Opening tab | Why |
|---|---|---|
| iPhone (any phone) | **Plan** | The map is information-dense; a phone screen struggles with it. The matrix of six cards reads well at 375px. |
| iPad | **Map** | Enough room for the heat field, callout, chrome and landing card. |
| Desktop | **Map** | Same. |

Everything else about tab behaviour stays as it is: the reader can switch tabs freely, tab
selection is **still not persisted** across visits, and every existing handoff (Plan → Map doors,
the callout's `Open in Plan`, the overlay's "open the full map" hatch) keeps working unchanged.

## 2. How the opening tab is decided today (read before touching anything)

- `frontend/src/components/WindowFirstShell.jsx:132` — `TABS` is `plan, coming-up, map, operations`.
- `WindowFirstShell.jsx:372` — `const [activeTab, setActiveTab] = useState(TABS[0].id);` — i.e. Plan.
- `WindowFirstShell.jsx:396` — `effectiveTab` falls back to `tabs[0].id` (Plan) whenever the
  selected tab's pane is absent.
- `WindowFirstShell.jsx:407` — `openedTabs` is seeded with `TABS[0].id` only. Slot panes (Map,
  Operations) mount **only** once their id is in this set (`:1856`). Plan and Coming up are always
  rendered, just `hidden`.
- `WindowFirstShell.jsx:203` — class Javadoc section **"Tab selection is deliberately not
  persisted"**, which argues Plan-on-every-visit. It must be rewritten, not contradicted silently.
- `App.jsx:123` — `activePlanTab` defaults to `'plan'` and is corrected by the shell's
  `onTabChange` mount effect (`WindowFirstShell.jsx:405`). `App` uses it to recast the page as a
  flex column on the Map tab.
- ⚠️ **`App.jsx:688` — `mapPane` is withheld until `allDates.length > 0`**, i.e. until
  `GET /api/forecast` has returned rows. The shell renders **before** that. So on a desktop the Map
  pane does not exist on the first render, and `effectiveTab` necessarily falls back to Plan. This
  is the one genuinely tricky part of this change — see §4.3.
- `hooks/useIsMobile.js` — the app's single phone boundary, `(max-width: 639px)`, mirrored in CSS
  (`index.css` has ~25 `@media (max-width: 639px)` blocks). `hooks/useIsCoarsePointer.js` —
  `(pointer: coarse)`.
- `test/setup.js:44` — jsdom's `matchMedia` stub returns `matches: false` for everything, i.e.
  **every test is on the desktop branch by default**. That matters for §5.

## 3. Decisions (taken here — do not re-litigate in the coding session)

1. **Detect by viewport, not by user agent.** iPadOS Safari reports itself as desktop macOS by
   default, so UA sniffing cannot separate an iPad from a Mac *or* an iPhone from an iPad reliably.
   The question the owner is actually asking is "is the screen big enough for the map", which is a
   viewport question.
2. **"Phone" = the app's existing phone boundary, plus a landscape-phone arm:**

   ```
   (max-width: 639px), (pointer: coarse) and (max-height: 499px)
   ```

   - The first arm is `useIsMobile`'s own query, so the default agrees with the layout the reader
     will actually get (the map's phone layout, bottom sheets, etc. all key on 639px).
   - The second arm catches an **iPhone held landscape** (widths up to ~932px, heights ≤ ~440px),
     which the width test alone would send to the map. Every iPad has a height ≥ 744px in either
     orientation, and a desktop has a fine pointer, so neither trips it.
   - An iPad in narrow Split View / Slide Over (< 640px wide) gets Plan. That is correct: it is
     rendering the phone layout.
3. **Decided once, at mount.** Rotating the device or resizing the window after load does **not**
   move the reader to another tab. A tab switching under someone mid-read is worse than a
   sub-optimal default. (This is why it is a one-shot read, not a `useIsMobile`-style listener.)
4. **The device decision lives in `App`, not in the shell.** The shell gains an `initialTab` prop
   and stays device-agnostic; `App` resolves the device and passes `'map'` or `'plan'`. Reasons:
   the shell's ~9 test suites keep their current Plan-first behaviour with no prop, and `App` is
   already the owner of which panes exist.
5. **Still not persisted.** No `localStorage`. The default is recomputed on every visit.
6. **Tab-bar order does not change.** Plan stays first. Only which tab is *selected* on open changes.

## 4. Design

### 4.1 A pure util: `frontend/src/utils/initialTab.js`

```js
/** The app's phone boundary plus a landscape-phone arm — see default-tab-by-device-plan.md §3.2. */
export const PHONE_OPENING_QUERY = '(max-width: 639px), (pointer: coarse) and (max-height: 499px)';

/**
 * The tab the app opens on for this device: Plan on a phone, Map on anything larger.
 * Read once at mount — never re-evaluated on resize or rotation (plan §3.3).
 * Fail-safe: with no matchMedia (SSR, very old engines) answers 'plan', the long-standing default.
 */
export function resolveInitialTab(win = typeof window === 'undefined' ? undefined : window) {
  if (!win?.matchMedia) return 'plan';
  return win.matchMedia(PHONE_OPENING_QUERY).matches ? 'plan' : 'map';
}
```

Taking `win` as a parameter keeps it testable without touching the global stub.

### 4.2 `App.jsx` — resolve once, pass down

- `const [initialTab] = useState(() => resolveInitialTab());` — `useState` initialiser, so it is
  read exactly once per mount (decision §3.3).
- Pass `initialTab={initialTab}` to `<WindowFirstShell>`.
- Leave `activePlanTab`'s `'plan'` default alone — on the first render the shell *is* on Plan
  (§4.3 explains why), and `onTabChange` corrects `App` when that changes. Update its Javadoc
  (`App.jsx:106–121`) to say the shell may now move to Map once the map pane arrives.

### 4.3 `WindowFirstShell.jsx` — "unchosen" state, and the late-arriving map pane

The shell must handle the map pane appearing *after* first render (§2, `App.jsx:688`). Model the
opening tab as a **preference that stands until the reader makes a choice**:

```js
// null = the reader has not chosen a tab yet this visit; the opening preference applies.
const [activeTab, setActiveTab] = useState(null);
const preferred = initialTab ?? TABS[0].id;
const requested = activeTab ?? preferred;
const effectiveTab = tabs.some((t) => t.id === requested) ? requested : tabs[0].id;
```

On a desktop this gives: first render Plan (map pane absent → fallback), then Map the moment
forecasts land and `mapPane` is handed over. That is the intended behaviour — **but only while the
reader has done nothing**. Two rules make it safe:

1. **Any tab selection commits.** `selectTab` already calls `setActiveTab(id)`, so once the reader
   clicks, arrows or is handed off anywhere, `activeTab` is non-null and the preference is dead.
   Nothing to add.
2. **Opening any Plan dialog commits Plan.** If the reader opens a window popup, the search dialog,
   a pick, or a location sheet during the fallback, and *then* the map pane arrives, the shell must
   not jump to Map underneath an open Plan dialog (every dialog in this shell is about the Plan
   tab — see `selectTab`'s own comment). Add one effect:

   ```js
   // While the reader has not chosen a tab, opening a dialog on the tab in force IS a choice:
   // pin it, so a map pane arriving a second later cannot move the tab under an open dialog.
   const anyDialogOpen = openWindowKey != null || sheetSpot != null || searchSeed != null
     || openPick != null || sheetKey != null;
   useEffect(() => {
     if (activeTab == null && anyDialogOpen) setActiveTab(effectiveTab);
   }, [activeTab, anyDialogOpen, effectiveTab]);
   ```

   Place it **below** the declarations of every state it reads (the file already records a
   use-before-declaration lint trap for `selectTab`'s neighbour at `:722`). The lint rule
   `react-hooks/set-state-in-effect` will fire; suppress it with a one-line justification in the
   file's existing style (the tab-request effect at `:740` is the precedent).

   Verify the list of dialog state variables against the file at implementation time — if a newer
   dialog exists that `selectTab` clears, it belongs in `anyDialogOpen` too. **The rule is: the set
   `selectTab` clears and the set that commits must be the same set.** Say so in a comment on both.

3. **`openedTabs` must follow the effective tab.** It is seeded with `TABS[0].id` today, so a Map
   pane selected by preference (not by `selectTab`) would never mount — a blank panel. Change the
   mount test at `:1856` to `openedTabs.has(tab.id) || tab.id === effectiveTab`, and keep the set
   sticky by adding `effectiveTab` to it in an effect (or on the render where it first becomes
   effective) so a pane that has been shown stays mounted after the reader leaves it — the sticky
   rule `WindowFirstShellSticky.test.jsx` pins.

4. **Keyboard.** The roving tabindex already keys on `effectiveTab`, so the selected tab button
   carries `tabIndex={0}` without change. Do **not** move focus on the preference-driven switch —
   only an explicit `tabRequest` moves focus (`:745` records why). Confirm with a test.

5. **The tab-request nonce is unaffected** — it calls `selectTab`, which commits.

### 4.4 Everything that should "just work" — check each, change nothing unless it breaks

- **Map landing card** (`MapLandingCard`, once per forecast run keyed on `briefing.generatedAt`):
  desktop readers now meet it on open, which is what the map-landing plan designed it for
  ("the tab opens on an answer"). Confirm it shows on first desktop load.
- **`/` search is Plan-only** (plan-matrix §3 rule 14). On a desktop that opens on Map, `/` does
  nothing until the reader goes to Plan. That is existing behaviour for the Map tab, not a
  regression — do not "fix" it.
- **Coming up badge**: its last-seen write fires only on the Coming up tab. Unaffected.
- **Map chunk cost**: desktop first paint now waits on the lazy `WindowFirstMapPane`/`MapView`
  chunks + Leaflet. `ViewFallback` already covers the Suspense gap. Note the first-load timing in
  the PR from the browser check; no preloading work in this change unless it is visibly bad.
- **Admin Operations tab**: unchanged; never a default.
- **`App` flex-column recast** (`isMapTabActive`): driven by `onTabChange`, so it follows the
  late switch automatically. Verify there is no page scrollbar after the Plan → Map switch on load.

## 5. Tests

Read `docs/engineering/frontend-test-standards.md` first.

**New: `test/initialTab.test.js`** (pure, fast)
- Phone portrait (query matches) → `'plan'`.
- Desktop / iPad (no match) → `'map'`.
- No `matchMedia` on the window → `'plan'`.
- Asserts the exact query string passed to `matchMedia` — so a later edit to the landscape arm is a
  deliberate test change, not a silent drift. (A fake `win` object records the query.)

**Extend: `test/WindowFirstShellTabs.test.jsx`** (or a new `WindowFirstShellInitialTab.test.jsx`)
- No `initialTab` prop → opens on Plan (pins the old default for every other suite).
- `initialTab="plan"` with a map pane → opens on Plan.
- `initialTab="map"` with a map pane present at mount → opens on Map, Map pane **mounted** (not a
  blank panel — assert on content from the pane, not just `aria-selected`).
- `initialTab="map"`, **no** map pane at mount → Plan; rerender with the map pane → Map. This is the
  late-arrival path; it is the one most likely to be broken.
- `initialTab="map"`, no map pane, reader **opens a window popup**, then rerender with the map pane
  → still Plan, popup still open. (The commit rule.)
- `initialTab="map"`, no map pane, reader clicks Coming up, then the map pane arrives → still
  Coming up.
- After the preference moved to Map, pressing Plan then Map again keeps the Map pane mounted (the
  sticky rule).
- Focus is **not** moved by the preference-driven switch (`document.activeElement` unchanged).
- `onTabChange` is called with `'plan'` then `'map'` on the late-arrival path.
- Mutation-check each new test mentally: would it fail if the commit effect were deleted? If
  `openedTabs` were left as-is? If it would not, it is not testing the rule.

**App-level suites.** Because jsdom's `matchMedia` stub answers "no match" (§2), `App` will now
resolve `'map'` in every App-rendering test. Run the full suite; wherever an App suite breaks
*only* because it assumed Plan-first, `vi.mock('../utils/initialTab.js', …)` in **that suite** to
return `'plan'`, with a one-line comment. Do **not** change the global stub in `test/setup.js` —
that would hide the feature from its own tests. Add one App-level test that the resolved value
reaches the shell (mock the util to `'map'`, assert the Map tab ends up selected once forecasts load).

## 6. Docs to update in the same change

- `WindowFirstShell.jsx` class Javadoc, section **"Tab selection is deliberately not persisted"**
  (`:203`): keep the not-persisted argument, replace "Plan resets on every visit" with the device
  rule, and point at this plan. Also the comment at `:729–732` that restates the rule.
- `App.jsx:106–121` (`activePlanTab` Javadoc) — the default can now move to Map after mount.
- `CLAUDE.md` — one sentence in the **Map tab (v2)** bullet: *"The app opens on Map at ≥640px and
  on Plan on a phone (`utils/initialTab.js`, decided once at mount, never persisted —
  `docs/engineering/default-tab-by-device-plan.md`)."*
- `changelog.d/YYYYMMDD-default-tab-by-device.md` — `### Changed — open on Map on iPad and desktop,
  Plan on phone` (see `changelog.d/README.md`). Never edit `CHANGELOG.md`'s `[Unreleased]`.

## 7. Phasing, verification and review

One PR, two commits, on a `feature/default-tab-by-device` branch. Follow CLAUDE.md's **UI Work —
Review Cadence** for each commit: build → tests → adversarial review of the diff (read-only
reviewers) → fix what survives → re-verify → commit.

- **Commit 1** — `utils/initialTab.js`, the shell's `initialTab` prop + unchosen/commit/mount logic,
  shell tests. No `App` change, so production behaviour is unchanged by this commit alone.
- **Commit 2** — `App` wiring, App-suite adjustments, Javadoc/CLAUDE.md/changelog.

**Local gate before pushing** (CLAUDE.md — `npm test` alone is not the CI job):

```bash
cd frontend && npm run lint && npm test && npm audit --audit-level=high && npm run build
```

**Browser verification** (backend `./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local`
on **8083**, `npm run dev`, sign in `admin` / `golden2026`; fixture recipe in the
`project_local_browser_verification` memory if the DB has no ratings). Reload fresh at each size
with `resize_window`, and screenshot each:

| Viewport | Expect |
|---|---|
| 375×812 (iPhone portrait, `mobile` preset) | Plan selected, stays Plan after forecasts load |
| 812×375 (iPhone landscape) | Plan — only if the emulation reports `pointer: coarse`; the `mobile` preset does emulate touch. If it cannot be emulated, say so in the PR rather than implying it was seen |
| 768×1024 (iPad portrait, `tablet` preset) | Map, landing card visible, no page scrollbar |
| 1280×800 (desktop) | Map, landing card visible |
| 1280×800, open a Plan card popup before forecasts load (throttle the network) | stays on Plan with the popup open |
| any size, rotate/resize after load | tab does not change |

Reset with `resize_window` preset `desktop` when done. In the PR, state which rows were seen in the
browser and which are covered only by tests.

## 8. Out of scope

- Remembering the reader's last tab (explicitly rejected — §3.5 and the shell's Javadoc).
- A user setting for the default tab.
- Reordering the tab bar.
- Preloading the map chunk.
- Any change to the map's phone layout.
