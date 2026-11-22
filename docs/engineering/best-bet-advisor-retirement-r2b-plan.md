# Best bet advisor retirement: R2b (schema cleanup, two releases)

Follows R1 (#1896), R2a (#1897) and R1.5 (#1901), all in v2.41.0, **deployed to production 2026-11-21**.
Owner decisions (2026-11-21): R2b waits for the deploy (done); **keep the `job_run` / `api_call_log` rows** of the
retired run type (cost history), so `RunType.BRIEFING_BEST_BET` stays as a deprecated history-only enum value.

## The rule that shapes the split

Production runs `ddl-auto: validate`. A migration may drop only what the *previous release image* no longer maps
(V196/V198/V200 comments). The previous image is 2.41.0. It maps `pipeline_run.best_bet_status` (entity field) and
`model_selection.extended_thinking` (entity field); it does **not** map `pipeline_run_pick` (no entity, no
repository: verify with grep). So:

### R2b-1 (this PR, next release)

Migration `V227__drop_pipeline_run_pick_and_retire_best_bet_model_row.sql` (re-read the latest number from the
tree first; it was V226 on 2026-11-21):
1. `DROP TABLE IF EXISTS pipeline_run_pick;` (also drops its index and `uq_pipeline_run_pick_rank`).
2. `DELETE FROM model_selection WHERE run_type = 'BRIEFING_BEST_BET';` (config row, not cost data). Verify first
   that nothing requires a row per `RunType` (`ModelSelectionService` startup/lookup, `ModelsController` list) and
   that a missing row is handled; if something throws on a missing row, say so and keep the delete out.
3. Do **not** touch `job_run`, `api_call_log`, `pipeline_run`, or the `extended_thinking` / `best_bet_status` columns.

Code in the same PR (the 2.41.0 image tolerates all of it because the columns stay):
- Delete `PipelineRunEntity.bestBetStatus` field, getter and setter, and `model/BestBetStatus.java`. The column stays
  in the DB (nullable, no default needed) until R2b-2.
- Delete `ModelSelectionEntity.extendedThinking` (field and accessors) if nothing else reads it (grep). The column is
  `NOT NULL DEFAULT false`, so inserts without it still work (V218 inserts it explicitly; later inserts via the
  entity then rely on the default). Confirm `ddl-auto: update` (local H2) and the Testcontainers/Flyway path both
  accept this.
- Keep `RunType.BRIEFING_BEST_BET` (owner kept the job_run rows) with its Javadoc updated to "history only: no code
  runs it; kept so `job_run` / `api_call_log` rows still load". Keep the `RunClassifier` and `RunDeepType` arms.
  Keep `LocalH2OldSchemaFixtures.OLD_RUN_TYPES` and `LocalH2EnumWidener`.
- Local H2: add `'BRIEFING_BEST_BET'` handling to `LocalH2TermRunTypeCleanup` **only for `model_selection`** (a stale
  local row must not fail load; do not delete `job_run` rows locally either) or confirm via its tests that the enum
  value still loading makes this unnecessary. Decide from the code; do not add what is not needed.
- Tests: `PipelineRunEntityTest.bestBetStatus_round_trips` deleted; `PipelineRunPickDedupeMigrationTest` must change
  `flywayTo(null)` to `flywayTo("148")` (and any sibling that migrates to latest then inserts into
  `pipeline_run_pick`: grep every `flywayTo(null)`/latest use); new Testcontainers test modelled on
  `RetireTermRunTypesMigrationTest` that migrates to V226, inserts a `pipeline_run_pick` row, a `model_selection`
  `BRIEFING_BEST_BET` row, a `job_run` `BRIEFING_BEST_BET` row, migrates to V227 and asserts: table gone, model row
  gone, **job_run row still present**. Update `ModelSelectionServiceTest` / `ModelsControllerTest` only where they
  reference `extendedThinking` or the deleted row.
- Docs: `pipeline.md` (line ~13 the run-type note), `platform.md` (~203-207), `ratings-stores.md` (~303),
  `database-migrations.md` (new notable-migration entry), the Javadoc in `RunType`, and the R2a plan's R2b section
  pointer. Changelog `changelog.d/20261121-retire-best-bet-advisor-r2b1.md` (`### Chore — …`).

### R2b-2 (DONE as V228 with `DropBestBetStatusAndExtendedThinkingMigrationTest`; merge only after the release containing R2b-1 is deployed)

`ALTER TABLE pipeline_run DROP COLUMN best_bet_status; ALTER TABLE model_selection DROP COLUMN extended_thinking;`
once the release containing R2b-1 is deployed. Written and built in its own PR after the owner says so.

## Gates

Backend build ladder (compile, targeted tests, checkstyle, full `verify` with the integration exclusion, no
concurrent Maven), `./mvnw -Plocal-dev test-compile` and `-Plocal-dev generate-test-sources checkstyle:check` for the
src/local and src/local-test roots, frontend untouched (verify by grep that nothing in `frontend/src` reads
`extendedThinking`; `modelsApi.js` already lost `setExtendedThinking`). Migrations and Testcontainers tests are proven
only in CI (no Docker locally).

## Review questions

1. Is `pipeline_run_pick` really unmapped in 2.41.0 (no entity, repository, native query, `@Query`, JPQL, `@Table`)?
2. Does anything fail on a missing `model_selection` row for `BRIEFING_BEST_BET` (service, controller, UI listing,
   `RunCostProfileService`, scheduler startup, the Ask rows)?
3. Is removing `ModelSelectionEntity.extendedThinking` safe on Postgres (NOT NULL DEFAULT) and H2 (`ddl-auto:
   update`), and does any JSON response, test fixture, or frontend field read it?
4. Which tests/fixtures run all migrations and then touch `pipeline_run_pick`, `best_bet_status` or
   `extended_thinking`?
5. Is anything else (SQL scripts, `measured-costs.sql`, admin queries, cost-profile reads) reading
   `pipeline_run_pick` or the model row?

## Review corrections (Opus review, 2026-11-21; these override the text above)

1. Migration: `DROP TABLE IF EXISTS pipeline_run_pick;` with **no CASCADE**, then the `model_selection` delete, nothing else. Re-check the latest number just before committing.
2. `PipelineRunPickDedupeMigrationTest`: both `flywayTo(null)` calls (~:119 and ~:181) become `flywayTo("148")`; fix the "latest migration" comment (~:118) and the class Javadoc (~:40).
3. New Testcontainers test (modelled on `RetireTermRunTypesMigrationTest`): migrate to "226"; insert a `pipeline_run` parent then a `pipeline_run_pick`; insert a `job_run` row with `run_type='BRIEFING_BEST_BET'` plus an `api_call_log` child; assert the V69 `BRIEFING_BEST_BET` model row exists; migrate to "227"; assert table gone, model row gone, `job_run` and `api_call_log` rows still present, other `model_selection` rows unchanged, `pipeline_run` intact. No model-row insert needed (V69 seeds it).
4. Delete `PipelineRunEntity.bestBetStatus` (field+Javadoc ~:56-65, accessors ~:210-216), `model/BestBetStatus.java`, and in `PipelineRunEntityTest` the import (:3) and the round-trip test (~:61-70).
5. Delete `ModelSelectionEntity.extendedThinking` (~:47-54). Do NOT touch `EvaluationModel.isExtendedThinking`, `BatchSummary.extendedThinking`, `BatchSummaryDeriver`, their tests or `frontend` `jobRunPanel.js` / `runsView.js` (a different field).
6. **Local H2 needs handling** (existing dev DB files have `extended_thinking boolean not null` with no default; after the field is removed any insert fails, e.g. choosing an Ask model in Run Config). In `src/local` `LocalH2TermRunTypeCleanup` (or a sibling `@Profile("local")` bean, same pattern: H2-only, idempotent, WARN on failure) run `ALTER TABLE model_selection DROP COLUMN IF EXISTS extended_thinking` when present. Test in `src/local-test` (`LocalH2TermRunTypeCleanupJpaTest` or new JpaTest): add the old NOT NULL column by hand, run the cleanup, save a new `ModelSelectionEntity`, assert it persists. Update the Javadoc. No `BRIEFING_BEST_BET` row deletion locally.
7. Keep `RunType.BRIEFING_BEST_BET`; rewrite its Javadoc (`RunType.java` ~:23-26): history only, no code runs it, kept so `job_run`/`api_call_log` rows load, model row removed by V227. Leave `RunClassifier` (~80-83,106) and `RunDeepType` (~70-73) arms.
8. Frontend gate: nothing under `frontend/src` reads `extendedThinking` from `/api/models`/`ModelSelectionView`; `batchSummary.extendedThinking` hits are a different field and stay. Frontend untouched.
9. Do not edit the persisted-briefing-key tests (`DailyBriefingResponseJsonTest`, `PersistedBriefingRemovedKeysTest`, `BriefingServiceTest` ~:663, `BriefingControllerTest` ~:198) or the `BRIEFING_BEST_BET` controller/service tests.
10. Gates: `./mvnw -Plocal-dev test-compile`, `-Plocal-dev generate-test-sources checkstyle:check`, full `verify` with the integration exclusion, no concurrent Maven. V227 and the migration tests are proven only in CI.
11. Docs: `database-migrations.md` (V227; columns dropped in R2b-2), `pipeline.md` ~:13, `platform.md` ~:203-207, `ratings-stores.md` ~:303, wherever `LocalH2TermRunTypeCleanup` is documented, changelog `changelog.d/20261121-retire-best-bet-advisor-r2b1.md`.
12. R2b-2 can drop both columns only because items 4 and 5 remove the mappings now.
