# O-20 — the pane under a dialog, and whether `inert` is the answer

**Status: arm C BUILT 2026-09-07. Arms A and B unstarted, and §5's decision — whether shell-root
`inert` is the right cure at all — is still the owner's.**

⚠️ **Arm C did not need `inert`, and §5's recommendation (c) is what shipped.** `Modal` already
carried the guard; mirroring it closed the arm with neither prerequisite in §3 and with a test the
suite CI actually runs. That is evidence for (c) beyond arm C: before adopting a guard nobody can
prove, check whether the defect has a cure that can be.

`map-tab-v2-plan.md` **O-20** names its own cure in one line — "shell-root `inert` while any dialog
is open" — and `useDialogFocus`'s ruling names it as "a named follow-on, not adopted here". This
document is what you get when you cost that follow-on before writing it, and the costing turned up
something neither of those two records: **the cure cannot be protected by this project's test gate.**

---

## §1 What `inert` can and cannot be tested for here

⚠️ **This section overclaimed in its first draft, in two ways an adversarial lens caught, and the
correction changes §5.**

Measured in this repo's jsdom (`vitest`, jsdom 30.0.1, 2026-09-07):

```
inert in HTMLElement.prototype: false
HTMLDialogElement.prototype.showModal: undefined
focus reached a button inside an inert div: TRUE
```

**Overclaim 1 — this was not a discovery.** The first draft said the costing "turned up something
neither record states". Both records state it. `useDialogFocus`'s own ruling
(`hooks/useDialogFocus.js`) says `inert` "fails as a *silent no-op*, so the tests would go green
while asserting nothing about the guard" — I quoted that sentence in this very document and still
claimed novelty. And **`map-tab-v2-plan.md` item 21, the document being costed**, said it on
2026-09-04: *"jsdom implements no `inert` behaviour, so the tests assert the ATTRIBUTE; a test there
trying to prove non-focusability would pass against a no-op."* Only the third probe line above is
new, and it is a confirmation, not a finding.

**Overclaim 2 — "the cure cannot be protected by CI" is false as written**, and item 21 is the
proof: **this project has already shipped an `inert` guard and pinned it in the suite CI runs.**
`MapHeatLayer` marks Leaflet's marker panes `inert` while the heat field owns the map, and
`MapHeatLayer.test.jsx` asserts `hasAttribute('inert')` on mount and its **absence** on unmount,
under a comment that draws the line exactly: *"jsdom implements no `inert` BEHAVIOUR, so this asserts
the attribute. A test here that tried to prove non-focusability would pass against a no-op and prove
nothing; the property it stands for is browser-only."*

So the honest split is:

| claim about a shell-root `inert` guard | testable in CI? |
|---|---|
| the attribute is applied to the right node when a dialog opens | **yes** — item 21's pattern |
| it is removed again on close, so nothing is left inert | **yes**, and that is the failure that would matter most |
| Tab genuinely cannot reach the map behind the sheet | **no** — browser-only |

That is materially weaker than "cannot be protected", and it is the version §5 is now argued from.
What remains true, and is the reason to keep stating it: **the property the guard exists for is the
one CI cannot see**, so a green suite would mean "we applied an attribute", never "the reader cannot
Tab out". Playwright would close that gap and does not run in CI — ⚠️ and the reason is not merely
runner minutes: `ci.yml`'s own comment says *"E2E tests require a live Spring Boot backend
(Open-Meteo + Claude + DB)"*, which the first draft of §5 omitted while costing that option.

## §2 What O-20 actually still contains

