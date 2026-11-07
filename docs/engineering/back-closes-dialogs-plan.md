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
- **A Back racing a close of our own** (x, Escape, a data close): the shrinking `go` is deferred one task and the depth
  re-read, which narrows the window. Once our `go` is out, a Back the reader had already queued is indistinguishable
  from it and can step one entry further (to whatever is below the app). The History API cannot close this; accepted.
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

#### PR 3 build notes (planned 2026-11-07, from `origin/main` `d6412142`)

**What is guarded.** Two prompts, `PostcodePromptOverlay` (`postcode-prompt`) and `SubRegionSignInPrompt`
(`sub-region-prompt`). Each is a portalled `role="dialog"` that Escape does not close, and each has only its own
buttons as exits. Owner decision: once the reader has interacted, Back does nothing while either is open. Before
any activation, Back leaves the page as it does today.

**The engine stays as it is.** The guard is an ordinary `mode: 'block'` layer, which the engine already handles
(design item 9: on `popstate` it pushes again and closes nothing). Only *when* it registers is new.

**`useBackLayer` gains `activationGated` (default `false`).** When it is true, an open layer registers only once
the page has a live activation:
- **At open:** if `navigator.userActivation?.isActive` is true, register at once.
- **Otherwise:** add a capture-phase listener for `click` and `keyup`, never `pointerdown`, which does not
  trigger activation on touch (design item 10). Attach it to the prompt's element when the hook is given a ref,
  else to `document`.
  - On the first such event, register if `isActive` is true. If the browser has no `navigator.userActivation`,
    register too: the event is itself a user gesture.
  - Then remove the listener.
- **Clean-up:** closing before any activation removes the listener and pushes nothing. Closing after
  registration releases the layer, so the engine drops its entry, as for every other layer.
- **Prompts that are always open from mount** (both are rendered only while shown): `open` is `true`, so the
  gate alone decides when the entry appears.
- The off switch (`BACK_CLOSES_DIALOGS_ENABLED=false`) still makes it a no-op, with zero history calls.
- **No `beforeunload`.**

**Wiring.** Each prompt calls `useBackLayer({ open: true, mode: 'block', activationGated: true, ref: dialogRef })`.
Back on a registered prompt is absorbed: the prompt stays open, focus stays inside it, and the URL does not change.

**Stacking.** The postcode prompt already waits for the sub-region prompt to close (`useModalPresent`), so at most
one guard is live at a time. Neither prompt can sit under an included dialog.

**Tests (Vitest).**
- `useBackLayer` with `activationGated`, under a fake `navigator.userActivation`:
  - an already active page registers at open;
  - an inactive page registers on the first `click` or `keyup`, and not on `pointerdown`;
  - with no `userActivation` API, it registers on the gesture;
  - closing before activation makes zero history calls and leaves no listener;
  - StrictMode's double mount nets to one push;
  - the off switch makes zero history calls.
- Each prompt component:
  - after a click inside it, a simulated Back keeps it open and closes nothing beneath it;
  - before any activation, nothing is pushed;
  - Save, Not now and Continue release the guard.

**Tests (Playwright smoke, `back.spec.js`).** Use an account that sees the prompt: the seeded `lite` or `pro`
account has no `postcode-prompt-dismissed` path in `global-setup.js`. If neither shows the prompt, add a seeded
user for it in `E2eRoleUsers`.
- Click inside the prompt, then `page.goBack()`: the prompt is still visible, the URL is unchanged, and
  `data-back-depth` is settled.
- Not now: the prompt closes and the depth returns to 0.
- No dead press: one more `goBack` then leaves to whatever is below.
- **Before any click:** there is no history entry (assert on `history.length` or `data-back-depth`). Playwright's
  `goBack` always grants activation, so the "Back leaves before a tap" claim itself is left to the manual pass.

**Manual pass (release blocker, already listed above).** On Android Chrome, both in a tab and as a standalone PWA,
check the first-run guard after a tap and before any tap. Record the result here.

**Docs.**
- `plan-tab.md` 'Back': the guarded prompts.
- This plan's status.
- A `changelog.d/` entry.
- **Each PR** gets the full UI review cadence (about six lenses plus refuters), and amends the 3a.1 line "the
  browser Back button closes no dialog … not tested".

### Docs
- **CLAUDE.md:** append to the **Dialogs** bullet as a rule:
  > "Back: a layer Escape closes takes `back="close"`; history entries go only through
  > `utils/dialogHistory.js`; every `replaceState` copies `history.state`; never register a `#manage` panel
  > (see `plan-tab.md`, 'Back')."
- **The detail** goes in `plan-tab.md`, `map-tab.md` (the exclusions) and `dev-and-testing.md` (the jsdom and
  Playwright limits).

---

## Refuter amendments (2026-11-07). Binding, and the last word where they conflict with the amendments above.

