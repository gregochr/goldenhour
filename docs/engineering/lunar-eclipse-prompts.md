# Lunar eclipse — kickoff prompts for the implementing sessions

Paste one prompt into a **fresh Claude Code session (Sonnet)**, in order (L6 may run first or in
parallel with L0–L2). Each phase lands as its own reviewed commit before the next session starts —
the plan's **Status line and phase table are the source of truth between sessions**, updated in the
same commit as each phase, so a new session never needs this chat's history. If a session dies
mid-phase, start a new one with the same prompt: step one of every prompt is reading the current
state from the repo.

**Status vocabulary** (the phase table's `state` column): the implementing session sets its own row
to `built, in review` in its commit — it never pushes, so it can never truthfully write "merged";
the **owner** flips the row to `merged` when merging. A downstream session finding the predecessor's
row at `built, in review` (or `not started`) stops and says so; finding `merged`, it branches **off
up-to-date `main`**.

The plan: `docs/engineering/lunar-eclipse-plan.md`. The design bundle: `docs/design/lunar-eclipse/`
(vendored verbatim — do not edit it; the plan's §1 wins where the bundle's README is stale against
this repo, and its §4/§6 record every deliberate disagreement).

**Multi-agent note.** These sessions need no special mode. The one multi-agent step is the
pre-commit adversarial review, which each prompt instructs explicitly — plain parallel subagents
(the Agent tool). Review agents are **read-only**; anything that must mutate gets its own worktree;
commit or stash before a review that runs mutations (CLAUDE.md § *UI Work — Review Cadence* records
why, including the destroyed-work incident).

**Every phase, without being reminded:** read CLAUDE.md and the side's test standards
(`docs/engineering/test-improvement-standards.md` backend, `docs/engineering/frontend-test-standards.md`
frontend) before writing code; never push, never tag; gate on exit codes, never on what output
appears to say; backend gate `cd backend && ./mvnw clean verify --batch-mode --no-transfer-progress
-Dtest='!**/integration/**' -DfailIfNoSpecifiedTests=false >/tmp/v.log 2>&1; echo "exit: $?"`
(there is no Docker on this machine and never will be); frontend gate `cd frontend && npm run lint
&& npm test && npm audit --audit-level=high && npm run build`; a `changelog.d/YYYYMMDD-<slug>.md`
entry per phase, never `CHANGELOG.md`'s `[Unreleased]` directly.

**Between phases (owner):** review in the browser, merge, push.

---

## L0 · Catalogue + calculator (backend)

> You are implementing **Phase L0** of `docs/engineering/lunar-eclipse-plan.md`. Read the plan in
> full first — its §1 reconciliation table and §2.2/§2.3 are load-bearing and cited line numbers
> may have drifted, so re-verify every citation before editing. Confirm the plan's Status says no
> phase has started, then create `feature/lunar-l0-catalogue` off up-to-date `main`.
>
> Scope is §2.2 and §2.3 exactly, nothing more — no wiring into any live path this phase: a static
> `LunarEclipseCatalog` (same shape as `EclipseCatalog` — read it first — but a different record:
> UTC contacts `P1 U1 U2? max U3? U4 P4`, `umbralMagnitude`, `Kind {PARTIAL, TOTAL}`,
> `nextComparable`), a `LunarEclipseCalculator` producing the `LunarEclipseSight` record per
> location from solar-utils' `LunarCalculator` and `MoonriseMoonsetCalculator` beans (see
> `SupermoonHotTopicStrategy` for the existing call pattern), and the eligibility rule verbatim.
> Seed **only umbral** eclipses from March 2025 through 2030 whose umbral phase is above the horizon
> somewhere in the British Isles, from NASA's decade tables — the plan's §2.2 candidate list is
> **from memory and must be verified row by row** before a row is seeded; record the source per row
> in the class javadoc as `EclipseCatalog` does. Write the §2.2 verification tests (full-moon
> illumination check per entry, contact ordering, kind ⇔ magnitude, the Dunstanburgh 2026-08-28
> figures within tolerance, the 2028-12-31 rises-in-shadow check) and the §3 L0 boundary tests on
> the 3°/30-minute rule and the midnight-crossing moonset. JaCoCo is 80% per class: cover the
> defensive branches with assertions rather than deleting them.
>
> Run the backend gate, then the adversarial review per CLAUDE.md § *UI Work — Review Cadence*
> (prosecutor lenses over the diff — correctness of the astronomy against published figures, test
> quality, project conventions; one refuter per charge defaulting to REFUTED; synthesis; agents
> read-only), fix survivors, re-run the gate. Commit conventionally with a `changelog.d/` entry and
> the plan's Status line + L0 row updated **in the same commit**. Do not push.

