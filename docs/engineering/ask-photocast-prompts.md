# Ask PhotoCast — kickoff prompts for the implementing sessions

One prompt per phase of `docs/engineering/ask-photocast-plan.md`. Each is pasted verbatim into a
fresh **Sonnet** session started in its own worktree off up-to-date `main`. Phases run strictly in
the order of the plan's §0. The implementing session never pushes; pushing, the PR and the merge
happen only on the owner's explicit instruction (plan §8).

**Rules every prompt carries, and why:**

- *Gates in the foreground.* Run every Maven/npm gate as a foreground Bash call with an explicit
  `timeout`, redirected to a log, and print `exit: $?` as its own statement. Never gate on a grep
  of Maven's output. Never wait on a background-task notification.
- *No Docker here.* Every backend gate carries `-Dtest='!**/integration/**'
  -DfailIfNoSpecifiedTests=false`. A migration is *pending CI*; write its Testcontainers test and
  say so in the report.
- *Review agents are read-only.* A reviewer that mutates the tree to probe a test deletes unstaged
  work. Give any mutating probe `isolation: 'worktree'`, and never restore with `git checkout --`.
- *Browser step has a budget.* Forty minutes. The session cannot sign in; if the owner is not
  there to sign in, report which claims were tested and which were seen, and stop.
- *Never push, never tag, never `POST /api/forecast/run`, never turn `photocast.ask.stub` off
  locally* (it bills the real Anthropic key).
- *Re-verify every file, line number and symbol against the tree before editing.* The plan was
  written 2026-10-05 at `4c8b3fbe`.
- *The plan's §6 defaults are binding.* Do not guess and do not ask; if a default is impossible,
  stop and report why.
- *Do not edit the plan's §0 table.* The orchestrator updates it at merge.
- *One commit:* the code and a `changelog.d/YYYYMMDD-ask-<phase>-<slug>.md` entry.
- *Read the test standards for your side first:* `docs/engineering/test-improvement-standards.md`
  (backend) or `docs/engineering/frontend-test-standards.md` (frontend).

**The common opening** (referred to below as *[OPENING]*):

> Read `docs/engineering/ask-photocast-plan.md` **in full**, then `docs/design/ask-photocast/README.md`
> and `COST_PLAN.md`, then CLAUDE.md's sections named in this prompt. Run `gh pr list --state open`
> and `git worktree list`, and grep the titles for *ask* — stop and report if anything overlaps.
> Confirm every earlier phase in the plan's §0 is merged to `main`; if not, stop. Re-verify every
> file and symbol this phase names. Never push, never create or delete tags.

---

## B1 · Read model, tools, contract, validator

> You are implementing **Phase B1** of the Ask PhotoCast plan. *[OPENING]* CLAUDE.md: *Plan-screen
> confidence* (the `VerdictSampleGate` material), *Two tide axes*, *Where a rating lives*,
> *Code Standards*. Binding plan sections: §1 #5, #6; §2.1, §2.2, §2.3's *Validation* list, §2.9's
> Java records; §3 B1; §5 D-2, D-3, D-5. Branch `feature/ask-b1-read-model` in a worktree.
>
> Scope is §3 B1's file list and **nothing else**: no Claude call, no controller, no migration, no
> Spring wiring beyond what a `@Service` needs. Build `AskSnapshot` from
> `BriefingService.getCachedBriefingForApi()` only. The window set is exactly §2.1's (non-null
> `window()`, not passed by the shared `PlanWindowProjector.hasPassed`, not a travel day — find how
> a travel day is marked on the served briefing and report what you found). A slot is pick-eligible
> exactly per §2.2; there is no rating that exempts an ineligible region and no fallback that admits
> a canopy slot. Extract the window-id codec and make `BriefingRollupBuilder` use it.
>
> Tests are §3 B1's list, each one named there is required — in particular the 2026-09-29 shape and
> the wood-beside-a-headland case. Minimise `any()` and `lenient()`. Gate:
> `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress -Dtest='!**/integration/**'
> -DfailIfNoSpecifiedTests=false >/tmp/ask-b1.log 2>&1; echo "exit: $?"`. Commit. Report the branch,
> the commit, the travel-day finding, and anything in §2.2 the code would not let you build.

## B2a · The engine

