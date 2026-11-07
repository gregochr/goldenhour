# Back closes the top dialog

Status: planned 2026-11-07. Owner request, raised while planning Phase 3a.1 of `engineering-gaps-plan.md`.
Research was done at `origin/main` 404d8392. Reviewed by five adversarial lenses on 2026-11-07; the **amendments section at the end is binding and wins over the earlier sections where they conflict**.

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

---

## Review amendments (2026-11-07). These are binding.

Five lenses reviewed the plan: history mechanics, inventory and wiring, focus and accessibility, real devices,
and tests and rollout.

### Owner decisions, revised after review
- **The guide stays in, and its links are fixed first.** In embed mode, `landing/guide/guide.js` makes in-guide
  navigation **replace** the current entry: `location.replace(href)` for guide-to-guide links, and
  `history.replaceState` plus `scrollIntoView` for `#section` links. Otherwise every in-frame navigation adds
  an entry to the page's joint history, which the store cannot see. Back would then page through the guide,
  and closing with × would leave a dead press.
  - This ships on its own as a **landing PR, published with `./release-landing.sh` before PR 2b.**
- **Back cancels** confirm dialogs (`confirm-dialog`) and the aurora forecast and simulate modals. Back runs
  their Cancel path. Chromium's history-manipulation intervention makes any "absorb Back" only good for the
  first press, so a second Back with no tap in between can leave the app.
  - **The one exception is `temp-password-modal`**, which stays blocking because cancelling would lose the
    password. Absorbing Back there is **best-effort**: the first Back is absorbed, and a quick second one may
    still leave on Chrome. This is documented, and the owner may later add a `beforeunload` for it.
- **`lite-limit-dialog` is Included, not Blocking.** It does close on Escape (`LiteLimitButton.jsx:39-48`, a
  capture-phase keydown), so under the owner's rule Back closes it.
- **Settings while deleting, and the drive-times spinner,** are the remaining blocking states. They share one
  `Modal` instance with the Settings and result views (see the engine, item 7).

### The engine (replaces design items 5–9 and 11)
1. **Source of truth.** The depth comes from the entry the browser actually lands on, never from a flag or a
   counter:
   - `arrivedDepth = history.state?.phLayer` when `session` matches and `href === location.href`, else 0.
   - The marker carries `{session, seq, depth, href}`. The session is `crypto.randomUUID()` per page load,
     never `sessionStorage`, which a duplicated tab copies.
2. **On `popstate`,** handled by a listener registered at **module evaluation** so it runs before
   `RunsView`'s:
   1. **Restore the URL:** if a hash or query writer rewrote our top entry while a layer was up, call
      `replaceState(history.state, '', recordedTopUrl)` synchronously. Queued `hashchange` and `RunsView`
      listeners then read the right URL.
   2. **Classify the arrival:**
      - **Our own pending traversal:** never close and never bounce; reconcile from `arrivedDepth`.
      - **Same session with depth > stack (Forward):** bounce with `history.go(stack.length - depth)`, close
        nothing, and mark the bounce as a pending traversal.
      - **Foreign session or no marker:** treat as depth 0.
   3. **Close from the top down** to `arrivedDepth`. **Pop the store's entries synchronously before calling
      `onBack`**, so the unmount that follows never triggers a second traversal.
   4. **Blocking layers:** stop at the first blocking layer (read live). Re-push `stack.length − arrivedDepth`
      entries so that every still-open layer has its entry again; best-effort, see above.
3. **Reconcile (on commit)** compares the stack with `committedDepth`, the depth we last placed or arrived at,
   **never with the previous stack length**:
   - **Grow:** push, but only under user activation (`navigator.userActivation.isActive`). Otherwise register
     the layer **entry-less**: Back behaves as today, and it never marks the base entry skippable.
   - **Shrink:** `go(-n)`, but only when the top entry is ours: same session, the expected depth, and
     `href === location.href`. Otherwise strip the marker with `replaceState` and do not navigate.
   - **Pending traversals:** closes and pushes are deferred while a traversal is pending. When the traversal
     lands, or at a timeout, re-read `history.state` and reconcile from it. **The timeout only re-reads; it
     never pushes blind.**