---

## L1 · Topic, almanac source, scoring (backend)

> You are implementing **Phase L1** of `docs/engineering/lunar-eclipse-plan.md`. Read the plan in
> full, re-verify its citations, confirm L0's row says `merged` (stop and say so if not), then
> create `feature/lunar-l1-topic` off up-to-date `main`. If `feature/lunar-l6-plain-copy` has
> merged, note it: you share `ComingUpAssembler.java` with it (different methods).
>
> Scope is §2.4 exactly: `LunarEclipseHotTopicStrategy` and `LunarEclipseAlmanacSource` mirroring
> `EclipseHotTopicStrategy`/`EclipseAlmanacSource` structurally (read both first, and
> `AlmanacSource`'s contract — answer for the whole range or do not exist, no network); the type is
> `LUNAR_ECLIPSE` / `lunar-eclipse` with Coming-up family `eclipse` (plan §2.1 and §4 #1 — do not
> add a `body` field); `TopicRarity` rank 2 with the shift; `ComingUpAssembler`'s constant, case and
> `enrichLunarEclipse`; `ComingUpScoringProperties.lunarEclipseMeanGapDays = 900.0` plus the
> `application-example.yml` row; the new nullable `ComingUpEntry.aside`; the `HotTopicSimulationService`
> template (no safety note — `HotTopicSimulationServiceTest` pins that only ECLIPSE warns, keep it
> true); and both eclipse strategies added to `HotTopicStrategyRegistrationTest`. The exposure note
> rides `note`, never `safetyNote` (§4 #5). `date` is the London civil date of maximum; `eventType`
> by the London hour of maximum exactly as the solar strategy does; withdraw on `U4`.
>
> Tests per §3 L1 with a 2026-08-28 fixture and a stubbed clock. **Run
> `ComingUpAnnualBadgeCensusTest` and quote its result in your final message** — the expectation is
> unchanged (the census year holds only penumbral lunar eclipses, which are not seeded); if it moves,
> that is a re-census: stop, do not edit the expectation, and report.
>
> Backend gate, adversarial review (read-only agents; lenses: correctness, date/timezone handling,
> test quality, conventions), fix survivors, re-gate. Commit with a `changelog.d/` entry and the
> plan's Status + L1 row updated in the same commit. Do not push.

---

## L2 · The slot sight (backend)

> You are implementing **Phase L2** of `docs/engineering/lunar-eclipse-plan.md`. Read the plan in
> full, re-verify citations, confirm L1's row says `merged`, then create `feature/lunar-l2-sight`
> off up-to-date `main`.
>
> Scope is §2.5 exactly: `BriefingSlot.eclipse` — a nullable `@JsonInclude(NON_NULL)`
> `EclipseSight` record (NOT `@JsonUnwrapped`; the plan says why) carrying the moon's altitude and
> bearing at maximum, the umbral span, moonset/moonrise, `setsInShadow`/`risesInShadow`, `race`
> (`DAWN`/`DUSK`/null by the §2.5 rule) and the four `LightStop`s keyed with `MastheadLight`'s
> `RULE_COLOURS` keys — all times London-local `LocalDateTime`, the slot's `solarEventTime`
> convention. Attach it at `BriefingSlotBuilder`'s `TideInfo` seam only on the eclipse's own window
> of its day. Add the simulation parity the plan describes, gated on `isSimulated()` like the
> aurora simulation, documented in the template's javadoc as a verification affordance. Do not use
> an array for the stops list (the equality-comparand rule in CLAUDE.md's hot-topics bullet).
> Nothing frontend this phase.
>
> Tests per §3 L2, including a legacy-cache-row deserialisation test and a wire-format check
> through the real Spring context (read `JsonDateFormatContractTest` and CLAUDE.md's
> two-Jackson-graphs warning first — a hand-built mapper proves nothing).
>
> Backend gate, adversarial review (read-only; lenses: correctness, cache/back-compat, serialisation,
> test quality), fix survivors, re-gate. Commit with a `changelog.d/` entry and the plan's Status +
> L2 row updated in the same commit. Do not push.

---

## L3 · Registries + the clocked chip (frontend)

> You are implementing **Phase L3** of `docs/engineering/lunar-eclipse-plan.md`. Read the plan in
> full, re-verify citations, confirm L2's row says `merged`, then create `feature/lunar-l3-chip`
> off up-to-date `main`.
>
> Scope is §2.1's client registries and §2.6 exactly: `badgeChannel` (exact-match arm beside
> `ECLIPSE`, before the substring arms — its existing test already expects `LUNAR_ECLIPSE_TIDE` to
> stay `tide`), `WHOLE_SKY_TOPIC_TYPES`, `CHANNEL_ICON`, `SWATCH_COLOR`, `TYPE_GLYPHS`
> (`'lunar-eclipse': '🌘'`), the hand-copied type list in `test/windowFirstTopics.test.js` (now 17,
> matching `TopicRarity`), `CLOCKED_TOPIC_TYPES = ['ECLIPSE','LUNAR_ECLIPSE']` + `chipClock`, the
> `.wf-hc-clocked` chip render in `WindowFirstHeatStrip` with the divider and the emphasised shape
> mirroring `.wf-hc-tide[data-best]`, the sr-only sentence, and the `Badge` javadoc amendment on the
> backend. A `SUPERMOON` badge with an `eventTime` must render **no** clock. Tailwind tokens live in
> `@theme static` or they prune to the empty string — check `getComputedStyle` in the browser, not
> the class list.
>
> Frontend gate, then browser verification per the plan's §7 (local backend on 8083, seeded roster,
> the L1 simulation template; sign in as `admin`/`golden2026`) — screenshot the card at desktop and
> 390px and say which claims were seen versus tested. Adversarial review (read-only; lenses: runtime
> behaviour, CSS/tokens, test quality, accessibility, conventions), fix survivors, re-gate. Commit
> with a `changelog.d/` entry and the plan's Status + L3 row updated in the same commit. Do not push.

---

## L4 · Popup: aside, tip, the dawn race (frontend)

> You are implementing **Phase L4** of `docs/engineering/lunar-eclipse-plan.md`. Read the plan in
> full, re-verify citations, confirm L3's row says `merged`, then create `feature/lunar-l4-race`
> off up-to-date `main`. Read `docs/design/lunar-eclipse/README.md` §4–§5 and `Lunar Eclipse.html`
> §04 for the exact values, colours and copy; the plan's §2.7 and §4 #8–#12 say where this codebase
> diverges and why.
>
> Scope is §2.7 exactly: the generic `badge.note` aside renderer in `WindowTopicRows` (full-width,
> the `.wf-trow-warn` shape; this reveals the solar note too — say so in the changelog); the `(i)`
> already mounts from `description`; the pure `utils/dawnRace.js` `raceModel(sight)` (track start
> floored to the hour, end clipped, umbra band to `min(umbraEnd, moonset)`, hatch after moonset only
> when it sets in shadow, positions in `[0,1]`, DUSK mirror, gradient through the exported
> `buildRuleGradient` from `components/shared/MastheadLight.jsx`); the `DawnRace` component
> (`aria-hidden` track, one accessible sentence from the same model — never hide it); the mount in
> `WindowSheetDialog` between topic rows and the tide row, only with a `LUNAR_ECLIPSE` row **and** a
> served `race` on the chosen spot's slot (best-reachable spot, else first ranked; caption names it;
> follow an existing spot-strip selection callback if one exists, add none); `buildEclipseIndex` in
> `utils/locationSheet.js` reusing `lookupForWindow`. Phone (< 560px) hides the track-start and
> sunrise labels and the phase-tick labels. No dismiss control, no storage. No footer change.
>
> Tests per §3 L4 — geometry to the boundary, mount conditions, `stopPropagation` on the tip, the
> accessible sentence, phone labels. Frontend gate; browser verification per §7 at desktop and 390px
> with screenshots, stating seen versus tested. Adversarial review (read-only; six lenses: runtime,
> CSS/tokens, test quality, accessibility, conventions, what it makes harder later), fix survivors,
> re-gate. Commit with a `changelog.d/` entry and the plan's Status + L4 row updated in the same
> commit. Do not push.

