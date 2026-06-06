# `cached_evaluation` post-write clear — investigation & scoping

**Status:** Investigate + Scope (no build). Stop for review.
**Incident:** 2026-06-06 — Planner "0 of 42 cells". Log + DB evidence shows the
batch **wrote** the cache rows (21 keys across batches 187/188), yet those rows
are now **absent**, while the previous cycle's (5-June) rows survive.
**Scope:** What removes `cached_evaluation` rows. Separate from the Hot Topics
refactor and from the `BatchResultProcessor` all-or-nothing flush defect
(that one was refuted for this incident — the writes succeeded here).

---

## TL;DR — what the code can and cannot explain

I inventoried **every** path that can remove `cached_evaluation` rows and
reconciled each against the incident's defining fact: **6-June rows gone, 5-June
rows present.** The conclusion is uncomfortable but important:

> **No application code path in the current codebase can produce the observed
> deletion.** The two real delete paths are mutually exclusive with the evidence,
> the prior "stop briefing clearing" fix is intact (and never deleted DB rows in
> the first place), and prod is durable PostgreSQL with no automated wipe/restore
> on deploy. The "written-then-absent, reverted-to-5-June-state" signature points
> at the **data/persistence layer** (a restore / volume revert / out-of-band
> DELETE), not the briefing or batch code.

So the single most-important fact the brief asks for — *"what code path deleted
the rows"* — resolves to: **none of the current ones can.** That redirects the
fix from "stop the clear" (there is no code clear to stop) to "make any deletion
loud, audited, and impossible to do non-atomically" plus pulling the specific DB/
infra evidence that pins the actual mechanism (§2.4).

---

## Step 0 — Every path that deletes / empties `cached_evaluation`

There are exactly **three** ways the table's rows can be removed, and no others
(verified: no cascade, no native `DELETE`/`TRUNCATE`, no Flyway delete, no
scheduled prune of this table).

### 0.1 `clearCache()` → full `deleteAll()` — admin endpoint only

[`BriefingEvaluationService.clearCache()` :247-258](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java):

```java
@Transactional
public int clearCache() {
    int size = cache.size();
    cache.clear();                              // in-memory map
    long dbDeleted = cachedEvaluationRepository.count();
    cachedEvaluationRepository.deleteAll();     // <-- deletes EVERY row
    ...
}
```

Sole caller: the admin escape-hatch endpoint
[`BriefingEvaluationController.clearCache()` :77-79](../../backend/src/main/java/com/gregochr/goldenhour/controller/BriefingEvaluationController.java)
(`DELETE /api/briefing/evaluate/cache`, `@PreAuthorize("hasRole('ADMIN')")`).
Confirmed via grep — `clearCache` has no other caller anywhere in `main`.

**Reconciliation with the evidence:** `deleteAll()` removes **all** rows → **zero
survivors**. The incident shows 5-June rows *surviving*. **Incompatible.** Even
if this endpoint were somehow triggered, it cannot leave the 5-June rows behind.

### 0.2 `deleteByEvaluationDateBefore(date)` — declared but unwired

[`CachedEvaluationRepository.deleteByEvaluationDateBefore` :38](../../backend/src/main/java/com/gregochr/goldenhour/repository/CachedEvaluationRepository.java)
("housekeeping"). Grep for callers: **zero** in `main` (and none in tests that
run against prod). It is dead code today.

**Reconciliation:** if it ever *were* wired as `deleteByEvaluationDateBefore(today)`,
it would delete rows with `evaluation_date < today` — i.e. delete the **older**
5-June-dated rows and **keep** the newer 6-June ones. That is the **exact
opposite** of the observed selectivity. **Incompatible**, and unwired regardless.

### 0.3 In-memory `cache.clear()` (no DB effect)

`clearCache()` also clears the `ConcurrentHashMap`, and historically
`onBriefingRefreshed` did too (see §1). This only affects the in-memory read
cache; it never touches the `cached_evaluation` table. On its own it cannot
explain absent DB rows (a restart rehydrates the map from the table —
[`rehydrateCacheOnStartup` :266-315](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)).

### 0.4 What is NOT a delete path (ruled out)

- **No cascade / FK / `@SQLDelete`.** `CachedEvaluationEntity` is a standalone
  `@Table(name="cached_evaluation")` with no relationships — a parent delete
  cannot cascade into it.