1. **The marker and the depth arithmetic.**
   - The marker is `{session, depth}`, and **`depth` is the entry ordinal**: the count of our entries up to this one, not the stack index.
   - **Drop `href`, `seq` and the URL-restore step (2.1).** No writer in the app both pushes and copies state, and a `location.hash` push gets `state = null`, so `href` guarded nothing. The restore could misread a programmatic hash push as a rewrite, eat the reader's Back, and strip markers after legitimate `{replace:true}` rewrites. Data-driven hash rewrites under a modal heal themselves.
   - **Back** closes `n = committedDepth − arrivedDepth` entried layers from the top, together with any entry-less layers above them.
   - A blocking layer re-pushes `committedDepth − arrivedDepth` entries. Survivors without an entry stay entry-less.
   - The Forward bounce is `go(committedDepth − arrivedDepth)`.
   - **Vitest** covers mixed stacks: entried L1 with entry-less L2, and the reverse.
2. **At most one `go` in flight.**
   - Record the depth it is expected to land at. An arrival anywhere else is the user's and is classified normally.
   - The next corrective `go` is issued from the actual arrived depth, and only when nothing is in flight.
   - This fixes the fast double Forward that would otherwise step past B0 and leave the app.
   - **Vitest:** double Forward and double Back.
3. **Scroll reset.** Set `scrollRestoration='auto'` on **any** arrival at depth 0 with an empty stack, and at boot when the stack is empty. Otherwise `'manual'` spreads to later `#manage` panel entries, and Operations stops restoring list scroll on Back.
4. **Drop the refused-close re-push (old item 4).**
   - The Settings "drive times updated" result is a *view*, not a layer, so Back on it closes Settings (`onBack = onClose`). Deleting in flight is `mode = block`, read live.
   - **Defensive fallback:** if a layer is still registered after its `onBack`, make it entry-less. **Never re-push from `popstate` without activation.**
5. **`back="cancel"` respects in-flight guards.** While a confirm's own Cancel is disabled, its mode switches to `block` through the in-place update:
   - `AuroraForecastModal` while `running`;
   - `AuroraSimulateModal` while `submitting || clearing`;
   - `ScheduleSwitchDialog` while `sending`.
6. **Drop `openWindow(null)` / `openOverPopup(null)` on vanish.** Register the shell's layers from the **derived** open state instead: `open: openCard != null` (`WindowFirstShell.jsx:1362`) and `sheetCard != null` (`:1038`).
   - That is still intent plus data rather than the lazy render, so the cold-chunk concern is met.
   - A vanish unregisters and traverses, and `go` needs no activation.
   - A returning card registers entry-less, which is today's behaviour.
   - This also respects the shell's existing `set-state-in-effect` lint decision (`:1318-1325`).
7. **The `!guideModal` guard covers both doors:** `onOpenSearch` (`:2574`) and the beyond line's `onSearchRegion` (`:3006`).
8. **The session id** comes from `crypto.getRandomValues`, not `crypto.randomUUID()`, which is undefined outside a secure context, e.g. a phone testing `vite --host` over a LAN IP.

---

## PR 1 build notes (2026-11-07)

What the engine does where the plan was silent or its formula needed a correction.

- **The residual Back + close race.** A shrinking `go` is deferred by one task (`setTimeout(0)`) and `readDepth()` is
  re-checked against `committedDepth` before it is issued, so a Back that landed first means no `go` at all. Where
  `window.navigation.currentEntry.index` exists it also tells our landing from the reader's. Once our `go` is out, a Back
  queued before it is indistinguishable: the reader can end one entry lower. Accepted; see Risks.
- **A hash push while an Included dialog is open closes it.** `location.hash = ...` fires `popstate` with a null state,
  which reads as depth 0, so every open layer closes (as `selectTab` does). Accepted for v1: only admin Operations
  writes the hash, and rarely while a dialog is up. A hash write racing a close can revert the hash navigation.
- **Re-push count.** After a blocking layer stops a Back, the entries pushed are `entried layers remaining - arrivedDepth`,
  not `committedDepth - arrivedDepth` (identical with a blocker on top, correct for a blocker beneath a closed layer).
  Likewise Back closes `entriedCount() - arrivedDepth` layers, so a Back that consumed the entry of a layer already
  closed by the app does not close the layer beneath.
- **Scroll.** `'auto'` is restored on every arrival at depth 0 with an empty stack and nothing outstanding whenever the
  property reads `'manual'` (a hash entry pushed while a layer was open inherits it), and at boot.
- **Timeout.** A `go` that has not landed re-arms once; the store degrades to entry-less only on the second expiry.
- **Lazy boot.** `bootDialogHistory()` (strip a stale marker, heal `scrollRestoration`) runs on the first registration.
  **PR 2a must call it once at app mount**, so a reload onto a `'manual'` entry is healed before any dialog opens.

---

## PR 2a build notes (2026-11-07)