---

## L5 · Coming up row (frontend)

> You are implementing **Phase L5** of `docs/engineering/lunar-eclipse-plan.md`. Read the plan in
> full, re-verify citations, confirm L4's row says `merged` (and note whether L6 has), then create
> `feature/lunar-l5-coming-up` off up-to-date `main`. Read `docs/design/lunar-eclipse/README.md` §1
> and `Lunar Eclipse.html` §01.
>
> Scope is §2.8 exactly: `buildEntryView` passes the served `aside`; `WindowComingUpEntry` renders
> it as the dashed-top slot after the facts and before the threshold, generically (no lunar branch);
> the `next` line already arrives as an accent facts row from L1; `TYPE_GLYPHS` was L3's. Keep the
> accessible name honest — bare `' '` text-node siblings between the new section and its neighbours
> (read the P3a lesson in `CHANGELOG.md` under "Coming up P3a" before touching the JSX). Verify with
> a Vitest fixture built from a real served lunar entry (run `LunarEclipseAlmanacSourceTest` at a
> stubbed 2026-06-01 and copy its output shape), and in the browser only by a **temporary, never
> committed** local clock stub — state which claims were seen versus tested.
>
> Frontend gate, adversarial review (read-only; lenses: runtime, CSS, test quality, accessibility,
> conventions), fix survivors, re-gate. Commit with a `changelog.d/` entry and the plan's Status +
> L5 row updated in the same commit. Do not push.

