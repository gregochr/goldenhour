# Back closes the top dialog

Status: planned 2026-11-07. Owner request, raised while planning Phase 3a.1 of `engineering-gaps-plan.md`.
Research was done at `origin/main` 404d8392. **Not yet reviewed.**

## The problem

Dialogs push no browser history, so the browser or phone **Back button leaves the app**, even with a dialog open.
On Android, hardware Back closes the installed PWA mid-task. The owner wants Back to close the top dialog
instead.

## Owner decisions (2026-11-07)

- **The rule:** Back closes exactly the layers that **Escape closes today**. Menus and first-run prompts are
  excluded.
- **Map overlays are out:** the location callout, the expanded phone peek sheet, and the window and region
  drilldown panels. Back never changes map selection. The location sheet opened *from* the callout is in.
- **Some modals block Back:** confirm dialogs, the Lite-limit dialog, and the aurora forecast and simulate
  modals have no Escape. Back is **absorbed** on them: they stay open, and nothing underneath closes.
- **First-run prompts are guarded:** the postcode prompt and the sub-region sign-in prompt, once the reader
  has interacted. Back does nothing while they are open. Before any user activation, Chromium ignores the guard,
  so Back still leaves, as today. That is a browser limit and is accepted.
- **The Ask sheet is in.** Its conversation is kept.
- **Forward after Back does nothing.** Stale entries are bounced.

## Inventory

**Included: Back closes them.** Escape closes all of these today.

| Overlay | `data-testid` | Where |
|---|---|---|
| Window popup | `window-sheet` | `WindowFirstShell.jsx:3222` |
| All spots | `window-spot-sheet` | `:3313` |
| Location sheet | `location-sheet` | `:3447` |
| Best bet / Also good | `window-pick-dialog` | `:3505` |
| Plan search | `plan-search` | `:3332`; follows its `stacked` Escape rule |
| Settings, and the "drive times updated" state | `settings-modal` | `App.jsx:1127`; `UserSettingsModal.jsx:674` |
| Guide | `guide-title` | dialog `GuidePanel.jsx:235`; sheet `:322` |
| Ask sheet (below 1024 px) | `bottom-sheet` | `AskSheet.jsx:65` |
| Sub-region picker sheet and the "Save and lock" confirm | `sub-regions-confirm-modal` | `SubRegionSettingsSection.jsx:356, 415` |

**Blocking: Back is absorbed.** Back does not close them and does not reach the layer beneath.
- `temp-password-modal`: Back would lose a generated password.
- The "calculating drive times" spinner.
- `confirm-dialog` (`ConfirmDialog` / `useConfirmDialog`).
- `lite-limit-dialog`.
- `aurora-forecast-modal`, `aurora-simulate-modal`.
- `postcode-prompt`, `sub-region-prompt`, the activation-gated guard.
- The loading stand-ins `settings-fallback` and the `ChunkErrorBoundary` overlay.

**Excluded: no entry, no change from today.**
- Menus and popovers: `wf-win-menu`, `masthead-menu`, `ProUpsellDoor`, `wf-ask-upsell`, `TideUpsell`,
  `wf-filters-panel`, `wf-jump-menu`, `MapLegendPanel`, the health popover, `DayCalendar`.
- The docked Ask panel and the docked guide.
- The Map callout, `wf-map-peek`, `wf-win-panel`, `wf-reg-panel`.
- **Operations panels:** `#manage/...` and `useHashSelection` already push hash entries, so Back already steps
  out of them. They must **never** be registered, or Back would answer twice.
- The change-password gate is a full page, so nothing is needed.

## Design

1. **`utils/dialogHistory.js`:** a module singleton, not a React context, because Settings and the prompts
   render in `App`, a sibling of the shell. It holds:
   - an ordered stack of `{id, onBack, blocking}`;
   - a per-page-load `sessionId`;
   - a monotonic sequence number.
2. **Each layer's entry:** `history.pushState({...history.state, phLayer: {session, seq, depth}}, '', location.href)`.
   - **The URL never changes, and the hash is never touched.** Five hash parsers read it, and `pushState`
     fires no `hashchange`.
   - Every existing `replaceState` (`schedulerQuery`, `runsQuery`, `useHashSelection`, `ManageView`) copies
     `history.state`, so the marker survives them.
3. **The API:**
   - an opt-in **`closeOnBack`** prop on `Modal` and `BottomSheet`, mirroring `closeOnEscape`. When it is absent,
     behaviour is byte-identical;
   - `useBackLayer({open, onBack, blocking})` for the Guide's own dialog markup and the prompts.
4. **Registration follows mount/unmount (or `open`), never `onClose`.** That covers a data-driven close
   (`sheetCard` → null), `selectTab` closing two layers at once, Settings opening, and the `RewindGate`
   remount.
5. **Reconcile after each commit, in a microtask:**
   - the stack shrank: `history.go(-diff)`, so a two-layer `selectTab` makes one traversal;
   - it grew: push;
   - the same depth (an A→B swap through `openOverPopup`): nothing.