- **No native `DELETE FROM cached_evaluation` / `TRUNCATE`** anywhere in `main`.
- **No Flyway migration** deletes/drops/truncates the table.
- **No scheduled prune** of this table. The two cleanup services target other
  tables: `RefreshTokenCleanupService` (refresh tokens) and
  `ForecastDispositionCleanupService` (`disposition_cleanup` job — dispositions,
  not the cache).
- **`writeFromBatch` / `persistToDb` never delete.** They upsert by `cache_key`
  (`findByCacheKey().orElseGet(new)`), replacing a single key's row in place.

---

## Step 1 — Reconciliation with the prior "stop briefing clearing" fix

The prior work is commit
**`d92a19e` "fix: stop briefing refresh from clearing evaluation cache"**
(2026-04-19 08:26). What it changed:

- `onBriefingRefreshed` previously called `clearCache()` on every briefing
  refresh; the fix made it a **no-op that retains** the cache (logs
  `"Briefing refreshed — evaluation cache retained"`).
- It added the admin `DELETE /cache` escape hatch.

**Current state — intact, not regressed.** The live
[`onBriefingRefreshed` :324-327](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingEvaluationService.java)
is the retain version. `git log -S "clearCache()"` on the file shows only the
original SSE commit and `d92a19e` — it has **not** been re-wired to clear since.
`BriefingService` still publishes `BriefingRefreshedEvent`
([`:344`](../../backend/src/main/java/com/gregochr/goldenhour/service/BriefingService.java)),
but the only listener retains.

**Crucial subtlety that reframes the whole hypothesis:** at the time of `d92a19e`,
`clearCache()` was **in-memory only** (`cache.clear()` + log) — see the diff;
there was **no `deleteAll()`**. The DB `deleteAll()` was introduced *later the
same day* by **`36514ad` "feat: durable evaluation cache — persist to DB and
rehydrate on startup"** (2026-04-19 23:18), by which point `onBriefingRefreshed`
already retained. Therefore:

> **At no point in git history did a briefing refresh delete `cached_evaluation`
> DB rows.** The bug the prior fix addressed was an *in-memory* wipe (which a
> restart would rehydrate away). The DB-row deletion seen on 6-June has **no
> historical code antecedent in the briefing path** to regress from.

**Verdict on the three options the brief poses:**
- *Regressed?* No — the guard is present and unchanged.
- *Incomplete?* Not in a way that touches DB rows — the briefing path never
  deleted DB rows.
- *Different path?* The only DB-deleting path is the admin endpoint (full wipe),
  which is incompatible with the selectivity. So the 6-June clear did **not**
  come from the briefing/cache code at all.

---

## Step 2 — The 6-June sequence, reconciled against all evidence

### 2.1 Established timeline

| Time (UTC) | Event | Source |
|---|---|---|
| ~14:06 | Batches 187/188 submitted | `forecast_batch` |
| 14:07–14:09 | Results streamed, parsed, **persisted** — "213 succeeded, 0 errored, 15 cache keys written" + "17 succeeded, 6 keys written"; `BriefingEvaluationService` logged each key persisted | app logs |
| 14:11 | Briefing narrative calls hit the half-open `anthropic` breaker (53 rejections) → best-bet/gloss failed **soft** (empty prose). **Reads cache; never deletes it.** | app logs |
| now | `cached_evaluation` has **no** 6-June rows; newest are 5-June 14:11 & 01:12 | live DB |

### 2.2 The breaker is not the deleter (but check the link the brief asked about)

The 14:11 breaker rejections came from `BriefingGlossService.generateGlosses` and
`BriefingBestBetAdvisor.advise`. Both **read** the cache (`getCachedScores`) and
both **catch every exception** and return a degraded result
([`advise` :475-478](../../backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BriefingBestBetAdvisor.java)).
**Neither calls any delete or clear.** There is no "clear-on-failure" path: a
breaker-failed narrative step cannot trigger a cache deletion, an empty-overwrite,
or a `BriefingRefreshedEvent`-driven clear (that listener retains). So the two
symptoms (empty prose, empty cells) are **not** tied together by a clear-on-
failure path — the empty prose is the breaker; the empty cells are a separate
data-layer event.

### 2.3 Why neither code delete path fits the selectivity

This is the heart of the finding. "6-June gone, 5-June present" requires removing
the **newer** rows while keeping the **older** ones:

- `deleteAll()` → removes everything → no 5-June survivors. ✗
- `deleteByEvaluationDateBefore(today)` → removes older (`< today`) → would remove
  the 5-June survivors. ✗ (and unwired)
- No predicate `deleteByEvaluationDateAfter`/`…GreaterThan` exists.

