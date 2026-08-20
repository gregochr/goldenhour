# Plan matrix — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session** (Opus), in order. Each phase lands as its
own reviewed commit before the next session starts — the plan's **Status block and Phase log
are the source of truth between sessions** (M1 creates the log), updated in the same commit as
each phase, so a new session never needs this chat's history. If a session dies mid-phase,
start a new one with the same prompt: step one of every prompt is reading the current state
from the repo.

The plan: `docs/engineering/plan-matrix-plan.md` (on main since #586). The design bundle:
`docs/design/plan-matrix/` (vendored verbatim — do not edit it; the plan's §4 adaptation table
and §5 data map **win** wherever the bundle's README disagrees, and all six §8 decisions are
already resolved in-doc).

**Multi-agent note.** These sessions do not need Ultracode or any special mode. The one
multi-agent step is the pre-commit adversarial review, which each prompt instructs explicitly —
plain parallel subagents (the Agent tool) are how every previous phase ran it. Review agents
are **read-only**; anything that must mutate gets its own worktree; commit or stash before a
review that runs mutations.

**Between phases (owner):** review the phase in the browser, merge, push. Sessions never push.
Before starting the next session, expect a `CHANGELOG.md` conflict if anything else merged —
`git rev-list --count HEAD..origin/main` tells you, not which files changed.

---

## M1 · The matrix

> You are implementing **Phase M1** of `docs/engineering/plan-matrix-plan.md`. First: read that
> plan **in full** (§3 ground rules, §4 adaptations A1–A21, §5 data map, and §6 M1 are binding),
> then the design bundle's `docs/design/plan-matrix/README.md` §"The matrix" and screens
> 01/02/05/07, then re-verify every file and symbol M1 names against the tree before editing —
> the plan was written 2026-08-20 and code moves. Never push, never create or delete tags.
> Create `feature/plan-matrix-m1-matrix` off up-to-date `main`.
>
> Scope is §6 M1's eight tasks, nothing more. The card list below the matrix **stays alive this
> phase** — card click keeps today's open-and-scroll-to-row behaviour; the popup is M2. The
> traps the plan's own review caught, restated so you cannot miss them: the verdict word comes
> from the served `verdictLabel` **only** (a client threshold anywhere in the diff is a defect —
> A1); the spread histogram's pool is reach-gated, sky-gated, and **not** rating-floored, its
> tooltip `N` is the pool size with the unrated remainder named explicitly, never `Σbars`
> (A10/M1.2); the best-reach line is the **head of that same pool under the existing
> comparator** — do not write a second ordering (A11, Rule 13); thumbnails are measured
> **per card** because a solo phone card spans the full row (the strip's single-well
> measurement is wrong here); empty cells and away cells follow §5's rules exactly (away cells
> are divs, Rule 14); the per-card movement chip dies here; new tokens go in `@theme static`
> (Rule 9); the per-card visually-hidden sentence counts what is actually rendered (Rule 15).
>
> Gate on exit codes, never output: `npm run lint && npm test && npm audit --audit-level=high
> && npm run build`. Then run the adversarial review per CLAUDE.md § *UI Work — Review Cadence*
> (~6 prosecutor lenses over the diff, one refuter per charge defaulting to REFUTED, synthesis;
> agents read-only) — paste M1's section **and the plan's §3–§5** into every reviewer's prompt.
> Fix survivors, re-run the gate, then browser-verify per the plan's §9 recipe (all three
> widths, solo card, tint alphas, border legends, redraw on resize and `document.fonts.ready`)
> and state which claims were seen versus tested. Commit with a conventional message, a
> `CHANGELOG.md` entry, and two plan-doc updates **in the same commit**: flip the Status block
> to in-progress, and add a **Phase log** table beneath it (`phase | branch | commit | date |
> notes`) with M1's row — later phases append to it. Do not push.

---

## M2 · The window popup (the list dies)