> You are implementing **Phase B2a**. *[OPENING]* CLAUDE.md: *Resilience*, *Cost tracking*, the
> Sonnet 5.5 warning under *Command pattern*, *Code Standards*. Binding: §1 #4, #22; §2.3 in full;
> §2.9 (config keys, records); §3 B2a; §5 D-1, D-9, D-10, D-11. Branch `feature/ask-b2a-engine`.
>
> Before writing the loop, inspect the Anthropic SDK 2.60.0 jar in `~/.m2` with `javap` and confirm
> the tool-use types and builder methods §1 #4 lists; use the real signatures, not memory. The
> reply is the `submit_answer` tool call's input. `tool_choice` is auto. Add
> `AnthropicApiClient.createAskMessage(params, RequestOptions)` on **new** Resilience4j instances
> named `ask` (retry: 2 attempts, 5xx/529 only, no content-filter retry; its own circuit breaker;
> bulkhead 4 concurrent, 2s wait) declared in `application-local.yml`, `application-example.yml`,
> `application-prod.yml` and `src/test/resources/application.yml`. Do not touch the existing
> `anthropic` instances or `createMessage`. The model is `photocast.ask.model` (HAIKU | SONNET
> only); add nothing to `model_selection` or `CONFIGURABLE_RUN_TYPES`. Cost recording is §2.3's
> *Cost* list exactly: two run types, the per-UK-day `ASK` run under an in-JVM lock, the
> column-scoped cost increment, the spend sum over `ASK` runs since UK midnight.
>
> Tests per §3 B2a with the SDK mocked at `AnthropicApiClient`; the concurrent-first-question test
> runs its second request on another thread. Gate as B1 (`/tmp/ask-b2a.log`). Commit. Report the
> SDK class names you used and any place the plan's loop could not be built as written.

## B2b · Stub, fixture, dry-run

> You are implementing **Phase B2b**. *[OPENING]* Binding: §2.3 (flag, stub), §2.9 (admin
> endpoints), §3 B2b, §9. Branch `feature/ask-b2b-stub-fixture`.
>
> `StubAskEngine` answers from the B1 tools with templated text and makes no client call.
> `AskLocalFixtureSeeder` runs only when the `local` profile is active **and**
> `photocast.ask.seed-local-fixture=true`; it must refuse otherwise. It writes rated entries
> through the same service the batch pipeline uses for `cached_evaluation` (find it; do not write
> the table by hand) for named local locations across the next two windows — at least two coastal,
> one canopy, one in a region left ineligible — then builds a briefing. Idempotent. The dry-run
> endpoint is ADMIN and returns the outcome plus the tool trace.
>
> After the gate, start the local backend per §9 with the fixture on and confirm with `curl` (as
> the `admin` local user) that `GET /api/briefing` carries the seeded ratings and that a dry-run on
> the stub returns picks. Report what you saw. Commit.

## B3 · Ready answers

> You are implementing **Phase B3**. *[OPENING]* CLAUDE.md: the *Job metrics* bullet's account of
> RUNNING runs and `RESETS_ONLY`, *Hot topics are recomputed LIVE*, the HTTP-caching note under
> *Briefing* API, *Database Migrations*. Binding: §1 #1, #2, #21; §2.4 in full; §2.9
> (`GET /api/ask/ready`, the precompute endpoint); §3 B3; §5 D-5, D-12. Branch
> `feature/ask-b3-ready`.
>
> Read the latest migration number from the tree (`ls backend/src/main/resources/db/migration/ |
> sort -V | tail -1`). Precompute is dispatched **after** `finishRun(runId)` on the background
> executor with its own 5-minute deadline; prove by test that the pipeline run is COMPLETED before
> it starts and unaffected by anything it throws. Freshness at serve time is all-or-nothing (§2.4):
> never serve a partial answer. A `BEST_*` answer that does not lead with the BEST pick's window is
> not stored. Add `/api/ask/ready` to `REVALIDATABLE_READ_PATHS` and pin Bearer-no-role across
> LITE/PRO/ADMIN and anonymous.
>
> Tests per §3 B3, plus the Testcontainers migration test (it will not run here; say so). Gate
> (`/tmp/ask-b3.log`). Then locally per §9: `POST /api/admin/ask/ready/precompute` on the stub and
> `GET /api/ask/ready?scope=all`. Commit. Report.

## B4 · The typed endpoint and its guards

> You are implementing **Phase B4**. *[OPENING]* CLAUDE.md: *User settings* (the
> `updatable = false` columns and the personal-data prefix), *Roles*, *daysAhead is measured from
> the UK civil date*. Binding: §1 #17, #18, #20, #23; §2.5 steps 1, 2, 4, 7, 8, 9, 10; §2.9's
> `POST /api/ask` contract, error table and `GET /api/user/settings/ask`; §3 B4; §6 Q4, Q6, Q7.
> Branch `feature/ask-b4-typed-endpoint`.
>
> Steps 3, 5, 6 and the log are B5's: put them behind interfaces with no-op implementations here.
> The order of §2.5 is binding — in particular the rate limit runs before the snapshot is built.
> `ask_usage` is find-or-insert with a unique-violation retry (local dev is H2: no `ON CONFLICT`),
> then **one** conditional update that takes `used` and `engine_calls` together. A refund targets
> the reserved date, never goes below zero, and never touches `engine_calls`. The day is the UK
> civil date through `ForecastHorizon` and the injected `Clock`. Every error is
> `{"error", "code"}` exactly as §2.9's table.
>
> Tests per §3 B4; concurrency tests use a second thread; the user-deletion test must pass. Gate
> (`/tmp/ask-b4.log`). Commit. Report.