**No combination of the in-repo delete code produces "keep old, drop new."** The
only operation that yields "table contains everything up to the 5-June cycle and
nothing after" is **reverting the database to its 5-June state** — a restore /
point-in-time recovery / volume-snapshot rollback — or an **out-of-band manual
`DELETE`** run directly against Postgres.

### 2.4 Persistence layer: prod is durable Postgres, with no auto-restore

- **Prod datasource is PostgreSQL**, not H2:
  `jdbc:postgresql://goldenhour-db:5432/goldenhour`
  ([`application-prod.yml:6`](../../backend/src/main/resources/application-prod.yml)).
  (CLAUDE.md's "H2 volume-mounted" describes an earlier/local setup and is stale
  for prod.) A `save()` that logged success committed via autocommit and is
  durable in the WAL — it is **not** silently lost on a normal restart. The
  per-key `persistToDb` INFO log fires *after* `repository.save(...)` returns, so
  the rows were genuinely committed.
- **No automated wipe/restore on deploy.** `deploy.sh`/`release.sh` contain no DB
  operations; the Postgres data is a persistent host volume
  (`/Users/gregochr/goldenhour-data/postgres` →
  [`docker-compose.yml`](../../docker-compose.yml)); `ddl-auto: validate` (no
  schema drop). `backup-postgres.sh` is a read-only `pg_dump`; **restore is a
  documented manual step** (`psql < goldenhour_<date>.sql`).

**So the data layer does not revert itself on its own.** Whatever reverted the
DB to a 5-June state was an explicit action (a manual restore, a volume/snapshot
rollback, a direct `DELETE`, or the app transiently pointing at a different DB
instance), none of which is in application code.

### 2.5 The remaining hypotheses that DO fit — and how to tell them apart

Because the code is exonerated, the next step is targeted DB/infra evidence:

| Hypothesis | Fits "keep 5-June, drop 6-June"? | Distinguishing evidence to pull |
|---|---|---|
| **Postgres restore / PITR to a 5-June snapshot** | Yes | Postgres logs around 6-June for `pg_restore`/recovery; `backup.log`; the restore command history. Check `pg_stat_file`/`pg_controldata` last-checkpoint vs 6-June. |
| **Volume/snapshot rollback** of `/Users/gregochr/goldenhour-data/postgres` | Yes | Filesystem/Time-Machine snapshot history; container recreate events; `docker inspect` mounts on 6-June. |
| **Out-of-band manual `DELETE`** (e.g. `DELETE … WHERE evaluation_date >= '2026-06-06'` or by `updated_at`) | Yes (if predicate targets new rows) | Postgres `log_statement` / audit; shell history on the host. |
| **App pointed at a different/empty DB on 6-June** then back | Partially | App startup logs: datasource URL, `[EVAL HYDRATE] Loaded N entries` count on each 6-June boot; whether rehydrate loaded the 6-June rows it had just written. |
| **Admin `DELETE /cache` hit** | **No** (wipes 5-June too) | Access logs for `DELETE /api/briefing/evaluate/cache`; the `"Briefing evaluation cache cleared"` INFO log. Rules in/out quickly. |
| **A prod build differing from `main`** with a real clear path | Unknown | The exact image tag/commit running on 6-June vs `main`; diff the deployed `BriefingEvaluationService`. |

The first action is cheap and decisive: **grep the 6-June app log for
`"Briefing evaluation cache cleared"` and `[EVAL HYDRATE] Loaded`, and the
Postgres log for restore/recovery and any `DELETE` on `cached_evaluation`.** That
single sweep separates "app deleted it" (should be one of our INFO lines) from
"the DB was reverted underneath the app" (no app log; Postgres recovery/restore
entries).

---

## Step 3 — Scoped fix

The brief's principle still stands and is worth enforcing regardless of which
Step-2.5 hypothesis lands: **scored cache rows must never be removed except as an
atomic replace by fresh rows, and every removal must be loud and attributable.**
Because the current code has no offending clear, the work is **hardening +
observability + pulling the evidence**, not "reorder a clear-then-write."

### 3.1 Make every deletion loud and audited (primary, cheap, do regardless)

- Upgrade the `clearCache()` log to **WARN** with the row count, the caller
  principal, and a reason, so an admin wipe is unmistakable and a *silent*
  disappearance (no such log) positively indicates a data-layer cause.