The Escape and outside-press arms closed on 2026-09-07 (#794). Three remain.

| arm | what happens | reachable how |
|---|---|---|
| **A — Tab-out** | A keyboard reader Tabs from the sheet onto Leaflet's controls, the window control, the Filters/Regions/Legend chips and the callout's buttons, all behind a 60%-black backdrop | Tab, repeatedly, from any open dialog |
| **B — phone `BottomSheet`** | Portalled to `document.body` at `z-index: 10000`, so it paints *over* the `z-50` sheet rather than under it | open a map popover from arm A on a phone |
| **C — focus restore** | ✅ **CLOSED 2026-09-07** by mirroring `Modal`'s own uncover-restore guard — no `inert`, neither §3 prerequisite, verifiable in jsdom | arm A, then open the drilldown, then Escape |

C is new and is a **consequence of #794**: before it, the panel closed on that press, so moving focus
out of it was coherent. It is the same family as the focus-to-`<body>` defect the map-landing
increment fixed five times.

---

## §3 The two prerequisites the ruling names, both real

`useDialogFocus`'s ruling says the follow-on "would need App-level sibling dialogs brought inside
the guarded tree and `stacked` gated on the covering layer having mounted first, both unstarted."
Verified:

1. **App-level siblings — THREE surfaces, not one.** ⚠️ The first draft verified only
   `UserSettingsModal` (`App.jsx:689`) and rendered the prerequisite as that one dialog. The ruling
   it cites (`v1-retirement-plan.md` §4.3 point 3) names three: *"`UserSettingsModal` and
   `MapOverlay` are siblings of the shell in `App`; `BottomSheet` is a body portal."* `MapOverlay` is
   at `App.jsx:706` and calls `useDialogFocus(true)`; `BottomSheet` portals to `document.body`. A
   guard rooted at the shell covers none of the three. Understating this made option (a) look
   cheaper than it is. A shell-root `inert` would either not cover it (leaving the hole) or would be applied
   somewhere that also covers the settings dialog itself (making it inert while it is the thing the
   reader is using). Bringing it inside the guarded tree is a real refactor of `App`'s structure.
2. **`stacked` mount ordering.** Four dialogs pass `stacked={!escapeEnabled}`
   (`WindowSheetDialog`, `LocationFourDaySheet`, `WindowSpotSheet`, `WindowPickDialog`). `stacked`
   currently means "a layer is above me"; under a shell-root guard it would have to mean "a layer
   above me has *mounted*", because the guard is applied by the covering layer and the covered one
   must not go inert before that has happened.

---

## §4 Arm C — built 2026-09-07, and the trap it had

`Modal` already solves this problem for its own uncover path:

```js
if (active && active !== document.body && active !== document.documentElement) return;
```

— *"a reader who Tabbed out into the page while the top layer was up has chosen where they are, and
yanking them back is worse than leaving them."* `useDialogFocus`'s cleanup had no such guard until this work added one.

⚠️ **The obvious port would strand the reader on `<body>` if focus were still inside the closing
dialog at cleanup time** — the exact defect fixed five times last increment. Measured (jsdom, React
19): at cleanup `document.activeElement` is **already `<body>`** and the dialog node is already
detached, so the guard passes and the normal close still restores. ⚠️ **That was jsdom's ordering only**, and
focus/blur timing is precisely where this project has been burned by the difference before
(`jsdom-accname-is-not-a-browser`; `inert`'s blur landing at a rendering step). ✅ **Re-measured in
Chromium before the fix landed**: detaching a focused node, and detaching a subtree containing
focus, both put `activeElement` on `<body>`. The environments agree; the normal close is
unaffected, and is pinned by test.

Arm C is jsdom-testable *on its own terms* (focus state is observable without `inert`), which is why
it is separable from the `inert` question entirely.

---

## §5 The decision — the owner's

**Q. Should arms A and B be closed with a shell-root `inert` guard?**

⚠️ **The first draft asked this as "given it cannot be protected by CI", which is false (§1), and
offered three options that omitted the one this repo already practises.** Corrected:

**(a) Adopt `inert`, pinned the way item 21 pins it.** Assert the attribute lands on the guarded
root when a dialog opens and is gone when it closes; record in the test, as `MapHeatLayer` does,
that non-focusability itself is browser-only. This is the house pattern, it is failable in CI for
the two things most likely to break (wrong node, not cleaned up), and it needs no new CI job.
⚠️ It still needs both §3 prerequisites, and §3's own scope was understated — see below.

**(b) Adopt `inert` AND put e2e in CI**, so the property the guard exists for is covered too. Cost
is not just runner minutes: `ci.yml` records that e2e needs a live Spring Boot backend
(Open-Meteo + Claude + DB), so this is a backend-in-CI question, not a browser-in-CI one.

**(c) Do not adopt it. Leave arms A and B as the accepted posture.** They have been that since
v1-retirement §4.3, whose three reasons are still live facts (Leaflet mutating its own tab stops,
the body-portalled bottom sheet, the settings spinner with nothing focusable), and neither arm is
reachable without deliberately Tabbing out of a modal.

**Recommendation: (a) if arms A and B are judged worth closing at all; (c) if they are not.** I no
longer argue against `inert` on testability — §1's corrected version does not support that, and arm
C is weak evidence for it either way: arm C had a cheaper cure because `Modal` already carried the
guard, which says nothing about arms A and B. The real question is whether Tab-out behind a backdrop
is worth the §3 refactor, and that is a product judgement rather than an engineering one.

**A fourth option, named rather than assumed away:** focus **sentinels** — a focusable node either
side of the guarded tree that bounces focus back — are fully jsdom-testable and need neither
prerequisite. They are a *trap*, which `useDialogFocus`'s ruling refuses app-wide, so this reverses
a recorded decision rather than following one. Not recommended without reopening that ruling
deliberately.

## §6 What was NOT examined

Arms A and B are described from O-20's own record, not independently reproduced: I did not measure
Tab order in Chromium, and did not check whether Leaflet's controls are genuinely reachable behind a
real backdrop in the running app. The e2e-in-CI cost in §5(b) is noted from `ci.yml`'s own comment,
not estimated.

✅ Arm C's focus-detach behaviour **was** measured in Chromium as well as jsdom (§4) — an earlier
version of this section said no browser run had happened at all, which was true when written and
false by the time arm C landed in the same commit.

⚠️ **This document has now been wrong twice in ways a reader would have acted on**: it claimed a
finding that two existing records already carried, and it argued from "cannot be protected by CI"
when the repo had a shipped precedent for pinning exactly this kind of guard. Both were caught by a
review lens, not by me. Weigh its remaining judgements accordingly.
