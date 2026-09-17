# Tide alignment on the Plan card — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order (§8 of the plan says which
may run in parallel). Each phase lands as its own reviewed commit and PR before a dependent session
starts — the plan's **§0 Status block and Phase log are the source of truth between sessions**. If a
session dies mid-phase, start a new one with the same prompt.

The plan: `docs/engineering/tide-plan-card-plan.md`. The spec: `docs/design/tide-plan/README.md`, with
`Plan Tide Summary.html`'s notes column (its two `cut` notes are load-bearing) and `plan-tide-v6.js`
as the prototype. All vendored verbatim — **do not edit them**; the plan's §1 corrections, §4
disagreements and §5 decisions **win** wherever the spec and the code disagree.

**The three things every session must internalise**, because they are what the spec gets wrong
about this codebase:

1. **There is no fit model to import.** The Map-tab model the spec calls a prerequisite was never
   built as a level formula; this app's tide axis is time-based and **served** — `tideAligned`,
   `tideState`, `nearestSolarOffsetMinutes` are flat on every coastal slot. `match ⇔ tideAligned`;
   the ranking tiebreak is the served offset, not `meanFit` (plan §1 #1, §4 #1–#2).
2. **One pool.** Tide facts ride the spot descriptor `buildWindowSpots` builds, so the spread
   histogram, the named spot and the new chip count the same `card.pool`. No second index on the
   Plan tab (§1 #3, §5 #2).
3. **The counts are reach-scoped and therefore client-derived, and that is a named licence, not a
   habit.** New members of CLAUDE.md's reach-scoped class: `card.tide` and `tideRun`, nothing else;
   and no string may say "in reach" unless `card.reachMeasured` is true (§5 #1, §5 #4).

**Owner decisions: none block.** §5 takes the spec's OPEN 1 (fully reach-bound) and the coastal
predicate; §6 records the rest. Sessions build those and do not reopen them.

**Multi-agent note.** The one multi-agent step is the pre-commit adversarial review: plain parallel
Agent-tool subagents, ~6 prosecutor lenses over the diff (runtime behaviour, CSS and tokens, test
quality, accessibility, project conventions, what it makes harder later), one refuter per charge
defaulting to REFUTED without citable evidence, then a synthesis. Review agents are **read-only**.

---

## C1 · The data — tide on the pool, the per-window summary, the run

> You are implementing **Phase C1** of `docs/engineering/tide-plan-card-plan.md`. Read the plan **in
> full** (§1, §2, §3 C1, §4, §5 binding), then the spec `docs/design/tide-plan/README.md` §2–§3, then
> `gh pr list --state open` for overlap on `windowFirstSpots.js`, `windowFirstCards.js` or the card, then
> re-verify every symbol C1 names against the tree. Read `docs/engineering/frontend-test-standards.md`
> and CLAUDE.md's **Backend-heavy** bullet. Never push, never tag. Create `feature/tide-card-c1-data`
> off up-to-date `main`.
>
> Scope is §3 C1's four tasks and **no visible change**: `buildWindowSpots` copies `tideState`,
> `tideAligned` (null when no state) and `tideOffsetMinutes` (`nearestSolarOffsetMinutes`) onto each
> spot; `buildWindowCards`' descriptor gains `tide = {coastal, matched, live, meanOffsetMinutes}` over
> `pool` with the spec's gate verbatim (`coastal >= 4 && matched >= max(3, ceil(coastal * 0.5))`) and
> named constants; new pure `utils/windowFirstTideRun.js#tideRun(cards)` → `{liveKeys, bestKey,
> liveCount}` ranked `matched DESC, meanOffsetMinutes ASC (nulls last), strip order ASC`, `bestKey`
> null unless `liveCount > 1`, away/unserved cards never live. **Nothing reads a level or a threshold
> on a level**; the only number compared is the served offset, for ranking.
>
> Tests per C1 #4, mutation-sensitive — the gate at each edge, `meanOffsetMinutes` over matched only,
> the spec's own tie case (three live windows matching the same nine; the smallest mean offset wins,
> not the first — §7 #3), and §7 #9 (`coastal + inland == pool.length`). Gate on exit codes: `npm run
> lint && npm test && npm audit --audit-level=high && npm run build`. Adversarial review per CLAUDE.md
> (read-only; paste C1, §1 #1/#3/#8/#11, §4 #1/#2/#8, §5 and the spec's §2–§3). Fix survivors,
> re-gate. Browser: the map and plan render as before (tested by unchanged tests, seen by one
> screenshot). Commit with a conventional message, `changelog.d/YYYYMMDD-tide-card-c1-data.md`, C1's
> Phase-log row (flip §0 to in-progress) and any §4 additions in the same commit. Do not push; report
> the branch and the commit.

---

## C2 · The two marks on the card

> You are implementing **Phase C2** of `docs/engineering/tide-plan-card-plan.md`. Read the plan **in
> full** and its Phase log (C1 has merged — confirm `card.tide`, `card.bestReach.tideAligned` and
> `tideRun` on `main`), then the spec §1–§5 and the notes column (both `cut` notes), then `gh pr list
> --state open` (C3 may be open in parallel — it touches `WindowSheetDialog`/`WindowAttributeRow`, not
> the card), then re-verify every symbol C2 names against the tree. Read
> `docs/engineering/frontend-test-standards.md`. Never push, never tag. Create
> `feature/tide-card-c2-marks` off up-to-date `main`.
>
> Scope is §3 C2's five tasks: the wave (`components/map/TideWave.jsx`, no props beyond `className`)
> before the named spot when `bestReach.tideAligned === true`, with the preference-axis words
> (`— the tide is right here`, `<state> — the water it wants`; **never** "on the light" — §4 #3), silent
> on a miss and on an inland head, name ink untouched; the chip as the **first element** of
> `.wf-hc-tps`, only when `card.tide.live`, `data-channel="tide"`, `<TideWave/> {matched} on tide`, with
> `data-best` and `best of {liveCount}` only on `tideRun.bestKey` and only when `liveCount > 1`; the
> tooltip per C2 #2 with "in reach" only when `card.reachMeasured`; CSS per C2 #3 beside `.wf-hc-tw` in
> `index.css` — emphasis is `--color-badge-tide` at 700 on the rgba(111,168,176) pill, **no `#B4DDE2`,
> no new token** (§4 #4); `.wf-hc-pls > .wf-hc-tps` stays selected through the parent. Do not touch the
> verdict, the histogram, the star chip, the border legend or the thumbnail; do not build the cut
> `Tide` border legend.
>
> Tests per C2 #4 including **§7 checks 1, 2, 4, 5, 8 and 10 as tests**, and the stylesheet-as-text
> suite pinning the new rules (the `@theme static` trap does not apply — no token is added). Gate on
> exit codes: `npm run lint && npm test && npm audit --audit-level=high && npm run build`. Adversarial
> review per CLAUDE.md (read-only; paste C2, §1 #2/#4/#5/#6/#11, §4 #3/#4/#5/#7, §5 and the spec's
> §1–§5). Fix survivors, re-gate, browser-verify per §9 in headless Chromium: the sunset row with three
> live windows and one emphasis; **§7 #7's two contrast figures** and **#6's overflow check** at
> 1280/834/390 in the Phase log as numbers. State seen versus tested. Commit with
> `changelog.d/YYYYMMDD-tide-card-c2-marks.md`, C2's Phase-log row and any §4 additions. Do not push.

---

## C3 · The popup fact, and the phone

> You are implementing **Phase C3** of `docs/engineering/tide-plan-card-plan.md`. Read the plan **in
> full** and its Phase log (C1 has merged; C2 may have), then the spec's working-order step 6 and
> `OPEN 4`, then `gh pr list --state open`, then re-verify every symbol C3 names against the tree
> (`WindowSheetDialog.jsx`'s tide row, `WindowAttributeRow.jsx`, `utils/windowFirstRows.js#tideFacts`).
> Read `docs/engineering/frontend-test-standards.md`. Never push, never tag. Create
> `feature/tide-card-c3-popup` off up-to-date `main`.
>
> Scope is §3 C3's three tasks: the tide row gains one trailing fact from `card.tide` — `9 of 14
> coastal locations in reach on tide`, "in reach" only when `card.reachMeasured`, omitted at zero
> coastal — passed to `WindowAttributeRow` as an `extraFacts` prop in `tideFacts`' own segment shape
> and **never** added inside `windowFirstRows.js` (§5 #6: that module maps served facts only);
> optionally `heightAtWindow` as a served fifth fact in `tideFacts` if the row has room at 390px
> (record it if added); the phone check for `OPEN 4` — at 390×844 with a four-topic night plus the
> emphasised chip (if C2 has merged), the topics line wraps to two lines and the pill is not clipped;
> measured line count and card height in the Phase log.
>
> Tests per C3 #3. Gate on exit codes: `npm run lint && npm test && npm audit --audit-level=high && npm
> run build`. Adversarial review per CLAUDE.md (read-only; paste C3, §1 #7/#11, §4 #6/#7, §5 #4/#6 and
> the spec's step 6). Fix survivors, re-gate, browser-verify per §9. Commit with
> `changelog.d/YYYYMMDD-tide-card-c3-popup.md`, C3's Phase-log row and any §4 additions. Do not push.

---

## C4 · Sweep and docs

> You are implementing **Phase C4** of `docs/engineering/tide-plan-card-plan.md`. Read the plan in full
> and its Phase log, then re-verify every cross-reference against `origin/main`. Never push, never tag.
> Create `docs/tide-card-c4-sweep` off up-to-date `main`.
>
> Scope is §3 C4: reconcile §4 against what shipped; flip §0 to complete; the CLAUDE.md edits per C4
> #2 — the Backend-heavy bullet's reach-scoped class gains `card.tide` and `tideRun` **and nothing
> else**, the Plan tab bullet gains one sentence on the two marks and the silence rule, the "Two tide
> axes" bullet notes the card glyph reads the preference axis; answer the spec's four OPENs in §6 with
> what shipped; record the §7 measurements. Docs-only, but the review cadence still applies (read-only
> agents; paste the whole plan). Commit with a `docs:` message and `changelog.d/YYYYMMDD-tide-card-c4-sweep.md`.
> Do not push.
