# Best bet advisor retirement — R2a (code-only dead-field removal)

Follows R1 (PR 1896, merged 2026-11-21), which stopped calling the advisor. R2a removes the now-dead
wire fields and types. R2b (a later PR, the release after R1 has been deployed) is the migration that drops
`pipeline_run_pick` and `pipeline_run.best_bet_status`. **R2a must contain no migration and must not touch
`PipelineRunEntity.bestBetStatus`'s column**: see "Why the split" below.

## Why the split

R2b is irreversible and breaks a rollback to the release before it (old code reads the table). R2a is
code only, so it ships safely with or after R1.

## Scope (all on `main`; verify each by grep before editing)

Remove:

1. `DailyBriefingResponse` components `bestBets`, `bestBetModel`, `bestBetStatus`, `bestBetsWithdrawn`,
   their withers (`withBestBets`, `withBestBetModel`, `withBestBetStatus`, …), the compact-constructor
   normalisation, and every convenience constructor that exists only to default them (the record has many
   overloads; collapse to the minimum the remaining callers need).
2. `BestBet` record, `BestBetStatus` enum (but see the entity constraint below).
3. `RunType.BRIEFING_BEST_BET` and its handling in `RunClassifier`, `RunDeepType`, `ModelSelectionService`,
   `RunCostProfileService` and the `ModelsController` (R1 already made `PUT /api/models/active` return 400
   for it; that guard goes with the value).
4. The two R1 neutralisation sites in `BriefingService` (startup restore, ~line 285) and
   `BriefingRoleShaper` (~line 63–72): they exist only to blank the fields being deleted.
5. Javadoc mentions in `BriefingController`, `BriefingRegion` (lines ~611, ~636), `BriefingRoleShaper`.
6. Test code that builds or asserts those fields (about 25 test files; list from
   `git grep -il 'bestBet\|BRIEFING_BEST_BET' origin/main -- backend/src/test frontend/src`).
7. Frontend: any reader of `briefing.bestBets` / `bestBetStatus` / `bestBetsWithdrawn`, and the
   `ModelSelectionView` entry for the run type; the e2e `roles.spec.js` assertions that mention best-bet
   fields.

**Keep (these are NOT the advisor and must not be touched):** the Map's `MapBestBetWhy.jsx`, `bestBetKey`
state and `.wf-bestbet-*` classes in `MapView.jsx` (they render a window's `pick`, served by
`PlanWindowProjector`); `AskTools.PickInfo bestBet`; `WindowFirstHeatStrip`'s upsell prefix
`wf-heat-bestbets-upsell` and `guide={{ section: 'bestBet' }}` (Pro gating of the window pick) unless the
agent proves they read a removed field.

## Constraints that make this non-trivial

- **Old cached JSON.** `daily_briefing_cache` rows written before this PR contain `bestBets`,
  `bestBetModel`, `bestBetStatus`, `bestBetsWithdrawn`. The record is read back with `readValue` on startup
  (`BriefingService`) and last-known-good paths. Removing components makes those unknown properties. The
  PR must prove, with a test that feeds a pre-R2a JSON blob (including a non-empty `bestBets` array and a
  `bestBetStatus` string) through the real mapper(s) used on the read path, that deserialisation succeeds
  (add `@JsonIgnoreProperties(ignoreUnknown = true)` on the record if the mapper is strict; determine which
  mapper first: `AppConfig.objectMapper()` is Jackson 2 and used internally; the HTTP mapper is Jackson 3).
- **Entity column stays.** `PipelineRunEntity.bestBetStatus` is `@Enumerated(STRING)` over
  `BestBetStatus`. Deleting the enum while the column exists makes Hibernate fail on any non-null stored value
  and the entity getter/setter dangle. R2a therefore either (a) keeps `BestBetStatus` and the entity field
  until R2b, or (b) changes the field to a `String` mapped to the same column with no enum. Choose (a):
  smallest, zero schema risk; `BestBetStatus` is deleted in R2b with the column. Say so in the Javadoc.
  Likewise `LocalH2EnumWidener` / `LocalH2OldSchemaFixtures` references stay until R2b.
- **`RunType` is `@Enumerated(STRING)` on stored rows** (`model_selection`, `pipeline_run.run_type`,
  `job_run`). A stored `BRIEFING_BEST_BET` row makes loading fail. R2a must therefore **not** delete the enum
  value unless every persisting entity is proven not to hold it. Default: **keep `RunType.BRIEFING_BEST_BET`
  in R2a** (mark `@Deprecated` with a Javadoc naming R2b) and delete it in R2b together with a migration
  that deletes the `model_selection` / historical rows. The guard in `ModelsController` stays. This overrides
  item 3 above: the agent applies the keep-it decision and removes only code that is dead without the enum
  removal.
- **Wire format.** Removing fields changes `GET /api/briefing` JSON for every role. Pinned by
  `JsonDateFormatContractTest` and `DailyBriefingResponseJsonTest`; update both to the new shape. Confirm the
  frontend never reads the fields (grep) so no client change is required.
