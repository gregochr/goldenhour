# O-20 — the pane under a dialog, and whether `inert` is the answer

**Status: PLAN ONLY. Nothing built. One decision blocks the work and it is the owner's.**

`map-tab-v2-plan.md` **O-20** names its own cure in one line — "shell-root `inert` while any dialog
is open" — and `useDialogFocus`'s ruling names it as "a named follow-on, not adopted here". This
document is what you get when you cost that follow-on before writing it, and the costing turned up
something neither of those two records: **the cure cannot be protected by this project's test gate.**

---

## §1 The crux: `inert` is a silent no-op in the only test environment CI runs

Measured in this repo's jsdom (`vitest`, 2026-09-07), not inferred:

```
inert in HTMLElement.prototype: false
HTMLDialogElement.prototype.showModal: undefined
focus reached a button inside an inert div: TRUE
```

The third line is the one that matters. Setting `inert` changes nothing, and focus still lands
inside. So a test asserting *"the pane is inert, therefore Tab cannot reach the map behind the
sheet"* **passes whether or not the guard exists**. `useDialogFocus`'s ruling already says this in
as many words — "it fails as a *silent no-op*, so the tests would go green while asserting nothing
about the guard" — and that sentence was written about a different use of `inert`, but it transfers
exactly.

This project has been bitten by this specific shape three times in the last fortnight: a required
prop whose absence threw inside a handler, so every "nothing happened" test passed; a fixture built
from the constant it was testing, so the mutant moved with it; and a `getBoundingClientRect` that
grew while an ancestor's `overflow: hidden` clipped every pixel. **A guard that cannot fail a test
is not a guard, it is a comment.**

**Playwright exists and would see it** — `frontend/playwright.config.js`, `src/test/e2e`, Chromium,
dev-server-backed. ⚠️ **But e2e does not run in CI**: `.github/workflows/ci.yml` mentions it only in
a comment, *"To run locally: cd frontend && npm run test:e2e"*. So today the only instrument that
can see this guard is one nobody runs on a PR.

**That is the decision in §5.** Everything below is downstream of it.

---

## §2 What O-20 actually still contains

The Escape and outside-press arms closed on 2026-09-07 (#794). Three remain.

| arm | what happens | reachable how |
|---|---|---|
| **A — Tab-out** | A keyboard reader Tabs from the sheet onto Leaflet's controls, the window control, the Filters/Regions/Legend chips and the callout's buttons, all behind a 60%-black backdrop | Tab, repeatedly, from any open dialog |
| **B — phone `BottomSheet`** | Portalled to `document.body` at `z-index: 10000`, so it paints *over* the `z-50` sheet rather than under it | open a map popover from arm A on a phone |
| **C — focus restore (NEW, 2026-09-07)** | `useDialogFocus`'s cleanup restores focus to its captured trigger with no orphaned-focus guard, so closing the sheet moves focus *out of* a drilldown panel that is still open | arm A, then open the drilldown, then Escape |

C is new and is a **consequence of #794**: before it, the panel closed on that press, so moving focus
out of it was coherent. It is the same family as the focus-to-`<body>` defect the map-landing
increment fixed five times.

---

## §3 The two prerequisites the ruling names, both real

`useDialogFocus`'s ruling says the follow-on "would need App-level sibling dialogs brought inside
the guarded tree and `stacked` gated on the covering layer having mounted first, both unstarted."
Verified:

1. **App-level siblings.** `UserSettingsModal` mounts at `App.jsx:689`, a sibling of the shell, not
   inside it. A shell-root `inert` would either not cover it (leaving the hole) or would be applied
   somewhere that also covers the settings dialog itself (making it inert while it is the thing the
   reader is using). Bringing it inside the guarded tree is a real refactor of `App`'s structure.
2. **`stacked` mount ordering.** Four dialogs pass `stacked={!escapeEnabled}`
   (`WindowSheetDialog`, `LocationFourDaySheet`, `WindowSpotSheet`, `WindowPickDialog`). `stacked`
   currently means "a layer is above me"; under a shell-root guard it would have to mean "a layer
   above me has *mounted*", because the guard is applied by the covering layer and the covered one
   must not go inert before that has happened.

---

## §4 Arm C is the one small enough to do first — and it has its own trap

`Modal` already solves this problem for its own uncover path:

```js
if (active && active !== document.body && active !== document.documentElement) return;
```

— *"a reader who Tabbed out into the page while the top layer was up has chosen where they are, and
yanking them back is worse than leaving them."* `useDialogFocus`'s cleanup has no such guard.

⚠️ **The obvious port would strand the reader on `<body>` if focus were still inside the closing
dialog at cleanup time** — the exact defect fixed five times last increment. Measured (jsdom, React
19): at cleanup `document.activeElement` is **already `<body>`** and the dialog node is already
detached, so the guard passes and the normal close still restores. ⚠️ **That measurement is jsdom's
ordering, not Chromium's**, and focus/blur timing is precisely where this project has been burned by
the difference before (`jsdom-accname-is-not-a-browser`; `inert`'s blur landing at a rendering step).
**Re-measure in a browser before relying on it.**

Arm C is jsdom-testable *on its own terms* (focus state is observable without `inert`), which is why
it is separable from the `inert` question entirely.

---

## §5 The decision — the owner's, and it is not "shall we do O-20"

**Q. Is shell-root `inert` still the right cure, given it cannot be protected by CI?**

Three answers, and I have a recommendation.

**(a) Adopt `inert`, and put e2e in CI first.** Honest, and it makes the guard real. Cost: an e2e
job on every PR (macOS/Linux runner minutes, a dev server, flake surface this repo has never
carried). The guard is then protected the way everything else is.

**(b) Adopt `inert` without CI coverage.** Cheapest to write, and the worst of the options on this
project's own evidence: a silent-no-op guard, in a codebase whose last three defects were all
"a test passed for the wrong reason". Not recommended.

**(c) Do not adopt `inert`. Close arm C, leave A and B recorded.** Arm C is real, new, caused by our
own fix, small, and *testable*. Arms A and B have been the accepted posture since v1-retirement §4.3
and are unreachable without deliberately Tabbing out of a modal. The ruling's three reasons against
containment (Leaflet mutating its own tab stops, the body-portalled bottom sheet, the settings
spinner with nothing focusable) are all still live facts.

**Recommendation: (c) now, and (a) as its own piece of work if and when Tab-out is judged worth an
e2e job.** Arm C is a real defect we introduced; arms A and B are a documented posture. Doing C now
costs little and removes the newest hazard. Adopting `inert` blind would add a guard nobody can
prove, which is how the last three defects in this repo got in.

**A fourth option worth naming rather than assuming away:** focus **sentinels** — a focusable node
either side of the guarded tree that bounces focus back — are jsdom-testable, need no `inert`, and
need neither prerequisite in §3. They are a *trap*, which the ruling refuses app-wide, so this is a
reversal of a recorded decision rather than a follow-on to it. Raised for completeness; not
recommended without the owner reopening that ruling deliberately.

---

## §6 What was NOT examined

No browser run: every claim here is from the repo, the vitest environment, and two measurements
noted as jsdom-only. I did not measure Tab order in Chromium, did not check whether Leaflet's own
controls are reachable in the real app behind a real backdrop, and did not cost the e2e-in-CI job
beyond noting it does not exist. Arms A and B are described from O-20's own record, not
independently reproduced.