- **Deviation: `settings-fallback` and the `ChunkErrorBoundary` overlay are `close`, not Blocking.** The inventory listed them
  as Blocking, but the stand-in IS Settings (and the overlay is Settings' or Ask's failure surface): a reader who presses Back
  expects the dialog to go, and an absorbed Back only holds for the first press on Chromium. The overlay is `block` only when it
  has no Close control. `ChunkErrorBoundary` takes `backLayer={false}` where its owner already registers the layer (Ask's sheet,
  registered by the shell).
- **Settings registers ONCE**, with `useBackLayer` in `UserSettingsModal`, not through `Modal back=`: the body, the drive-times
  spinner and the result view are one `Modal` instance whose `onClose` differs per view, and Back on the result must close
  Settings. The mode is `block` while `refreshing || deleting`, `close` otherwise.
- **`lite-limit-dialog` uses `Modal back="close"`** (it already renders through `Modal` with an `onClose`; its window Escape
  listener is untouched).
- **The Ask sheet registers from the shell** (`useBackLayer({open: askSheetShown})`, outside `Suspense`), not inside the lazy
  chunk, so a cold chunk does not delay the entry. `AskSheet`'s `BottomSheet` therefore has no `back`.
- **`temp-password-modal`:** Back is blocked, and a scrim press no longer closes it (`Modal closeOnBackdrop={false}`): only its
  Close button discards the password.
- **Accepted:** the aurora-run case. `AuroraForecastModal`'s `handleRun` ends with a hash push (`RunsView` `clear()`) while the
  modal is `block`; the hash push reads as depth 0 and the engine's re-push leaves one extra hop on Back. Not fixed in v1.
- **Manual Android check (release blocker, added to the device pass):** Back during the Settings drive-times spinner, then Back on
  the result view; the same during an account deletion.
- **Guide fix live before release:** the guide link fix (#1773, embed-mode links replace history) must be published with
  `./release-landing.sh` **before any app release that contains 2a**, or Back pages through the guide.
- **Tests:** `smoke/back.spec.js` drives the guide with the repo's real `landing/guide/guide.js` served from a stubbed origin
  (`stubGuideFrame`). The Ask-highlight scroll route is not covered by Playwright (it needs a Ready answer, which nothing seeds in
  the smoke stack); the phone scroll case scrolls while the Ask sheet is up and asserts the value survives each close.

---

## PR 2b build notes (2026-11-07)

- **Wiring.** Five `useBackLayer` calls in `WindowFirstShell`, placed just after `openCard` is derived and outside every
  `Suspense`: popup `openCard != null`, all spots `sheetCard != null`, location sheet `sheetSpot != null`, pick
  `openPick?.pick != null`, search `searchSeed != null`. Each `onBack` is the close Escape runs. No `openWindow(null)` on
  vanish (refuter amendment 6): a vanished card unregisters and the engine traverses.
- **The `!guideModal` guard** is on both doors (`onOpenSearch`, the beyond line's `onSearchRegion`). Proved by mutation: with
  the masthead guard removed the Vitest case fails.
- **Plan ambiguity: "Two layers, then click the Map tab".** The tab bar sits under the modal scrim, so a real click cannot
  reach it. The spec uses the location sheet's own "show on map" door (`location-sheet-map`), which closes both layers and
  calls `selectTab`; the Vitest case clicks the tab directly and asserts one `go(-2)`.
- **Plan ambiguity: "`goBack` reopens nothing (index check)" after `selectTab`.** From the baseline `goBack` leaves the origin
  (to the login entry), which is correct and not what is being asserted, so the spec presses **Forward** instead: the two stale
  entries ahead are bounced, nothing reopens, and the page ends on the baseline.
- **Same-depth swap proof.** Vitest swaps the location sheet's place through a second `locationSheetHandoff` and asserts no
  `go` and no second `pushState`.
- **Discovery.** `E2E_HYGIENE=record` over the whole smoke suite (smoke, roles-write, logout): no hygiene findings from the new
  specs.
- **Review round (2b).** The `location-sheet-map` door closes the two layers in its OWN handler (`openOverPopup(null)` and
  `openWindow(null)`, then the tab move), so the Playwright case proves the entries are consumed and a stale Forward is
  bounced (`navigation.canGoForward` is asserted true first), not that `selectTab` coalesced to one `go(-2)`. That claim
  rests on the Vitest case ("makes one go(-2) and takes both layers") in `backDialogsPlan.test.jsx`.
- **Cold chunks.** `backDialogsPlanCold.test.jsx` mocks `PlanSearch` and `LocationFourDaySheet` with components that suspend
  forever; only the shell's registration can put a layer in the store. Mutation: deleting the shell's two registrations
  (equivalent to moving them into the chunk) fails both tests.
- **Real swap.** Search alone, then a window picked from it, ends with the popup at the same depth: no `go`, one `pushState`.
- **Third layer.** Popup, then masthead search, then the masthead guide door is already refused by `GuideContext.openGuide`
  (two standing dialogs, one inert: the full page opens in a new tab). Pinned by a Vitest case. The beyond-line door's
  `!guideModal` guard is tested, and fails when removed.