> You are implementing **Phase M2** of `docs/engineering/plan-matrix-plan.md`. First: read the
> plan in full (§3–§5 and §6 M2 binding), confirm M1 is on `main` (the plan's Phase log says),
> read the bundle README §"Window popup" and screens 03/04/06/08, and re-verify every symbol M2
> names against the tree. Never push. Create `feature/plan-matrix-m2-popup` off up-to-date
> `main`.
>
> Scope is §6 M2's eight tasks. This is the phase with the two traps that survived adversarial
> review as blockers — implement them from the plan's text, not from the bundle: **(1) the
> badge→topic join** must replicate `PlanWindowProjector.keysFor` (A8 rule 1) — a NIGHT topic
> dated D matches its badge on D+1's **sunrise** card, keyed on the served `topic.eventType`,
> never date equality and never a client list of types; **(2) the topic scope filter** (A8 rule
> 2) exempts whole-sky types by type — aurora and NLC serve **populated** `regions` lists that
> mean coverage/conditions, not eligibility, so an unexempted intersection deletes aurora from
> every away plan while naive tests stay green. Both have named fixtures in M2's test list.
> Equally load-bearing: the prose slot's unpicked state is the **top region's gloss labelled
> with the region's name** (A21 — `card.lead` is a boolean, not prose; do not render it and do
> not invent text); the region rail's "Nh away" figure **already exists** (`awayLabel` — reuse
> the producer, Rule 13); esc closes **topmost first, one layer per press** exactly as M2.5
> orders it; the per-window empty-pool quiet sentence has two variants (lens-emptied vs
> region-focus-emptied) and the page-level conflicts cannot substitute for it; the promoted
> strip's scroll-specific `adjacent` suppression is removed in the same commit that retargets
> "Go to" (M2.8). Deletions follow the §7 ledger precisely — list **rendering** only, the
> `buildPaneItems` derivation survives. Migrate tests by salvage-by-behaviour, not wholesale
> port; prove Modal stacking (WindowSpotSheet over the popup) with tests before styling it.
>
> Gate, adversarial review (M2 section + §3–§5 pasted into every reviewer), fix, re-verify,
> browser-verify per §9 (popup at all three widths, keyboard walk ←/→/esc, region pick swaps
> words without moving furniture), then commit with CHANGELOG + the plan's Status/Phase log
> updated in the same commit. Do not push.

---

## M3 · The tick line (origin + search into the masthead)

> You are implementing **Phase M3** of `docs/engineering/plan-matrix-plan.md`. First: read the
> plan in full (§3–§5, §6 M3), confirm M1+M2 are on `main` (Phase log), read the bundle README
> §"Masthead"/"Search" and screens 01/09/10, re-verify symbols. Never push. Create
> `feature/plan-matrix-m3-tickline` off up-to-date `main`.
>
> Scope is §6 M3's six tasks. Task 2 (stickiness) exists because the review caught it as
> unported chrome — the masthead becomes `position: sticky`, the lens re-bases from `top: 0` to
> a shell-measured `--wf-mast-h`, the stuck-lens shadow rides an IntersectionObserver sentinel,
> and the `index.css` comment claiming "sticky here and nowhere above it" is corrected **in the
> same commit** (either build all of it or record the omission as a new §4 row — never silent).
> `MastheadLight`'s three states (unlit / postcode-nudge / lit) survive intact; a failed light
> fetch is still not a 204. The rail footer's deletions are relocations, not losses: forecast
> age moves beside the change line (ONE age on the page, Rule 7), the home-not-set nudge into
> the origin button's empty state, Edit-reach onto the ⚙ path. The origin behaviours that are
> already pinned (re-frame on move, reach default drop to 90, beyond-line suppression,
> `effectiveReachById` overwrite) must keep their existing suites green **unedited** — those
> tests passing unchanged is the proof the move was chrome, not behaviour. Search folding
> improvements per A13; "Recent locations" stays unbuilt; aliases are out of scope (O-3).
>
> Gate, adversarial review (M3 section + §3–§5 to every reviewer), fix, re-verify,
> browser-verify per §9 (tick line at all widths, search open/anchored, `/` shortcut, sticky
> behaviour mid-scroll), commit with CHANGELOG + Status/Phase log in the same commit. Do not
> push.