---

## L6 · Plain-language copy (backend + frontend, independent)

> You are implementing **Phase L6** of `docs/engineering/lunar-eclipse-plan.md`. It depends on no
> other phase. Read the plan's §1 row 8 and §2.9, and `docs/design/lunar-eclipse/README.md` §6 with
> `Coming Up.html` (the copy; the notes beneath its frame still explain the model in bits — that is
> for engineering, not UI). Create `feature/lunar-l6-plain-copy` off up-to-date `main`.
>
> Scope: every user-facing surprisal string, on both sides, replaced by the design's plain language
> — backend `ComingUpAssembler.mergeEntries` (`joinNote`), `markScoreNotes` (the two sentences, with
> a tabled `gapWord` from the entry's mean gap), `ComingUpConditionsBuilder.rarityWord` (a
> frequency phrase) and the occurrence `reason`; frontend `WindowComingUpSinceLine` (the two banner
> shapes), `WindowComingUpConditions` (peak line, occurrence word, "figures are provisional"),
> `utils/comingUpConditions.js` (`bitsWord` → the three-word scale), the dust-row fact and the
> coincidence card's "Counted as one event, not two." **Scoring logic is unchanged**: every `bits`
> value assertion in `ComingUpAssemblerTest` and `ComingUpAnnualBadgeCensusTest` must stay green
> untouched; only strings and their tests move. Exit check: `grep -rn "bits"` over
> `frontend/src/components` and the two backend classes finds no user-facing string.
>
> Both gates, adversarial review (read-only; lenses: copy fidelity against the bundle, correctness
> of the frequency/gap wording at its boundaries, test quality, accessibility), fix survivors,
> re-gate. Commit with a `changelog.d/` entry and the plan's Status + L6 row updated in the same
> commit. Do not push.

---

## L7 · Sheet + callout line, docs sweep

> You are implementing **Phase L7** of `docs/engineering/lunar-eclipse-plan.md`. Read the plan in
> full, confirm L0–L6 all say `merged`, then create `feature/lunar-l7-sweep` off up-to-date `main`.
>
> Scope is §3 L7 and §8: the per-location line in `LocationFourDaySheet` and `MapCallout` as a
> sibling block after `TideFitBlock`, fed from `slot.eclipse` through L4's index (`◑ moon 7° up WSW
> at max · sets 06:16 in shadow` — no horizon claim, §4 #3), with tests; then the docs sweep —
> `CLAUDE.md` (Almanac source count, a Lunar eclipse bullet under What's Built, the Backend-heavy
> bullet's one-line note that `dawnRace.js` is filter/map/select over served instants like
> `comingUpSparkline.js`, not a new licensed class) and this plan's §4 closed out against what
> actually shipped, in the tide-plan C4 manner: read the code back against every entry, add any
> disagreement found only now, and mark §6's decisions taken or still open. Status → COMPLETE.
>
> Frontend gate, browser check of the sheet and callout under the simulation, adversarial review
> (read-only; lenses: runtime, accessibility, docs accuracy against the tree), fix survivors,
> re-gate. Commit with a `changelog.d/` entry. Do not push.