## B5 · Pre-filter, intent match, cache, log

> You are implementing **Phase B5**. *[OPENING]* Binding: §1 #15, #16, #21; §2.5 steps 3, 5, 6 and
> the `ask_log` paragraph; §2.9 (metrics); §3 B5; §5 D-6, D-8, D-13. Branch
> `feature/ask-b5-prefilter-cache-log`.
>
> The pre-filter is whole-word phrases and runs before the matcher. The matcher is deterministic,
> conservative, and returns a Ready id only if that answer is available **and fresh** for the
> scope. The cache key is §2.5 step 6's exactly; a hit is re-checked with the same freshness rule
> B3 built (reuse it, do not copy it) and a failed check is a miss. Denied requests write no
> `ask_log` row. For the cleanup job's cron slot read every `scheduler_job_config` seed across the
> migrations — the comment in V151 is out of date.
>
> Tests per §3 B5, including the should / should-not phrase table. Gate (`/tmp/ask-b5.log`).
> Commit. Report.

---

## F1a · Client core, unmounted

> You are implementing **Phase F1a**. *[OPENING]* CLAUDE.md: *Backend-heavy*, *Role gating — UI
> pattern*, *Rewind* (under Admin features), *UI Work — Review Cadence*. Binding: §1 #5, #10, #19,
> #24; §2.6 (the conversation, the pick card model, live regions); §2.9 in full — its JSON is your
> fixture, copied literally; §3 F1a. Mocks: `Map Ask in Peek.html` (`askPane`, `.apk`, `.sug`,
> `.cant`, `.think` rules) and `Ask Everywhere.html` (`evCard`). Branch `feature/ask-f1a-client-core`.
>
> **Nothing is mounted in the app in this phase.** Build the API module, the two hooks, the
> provider (conversation state only — whether Ask is open is shell state, added in F1b), the pure
> `utils/askModel.js`, and the conversation with its cards, in the existing tokens
> (`--color-plex-*`, `--color-verdict-*`, `--color-badge-*`; there is no bare `--bg`). Drive on a
> card is the **home** reach map even when the provider's origin is away — find the home map the
> provider keeps and report if it does not keep one. Do not render "Plan this ›" (F5) or "Add to
> Coming up" (S1). Tailwind and `index.css` only, PropTypes on every component, `data-testid` on
> key elements.
>
> Tests per §3 F1a. Gate: `cd frontend && npm run lint && npm test && npm audit --audit-level=high
> && npm run build; echo "exit: $?"`. Then the adversarial review per CLAUDE.md (read-only
> reviewers; paste §2.6, §2.9 and §4 into each). Fix survivors, re-run the gate. Commit. Report.

## F1b · Phone and tablet-portrait entry

> You are implementing **Phase F1b**. *[OPENING]* CLAUDE.md: *Plan tab (the day × event MATRIX)* —
> the modal rules and `selectTab`; *UI Work — Review Cadence*. Binding: §1 #13; §2.6 (the surface
> table's first two rows, *Sheet*, *Dialogs*); §3 F1b; §4 #7. Mock: `Ask Everywhere.html`, Option B
> only. Branch `feature/ask-f1b-bar-and-sheet`.
>
> `BottomSheet` gains two caller opt-ins (`size="tall"`, `closeOnEscape`); prove every existing
> caller is unchanged. `askOpen` is `WindowFirstShell`'s own `useState` and joins `selectTab`'s
> closing list — `selectTab` may call only the shell's own setters. Below 640px: the 48px ask bar
> on Plan and Coming up only (the phone Map gets its entry in F4; do not add one). From 640 to
> 1023px: `AskField` at 260px **beside** the tablist, never inside it. The sheet's height follows
> `window.visualViewport`; the input is 16px. "Show on map ›" closes the sheet and switches tab;
> the markers arrive in F3.
>
> Tests per §3 F1b. Gate as F1a. Adversarial review. Browser per §9 at 390 × 844 and 800 × 1100;
> the iOS keyboard check in the Simulator if available — otherwise report it as not seen. Commit.
> Report seen versus tested.

## F2 · Desktop