4. **A layer that refused the close re-pushes.** If a layer is still registered on the commit after its
   popstate-driven `onBack` (`closeUnlessDeleting`, the result view's `dismissRefreshResult`), push its marker
   again.
5. **Registration follows intent state, not render.**
   - Lazy dialogs (`LocationFourDaySheet`, `PlanSearch`, `WindowSheetDialog` behind `fallback={null}`)
     register from the owner's open state, e.g. `useBackLayer({open: sheetSpot != null})` in the shell,
     outside `Suspense`. Otherwise a swap to a cold chunk becomes a pop followed by a push with no gesture.
   - When "the card vanished" on a data refresh, clear the key (`openWindow(null)` / `openOverPopup(null)`),
     so a vanished layer is closed, not hidden. That way a refresh can never reopen a dialog without a push.
6. **Stacked layers stay registered.** `closeOnBack` is always on for Plan search and Settings, and is never
   mirrored from `stacked`. Stack order picks the top layer. The one three-deep keyboard route (popup → guide
   → masthead search) is closed off by adding `!guideModal` to search's opening guards.
7. **The API** (all of it lands in PR 1):
   - `Modal` and `BottomSheet` get `back="close" | "cancel" | "block"`. Absent means no registration and no
     history call: byte-identical to today.
   - A registration **updates `blocking` and `onBack` in place** (callback in a ref) when props change, so the
     Settings instance's spinner → result → Settings views never unregister mid-way.
   - A `Modal` with no `onClose` implies block.
   - `useBackLayer({open, onBack, mode})` for raw markup (the guide dialog, `SettingsFallback`) and a function
     wrapper for the `ChunkErrorBoundary` class.
8. **Robustness:**
   - every `pushState`, `replaceState` and `go` is wrapped in try/catch, degrading to entry-less (WebKit
     throws above about 100 calls in 10–30 s);
   - `history.state?.` is read defensively everywhere;
   - no import-time side effects beyond the listener;
   - StrictMode's double mount nets to one push.
9. **Off switch:** a single constant in `dialogHistory.js` turns every registration into a no-op, proven by a
   test that makes zero history calls. Rollback is a one-line PR.
10. **The first-run guard (PR 3):**
    - defer to `click` or `keyup`, not `pointerdown`, which is not activation-triggering on touch;
    - push only when `userActivation.isActive`.
11. **Corrections to earlier claims:**
    - Not every `replaceState` copies `history.state`: `App.jsx:158`, `App.jsx:209`, `RegisterPage.jsx:276`
      and `ResetPasswordPage.jsx:64/73` write `{}`. They run in auth flows only, item 1's guard covers them,
      and they are named as intentional.
    - Every other `replaceState` writer must keep copying state. The CLAUDE.md rule below says so.

### Scroll (replaces design item 12's scroll line)
- **The problem:** with `scrollRestoration='auto'`, every close (×, Escape, `selectTab`, Back) now traverses,
  and the browser restores the base entry's scroll afterwards. That undoes app scrolls: the Ask highlight's
  smooth scroll, a page scroll made behind a `Modal`, and the opener scroll from focus restore.
- **The fix:**
  - set `history.scrollRestoration='manual'` **just before the first push**, so both the base and the layer
    entry inherit it;
  - set it back to `'auto'` once at depth 0 with our traversal landed;
  - guard for jsdom, which lacks the property.
- **Side effect:** reloading with a dialog open no longer restores scroll. Accepted.
- **BottomSheet's scroll lock gets a module-level reference count.** With two sheets closing in one commit,
  `body` was being left `overflow:hidden`.

### Excluded: also name these
- the spot hover peek (`useSpotPeek.js:184`), `MapPeekAsk` (`:305`) and `DayTooltip`;
- the in-panel confirm and edit steps in Operations. Their Escape steps back inside the panel; Back leaves the
  panel through the hash, as today.

### Tests (replace the PR test lists)
- **Vitest.** The store takes an **injectable history and window seam**. Its logic is tested against a small
  fake that models Chromium:
  - every queued traversal runs;
  - a push truncates the forward entries;
  - an out-of-range `go` does nothing.

  jsdom itself merges two `back()` calls into one, drops a traversal followed by `pushState`, and has no
  `userActivation` or `scrollRestoration`. So it is used only for a few wiring tests, and the gaps are listed
  in `dev-and-testing.md`.
  - Assert spy calls (`go` called once with `-2`), not `popstate` counts.
  - Use `__resetForTests()` between tests, and fake timers for the timeout branch.
  - **Cases to add:**
    - Back with one layer makes exactly one traversal;
    - Escape held down over two layers;
    - a hash push between a close and its arrival;
    - Forward bounce;
    - a refused close re-pushes;
    - a blocking layer over an included layer;
    - `onBack` throwing;
    - logout with a dialog open;
    - the Rewind remount;
    - StrictMode;
    - the off switch;
    - the marker survives each state writer;
    - an absent prop makes zero history calls (spies), because the attribute pinning alone does not prove it;
    - a lazy target swap;
    - a same-depth swap makes no `go(0)`.
- **Playwright** (`smoke/back.spec.js`, after 3a.1 merges):
  - **Before every `goBack` / `goForward` / reload,** wait for the store to settle: poll a `data-back-depth`
    attribute on `<html>` (a seam like 3a.1's `data-placed`). Otherwise Playwright's index-based `goBack`
    double-steps.
  - **The baseline:** capture `navigation.currentEntry.index` before opening anything. After a ×,
    Escape or `selectTab` close, assert the index is back to the baseline and there is no `phLayer`. **"No
    dead press" is the property**, not "stays in the app"; `goBack` from the baseline leaving the origin is
    correct.
  - **The Operations case:** use the seeded `#manage/users/<adminId>`. Open the `reset-password` confirm,
    `goBack` cancels it and the panel stays, then the next `goBack` closes the panel. **Never click
    confirm-dialog-confirm**, because it would reset the shared admin.
  - **The guide case:**
    - stub the guide iframe with a route-fulfilled HTML page that posts the `photocast-guide` message, and
      add that host to the fixture's allowed list;
    - follow a link inside it, then `goBack` closes the guide, and × followed by `goBack` gives no dead press.
  - **Focus after Back:**
    - the first `goBack` returns focus inside `window-sheet`, on its spot control;
    - the second returns it to the Plan card;
    - over the Map, focus returns to the callout's control.
  - **Scroll:** assert `scrollY` after closing with ×, Escape and `goBack`, including the Ask-highlight route.
  - **Reload:** assert `history.state?.phLayer` is absent.
  - **Selectors:** `bottom-sheet` is shared, so select by `aria-label`. The guide dialog's test id is
    `guide-dialog`.
  - **Phone width:** `test.use({ viewport: { width: 390, height: 844 } })`.
  - **WebKit** is a one-off local run, with its results recorded in the PR. The gate stays Chromium-only.
  - **Reliability proof:** the same 5× matrix plus `--repeat-each=10`. Budget about 30–40 s more.
- **The manual device pass is a RELEASE BLOCKER.** Playwright's `goBack` ignores skippable entries and
  always grants activation, so none of these can be automated:
  - **Android Chrome, in a tab and as a standalone PWA:**
    - Back on each included dialog;
    - a double Back on `temp-password-modal`;
    - a Back on the result view and then Back again;
    - a data-refreshed popup;
    - a cold-chunk swap;
    - the guide with in-frame navigation;
    - the first-run guard after a tap and before any tap;
    - predictive-back previews;
    - the keyboard-up first Back (it only hides the keyboard, which is expected).
  - **iOS Safari in a tab:**
    - swipe-back, including a check for a flash or bounce;
    - swipe-forward;
    - VoiceOver's scrub gesture.
  - **Desktop Chrome and Firefox:** Back, Forward, the mouse back button, and Alt+Left inside a text field.

### PR split (replaces the earlier split)
- **Landing PR:** the guide's links replace history in embed mode. Published before 2b.
- **PR 1:** the store, the seam, the `back` prop on `Modal` and `BottomSheet` (all three modes), `useBackLayer`,
  the scroll handling, the BottomSheet lock reference count, the off switch, and `data-back-depth`. A harness
  test with a real stacked `Modal` and `BottomSheet` stands in for the missing consumers.
- **PR 2a:** the cancel and block set (confirms, aurora, temp-password, the Settings spinner, delete and
  result views), plus Settings, the sub-region sheet and confirm, the guide, the Ask sheet and lite-limit,
  with their specs. A blocking modal ships with, or before, any included layer it can sit over.
- **PR 2b:** the Plan-shell dialogs (popup, spots, location sheet, picks, search), the
  `openWindow(null)`-on-vanish fix and the `!guideModal` guard, with their specs. **No release is cut between
  2a and 2b.**
- **PR 3:** the first-run guard.
- **Each PR** gets the full UI review cadence (about six lenses plus refuters), and amends the 3a.1 line "the
  browser Back button closes no dialog … not tested".

### Docs
- **CLAUDE.md:** append to the **Dialogs** bullet as a rule:
  > "Back: a layer Escape closes takes `back="close"`; history entries go only through
  > `utils/dialogHistory.js`; every `replaceState` copies `history.state`; never register a `#manage` panel
  > (see `plan-tab.md`, 'Back')."
- **The detail** goes in `plan-tab.md`, `map-tab.md` (the exclusions) and `dev-and-testing.md` (the jsdom and
  Playwright limits).