- Add a periodic/boot **cache health heartbeat**: log `count(*)` and
  `max(updated_at)` for `cached_evaluation` on each briefing run and at startup.
  A **backwards jump** in `max(updated_at)` (e.g. 6-June → 5-June) is the direct
  signature of a restore/rollback and would have flagged this within one cycle.
- Optionally, a lightweight DB audit trigger (or `log_statement='mod'` scoped) so
  any `DELETE`/`TRUNCATE` on the table is captured server-side.

### 3.2 Remove the unwired footgun

`deleteByEvaluationDateBefore` is dead code that, if ever wired as
`…Before(today)`, deletes current/near-horizon rows. **Delete it**, or rename and
guard it so it can only target genuinely-past `evaluation_date` (e.g.
`< today.minusDays(N)`) and logs what it removed.

### 3.3 Harden the admin clear

The one real DB-wiping path (`DELETE /cache`) is a full `deleteAll()` with no
confirmation. Add a required confirmation token/param and the WARN audit (§3.1).
This does not fit the 6-June selectivity, but it is the only in-code way to lose
all scored data and should not be a single unguarded click.

### 3.4 If (and only if) Step 2.5 shows a prod-only code clear

Then apply the atomic-replace principle to *that* path: never `delete`-then-write
as two steps where the write can be skipped/failed; upsert in place (which
`persistToDb` already does), or wrap clear+write in one transaction that rolls
back the clear if the write does not complete. **Do not build this speculatively**
— confirm the path exists in the running build first (§2.4), since `main` has
none.

### 3.5 Persistence-layer recommendations (ops, flagged for the owner)

- Audit the **restore/backup runbook**: ensure no deploy/restart step can copy a
  stale dump or revert the Postgres volume. Document that restore is destructive
  and manual.
- Confirm the app always binds the **same** Postgres instance/volume across
  restarts (log the resolved datasource URL at boot; alert on change).

---

## Step 4 — Verification plan

1. **Audit/guard regression tests.**
   - `clearCache()` emits the WARN audit with the row count; `DELETE /cache`
     stays ADMIN-only (extend the existing
     `BriefingEvaluationControllerTest` 403/401 cases) and now requires the
     confirmation token.
   - The unwired prune is removed (or, if kept, a test asserts it cannot match
     `evaluation_date >= today`).
2. **Durability / survive-restart test.** `writeFromBatch(key, results)` →
   simulate restart → `rehydrateCacheOnStartup` → assert the rows and ratings are
   read back intact. This locks in that a written row survives a normal lifecycle
   (the property prod Postgres should already guarantee), so any future
   divergence is infra, not code.
3. **No clear-on-failure test.** Drive a briefing refresh whose `advise`/
   `generateGlosses` Claude calls throw (breaker open) and assert
   `cached_evaluation` row count is **unchanged** — encodes that a narrative
   failure can never remove scored rows.
4. **Empirical invariants (operational guards).**
   - On any cycle with `forecast_run_disposition.EVALUATED > 0` **and** a
     `"cache keys written"` log, `cached_evaluation` must still contain those rows
     after the briefing run. (Carried over from the prior scoping doc.)
   - **`max(updated_at)` of `cached_evaluation` must never move backwards**
     between consecutive briefing runs. The 6-June regression is exactly a
     backwards jump (6-June writes → table at 5-June). The §3.1 heartbeat makes
     this alertable.
5. **The decisive evidence sweep (do first, before any build):** the 6-June app
   log for `"Briefing evaluation cache cleared"` / `[EVAL HYDRATE] Loaded N`, plus
   Postgres logs for restore/recovery and any `DELETE` on `cached_evaluation`
   (§2.4/§2.5). This is what actually identifies the mechanism.

---

## Single most important fact

**No code path in the current tree deleted the 6-June rows in a way consistent
with the evidence.** The only DB-deleting code is the admin full-wipe
(`deleteAll()`, which would also remove the surviving 5-June rows) and an unwired
date-prune (which would remove the *older* 5-June rows, not the newer 6-June ones);
the prior "stop briefing clearing" fix is intact and never deleted DB rows at all.
Prod is durable PostgreSQL with no automated wipe/restore. The "written-then-
absent, table sitting at the 5-June state" signature is a **persistence-layer
revert** (restore / volume rollback / out-of-band `DELETE`), not the briefing or
batch code. The fix is therefore to (a) make every deletion loud, audited, and
non-backwards-detectable, (b) remove the unwired prune footgun and guard the admin
wipe, and (c) pull the §2.4 DB/infra evidence to pin the actual revert — **not**
to "stop a briefing clear" that does not exist in the code.