6. **The safety guard (mandatory):** traverse **only** when `history.state.phLayer` carries this session's
   marker at the expected depth. Otherwise strip it with `replaceState` and **do not navigate**. Without this,
   closing a dialog with × after a hash push would send the reader out of an Operations panel, or out of the
   app.
7. **On `popstate`:**
   - read the arrived depth, which is 0 for a foreign or stale entry;
   - close layers from the top until the stack length is at most that depth, calling each layer's `onBack`
     (its `onClose`).

   This is idempotent, so an Escape and a Back racing cannot double-close.
8. **Our own traversals:** the pops that our own `go(-n)` causes are recognised by an in-flight flag, cleared on
   the next `popstate` with a timeout fallback. New pushes queue while it is set.
9. **A blocking layer on top:** on `popstate`, push again and close nothing.
10. **First-run prompts:** push their guard only once `navigator.userActivation?.hasBeenActive` is true.
    Otherwise defer until the first `pointerdown` or `keydown` inside the prompt. No `beforeunload`.
11. **Stale sentinels** (after a reload, or on Forward):
    - on boot, strip a foreign or stale `phLayer` with `replaceState`;
    - on arrival, skip it with `history.back()`.

    The worst case is one dead press.
12. **What doesn't change:**
    - no Navigation API and no `CloseWatcher` in v1. Safari lacks `CloseWatcher`, and older iOS versions lack
      the Navigation API;
    - no global `scrollRestoration` change. Measure it on Plan with a sheet open over a scrolled page, and
      if it jumps, set `'manual'` only around our own traversals;
    - the two-dialog invariant stays. The store warns in development if depth exceeds 2;
    - the Escape predicates (`escapeEnabled`, `stacked`) stay the Escape authority, untouched.
13. **Desktop and tablet change deliberately.** This is **not** phone-only work, so `useIsMobile` is not
    involved. Desktop Back used to leave the page and now closes the dialog.

## PR split

1. **PR 1: the store, the hook, and the `closeOnBack` prop on `Modal` and `BottomSheet`.** No consumers yet.
   - **Exhaustive Vitest** (jsdom has `pushState`, `popstate` and an async `back()`):
     - push and close depth;
     - a coalesced `go(-n)`;
     - a same-depth swap;
     - a blocking re-push;
     - the stale-session skip;
     - **no traversal when the top entry isn't ours**;
     - an Escape + Back race;
     - the Forward bounce;
     - an absent prop is byte-identical to today (the existing pinning tests).
   - Reset with `replaceState(null)` and a fresh session per test, awaiting `popstate`, never timers.
2. **PR 2: wire the Included and Blocking sets, add `smoke/back.spec.js`, update the reference docs.**
   - **Shell Vitest:**
     - `selectTab` with two layers makes one traversal;
     - `RunsView`'s `popstate` listener tolerates same-hash pops.
   - **Playwright** (`smoke/back.spec.js`, on the 3a.1 seeded stack):
     1. **Plan:** card → `window-sheet` → `window-spot` → `location-sheet`. The first `goBack` closes only the
        sheet; the second closes the popup. The URL is unchanged, the Plan tab is still selected, and we are
        still on the origin.
     2. Escape closes the top layer, then `goBack` closes the next. No dead press, no double close.
     3. Close with ×: there's no marker left, and `goBack` reopens nothing and stays in the app.
     4. Two layers, then press the Map tab: the markers are consumed, and `goBack` reopens nothing.
     5. Reload with a dialog open: nothing reopens, there's no `pageerror`, and the next `goBack` is harmless.
     6. Settings: `goBack` closes it, and focus returns to the cog.
     7. **Phone project:** the Ask sheet and the guide sheet.
     8. **Operations:** `#manage/scheduler/<job>` Back is unchanged, and a `confirm-dialog` over a panel
        survives `goBack`.
     9. `goForward` after a Back reopens nothing.
   - **Docs:** `plan-tab.md` and `map-tab.md` (the exclusions), `dev-and-testing.md`, and a new line in
     CLAUDE.md's Dialogs invariant: "Back closes the top dialog only where Escape does; see
     `back-closes-dialogs-plan.md`".
3. **PR 3: the first-run activation-gated guard.** Includes the manual Android Chrome measurement of
   skippable-entry behaviour.

## Verification

- **Review:** the full UI review cadence (about six lenses plus refuters) on each PR, since this touches
  `Modal`, `BottomSheet` and the shell.
- **Browsers:** Chromium and WebKit (Playwright).
- **Devices:** a **manual pass** on:
  - Android standalone PWA hardware Back;
  - iOS Safari's swipe-back in a tab (standalone iOS PWAs have no Back, so the feature is inert there).

## Risks

- **Traversing when the top entry isn't ours:** design item 6 is the guard.
- **Races between `go(-n)` and a new push:** the in-flight flag and the push queue.
- **Chromium skippable entries:** the first-run guard doesn't work before the first tap (accepted), and a
  programmatic open without a gesture can make a later Back skip the base entry (accepted, noted).
- **Scroll restoration jumps:** measure first.
- **One dead press** after a reload or Forward: accepted.
- **Review push-back on `Modal` pinning tests:** expected, because desktop behaviour changes.