> You are implementing **Phase F2**. *[OPENING]* CLAUDE.md: *Plan tab (the day × event MATRIX)* in
> full (search is Plan-only; the modal routes), the O-17 comment at the shell's panel wrapper, *UI
> Work — Review Cadence*. Binding: §1 #8, #11; §2.6 (*Dock*, *The field*, *Dialogs*, *Escape*,
> *Outside press*); §3 F2; §5 D-4; §6 Q1 and Q3. Mock: `Ask on Desktop and iPad.html` — the
> **desktop** frame only; the iPad floating card is deferred. Branch `feature/ask-f2-dock`.
>
> The dock is a sibling of the **whole shell column**, not of the pane and not inside the panel
> wrapper. `/` moves to Ask per Q1's default: re-point every test that uses `/` as the way into
> search at the ⌕ button, and keep the search shortcut's refusals for Ask. A refusal is asserted on
> `defaultPrevented` and against a control that has rendered — a lazy component that never mounted
> makes "opens nothing" vacuous. Opening Ask never closes a dialog; while one is open the dock and
> field are `inert`.
>
> Tests per §3 F2. Gate as F1a. Adversarial review. Browser at 1024, 1280 and 1600 wide: measure
> the masthead's and the panel's left edges (they must be equal) and confirm the dock's input stays
> in view while Plan scrolls. Commit. Report seen versus tested.

## F3 · Map linkage

> You are implementing **Phase F3**. *[OPENING]* CLAUDE.md: *Map tab (v2)*, *Map tab — the verdict,
> the picks and the landing card* (panels persist; a panel that replaces another must move focus),
> the *No marker clustering* warning, *UI Work — Review Cadence*. Binding: §1 #14; §2.7 except the
> phone paragraph and table; §3 F3. Branch `feature/ask-f3-map-linkage`.
>
> The window follow is its **own** App state and its own effect — do not send it through
> `mapTabHandoff`, whose effect applies the Plan lens and mounts the breadcrumb for every source
> that is not `'map'`. A pick chip is built from the pick's own window. Camera padding is measured
> and clamped to 60% of `map.getSize()` per axis; write the 400px-tall-frame test first and watch
> it fail on unclamped padding. The heat dim is a factor on the heat fill inside `MapHeatLayer`'s
> draw, with a repaint — not pane opacity.
>
> Tests per §3 F3. Gate as F1a. Adversarial review. Browser at 1280 on the fixture. Commit. Report.

## F4 · Phone Map

> You are implementing **Phase F4**. *[OPENING]* CLAUDE.md: *Map tab on a phone — the peek sheet*
> in full; `docs/engineering/map-mobile-sheet-plan.md` §5 (D-1, D-7); *UI Work — Review Cadence*.
> Binding: §1 #12; §2.7's phone paragraph and **state table**; §3 F4. Mock: `Map Ask in Peek.html`
> (the `.sh`, `.ar`, `.askp`, `.mini` rules and the `map.on('mousedown dragstart')` handler).
> Branch `feature/ask-f4-peek-ask`.
>
> Implement the state table cell by cell and write one test per cell. `'peek:ask'` means "expanded"
> only; whether an answer exists and whether it is minimised is Ask state. `--psh` becomes an
> inline custom property set from state; `MapCallout` gains `bandKey` so it repaints. Change every
> 74 literal in the phone block and every test that pins it, and prove desktop and tablet unchanged
> on the same fixture. When the ask row replaces the three buttons, focus must land somewhere
> deliberate — assert on `document.activeElement`, not on a key fired at the panel node.
>
> Gate as F1a. Adversarial review. Browser at 390 × 844 and 375 × 667 with measured rects of the
> sheet, the pill and a callout. Commit. Report seen versus tested.

## F5 · Plan this

> You are implementing **Phase F5**. *[OPENING]* CLAUDE.md: the `LocationFourDaySheet` passages
> under *Plan tab* and *Map tab (v2)* (the three sheet routes, `inPlan`), *UI Work — Review
> Cadence*. Binding: §1 #7, #9, #24; §2.8; §3 F5. Branch `feature/ask-f5-plan-this`.
>
> Export `lightWindows` from `utils/locationSheet.js` rather than copying it. The four figures must
> equal the location sheet's own output for the same fixture — test that directly. The note line is
> the slot's served summary. Do not render "Add to Coming up". The Plan-card highlight carries the
> pick's rank circle so it is not mistaken for the open card.
>
> Tests per §3 F5. Gate as F1a. Adversarial review. Browser on the fixture at 390 and 1280. Commit.
> Report.

## Z · Sweep

> You are implementing **Phase Z**. *[OPENING]* Binding: §3 Z, §7. Branch `chore/ask-z-sweep`.
>
> Update CLAUDE.md (What's Built, API Endpoints, the migrations table, the Backend-heavy bullet,
> the `/` key) and `application-example.yml`, then **fact-check your own sweep against the code** —
> a docs sweep is where errors become authoritative. Write `AskPromptRegressionTest` with three
> cases asserting structural invariants only, add it to PIT's `excludedTestClasses`, and **stop and
> show its assertions for the owner's approval before committing** — no later session may change
> them. Measure every row of §7 that can be measured locally and record the figures in the plan.
> Write the production enable checklist. Commit. Report.