- **ETag/HTTP caching:** payload shape change must not break `HttpCachingIntegrationTest` expectations.

## Gates

Backend build ladder (compile, targeted tests, checkstyle, full `verify` with the integration exclusion; no
concurrent Maven runs), frontend lint / `test:coverage` / audit / build, diff-cover 90%, changelog
`changelog.d/20261121-retire-best-bet-advisor-r2a.md` (`### Chore — …`), update the reference docs that
describe the fields (`api-endpoints.md`, `pipeline.md`, `plan-tab.md`, `roles-and-gating.md`).

## Review questions for the plan reviewer

1. Is every claim in "Keep" true (does any kept item actually read a removed field)?
2. Is the cached-JSON read path as described, and which mapper is it? Does it fail on unknown properties?
3. Is deleting nothing enum-typed in R2a (keeping `RunType.BRIEFING_BEST_BET`, `BestBetStatus`) sufficient to
   avoid every hydration failure, and are there other places that persist `BestBet`/`BestBetStatus` (JSON
   columns, `pipeline_run_pick`, snapshot tables)?
4. What else consumes `DailyBriefingResponse`'s removed constructors/withers (Ask, aurora, rewind, the
   simulation paths, `BriefingRegionSnapshot`)?
5. Anything in the frontend or e2e reading the removed fields, or any contract/Playwright fixture to update?

## Review corrections (Opus review, 2026-11-21; these override the text above)

1. **Compile-break site list.** The record has 17 components and overloads of arity 16, 15, 14, 13, 12, 11 and 10.
   Most positional `new DailyBriefingResponse(...)` callers never contain the word "bestBet". Build the
   list from `git grep -l 'new DailyBriefingResponse(' -- backend/src` across `src/test`, `src/local` and
   `src/local-test` (about 45 files, about 90 call sites), not from a bestBet grep.
2. **`-Plocal-dev` callers are invisible to `verify`.** `E2eLocalFixtureSeeder.java:~680` (src/local) and
   `E2eLocalFixtureSeederTest.java:~307` (src/local-test) compile only under `-Plocal-dev`. Gates add
   `./mvnw -Plocal-dev test-compile` and the E2E smoke recipe (`-Plocal-dev -Dmaven.test.skip=true package`).
3. **`roles.spec.js:~260`** asserts `lite.briefing.bestBets` equals `[]`; drop that assertion. Lines ~81-87 and
   ~281 are the window-pick upsell and stay.
4. **Do-not-touch list.** `AskPromptRegressionTest`, `AskThreadRegressionTest`, `AskWindowContextRegressionTest`
   and `prompt-regression/ask/*.json` use `bestBet` for the window/PickInfo pick (CLAUDE.md forbids changing
   regression assertions). Also leave `ModelsControllerTest`, `GlobalExceptionHandlerTest`,
   `AskJobRunRepositoryTest`, `RunClassifierTest`, `RunDeepTypeTest`, `PipelineRunEntityTest`,
   `LocalH2OldSchemaFixtures` and `LocalH2EnumWidener`: the enum values stay.
5. **Registries.** Update `ReviewedTypes.java` (DailyBriefingResponse component set; delete its `BestBet`
   entry) and `RecordWitherPreservationTest` (drop the three best-bet withers). Delete `BestBetTest.java`.
6. **Item 3 is void.** No `RunType` consumer changes in R2a. `ModelSelectionService`, `RunCostProfileService`
   and `ModelsController` have no explicit handling (the 400 is the generic `isConfigurable` check);
   `RunClassifier` and `RunDeepType` switch arms stay.
7. **No new annotation.** `DailyBriefingResponse` already has `@JsonIgnoreProperties(ignoreUnknown = true)`
   (the only `readValue` is the startup restore in `BriefingService`, Jackson 2). Extend the existing
   `PersistedBriefingRemovedKeysTest` with a blob carrying `bestBets: [{...}]`, `bestBetModel`,
   `bestBetStatus: "FAILED"` and `bestBetsWithdrawn: true`, and assert it loads. Also delete the R1
   neutralisation in `BriefingService` and `BriefingRoleShaper` only after that test passes.
8. **Overload rule.** The canonical record then has 13 components. Keep no reduced overload whose arity equals
   an old one (13, 12, 11 or 10), so any caller left unedited fails to compile instead of silently
   re-binding.
9. **Docs.** `api-endpoints.md`, `plan-tab.md` (three places), `roles-and-gating.md`, `pipeline.md` (the enum
   stays, so reword only), the Javadoc in `BriefingController`, `BriefingRoleShaper`, `BriefingRegion` and
   `DailyBriefingResponse`.
10. **Confirmed true:** the whole "Keep" list; `HttpCachingIntegrationTest.briefingPicksEtagDoesNotCrossRoles`
    still separates roles via `window.pick` once its `bestBets` assertions go; rollback to R1 code reading
    R2a JSON is safe (missing `bestBets` becomes `List.of()`).