---

## M4 · The location sheet, v3

> You are implementing **Phase M4** of `docs/engineering/plan-matrix-plan.md`. First: read the
> plan in full (§3–§5, §6 M4, and §8 — **D-3 and D-4 are RESOLVED yes**, so this phase is
> ungated; the resolutions and their terms are in the doc), confirm M1–M3 are on `main` (Phase
> log), read the bundle README §"Location sheet" and screen 11, re-verify symbols. Never push.
> Create `feature/plan-matrix-m4-sheet` off up-to-date `main`.
>
> Scope is §6 M4's four tasks. The sheet's existing key policy is untouchable: rating and prose
> come from **one id-first score row** (never the name-keyed `scoreIndex` — a recorded
> adversarial-review finding), confidence is the location's own region's, the row's clock is
> the location's own, and those existing suites stay green unedited. The lead line carries **no
> denominator ratio** ("2 windows at 4★+" / "none at 4★+" — Rule 5 and the P8 lesson). The new
> entry points (popup field chips, spot-strip cards) stack the sheet **over** the popup; esc
> follows M2.5's topmost-first order. The `Plan from <region>` footer is **close-then-move**:
> the sheet and the popup close first, then `setOrigin` — assert the *sequence* in a test, not
> just the outcome, because the P8 invariant this honours is about what happens under an open
> surface. Confidence marks are tier treatments, never percentages (A2).
>
> Gate, adversarial review (M4 section + §3–§5 to every reviewer), fix, re-verify,
> browser-verify per §9 (all three entry routes, stacked esc, footer origin move re-framing the
> matrix behind the closed dialogs), commit with CHANGELOG + Status/Phase log in the same
> commit. Do not push.

---

## M5 · Disposition, sweep, and the settling commit

> You are implementing **Phase M5** of `docs/engineering/plan-matrix-plan.md` — the final
> phase. First: read the plan in full (§3–§5, §6 M5, §7 ledger, §8 — **D-1 and D-2 are
> RESOLVED**: the promoted strip is deleted, the doors stay), confirm M1–M4 are on `main`
> (Phase log), re-verify symbols. Never push. Create `feature/plan-matrix-m5-sweep` off
> up-to-date `main`.
>
> Scope is §6 M5's seven tasks. Delete the promoted strip per the §7 ledger
> (`WindowFirstPromotedStrip`, `windowFirstPromoted.js`, their tests, the shell's strip wiring)
> — deletion lands complete in this commit, never "later"; add a pinning check that the doors
> still render below the matrix untouched. Run the §6-clause copy sweep over **every string
> the series added** (no counts of our own data, no denominators, no two-ages, no "since"
> alongside an age). The accessibility pass is the one the flag flip has been waiting for: axe
> on matrix/popup/sheet/search, a real screen-reader walk (browse mode, not just focus order),
> keyboard-only traversal, focus-visible on every new control. Phone pass at 390px on all four
> surfaces. Measure the redraw storm (resize + font load + origin move) against the plan's
> Rule 8 invariants and record the numbers in the plan; verify the `geo` chunk boundaries
> survived the component moves (no d3-geo in the entry bundle). Update CLAUDE.md's Plan-tab
> bullets (strip → matrix) — leave the stale v1-side comments for the v1 deletion. Then the
> full-page adversarial review (the ~15-agent shape: ~6 lenses, refuters, synthesis; agents
> read-only, §3–§5 + the whole §6 pasted in), fix survivors, re-verify, and close the plan:
> Status flipped to COMPLETE with the Phase log's M5 row, CHANGELOG entry, one commit. Do not
> push. State plainly in your final summary what was verified in the browser versus asserted —
> the owner's next decision after this phase is the flag flip.
