# API endpoints

> Moved verbatim from `CLAUDE.md` on 2026-10-22 to keep the always-loaded file small.
> Read the section you need; this file is not loaded automatically.

## API Endpoints

### Auth (no JWT required)
`POST /api/auth/login|refresh|register|resend-verification|verify-email|forgot-password|reset-password|set-password`

> `/login`'s `username` field takes a username OR an email address (the wire name is unchanged so existing clients work).
> `/forgot-password` `{email, turnstileToken?}` answers 200 `{"message": "If that email has an account, we've sent a reset
> link."}` for every well-formed address (400 for a blank, malformed or failed-CAPTCHA one); `/reset-password` `{token,
> password}` answers 200 with the `/login` body (the resetting device is signed in) or 400 with "This reset link is invalid" /
> "…has already been used" / "…has expired"; `/set-password` and `/reset-password` answer 429 "Too many attempts. Please wait
> a few minutes and try again." from their three limiter bounds. The emailed links are on the APP origin: `<app>/verify?token=`
> and `<app>/reset?token=` (`UserEmailService.appLink`); `/reset` opens the reset page even on a browser that is already signed
> in. `/api/auth/**` is `permitAll`, so an unauthenticated call arrives as the principal `anonymousUser`: `/change-password`
> and `/marketing-emails` refuse it (401) and `/register` refuses that username (409).
>
> `/register` also accepts an optional `inviteToken` (a waitlist invitation). A token that is live, unused and issued to
> the request's own address (compared case-insensitively) is redeemed in the registration's transaction and skips ONLY the
> early-access cap check: Turnstile still applies, and `photocast.registration.enabled=false` refuses everyone, invited or not.
> Any other token (unknown, expired, used, another address) is ignored and the ordinary cap applies, with the same 403 "Early
> access is currently full" (no leak). The waitlist row is deleted at activation (`/set-password`), by the account's stored
> address, not at register.

### Waitlist (public sign-up; ADMIN management)
`POST /api/waitlist` `{email, turnstileToken}` (public, no JWT) | `GET /api/admin/waitlist` | `POST /api/admin/waitlist/{id}/invite` | `DELETE /api/admin/waitlist/{id}`

> `POST /api/waitlist` answers 200 `{"message": "You're on the list"}` for a new address AND an already-listed one (idempotent,
> including a lost unique-key race); 400 for a missing or malformed address, or a Turnstile token that fails or is missing
> (strict: no fallback); **429** "Too many attempts. Please wait a few minutes and try again." from `AuthAttemptLimiter` (60
> an hour overall, 5 per client address per 15 minutes; a refused attempt is never a silent 200). The log carries the row id,
> never the address.
>
> `GET /api/admin/waitlist` returns `[{id, email, submittedAt, invitedAt, inviteExpiresAt}]`, oldest first; the invitation's
> token hash and used mark are never served. `POST .../{id}/invite` mints a random token, stores its SHA-256 hash with
> `invited_at` and a 7-day expiry (clearing `invite_used_at`; a re-invite replaces the earlier token) and mails
> `waitlist-invite-email` with `<app>/register#invite=<token>` (the fragment form, NOT `#token=`, which the app reads as a
> verification link): **200** `{message}`; **400** for a non-ASCII address (registration rejects those); **404** unknown id;
> **503** when no mail sender is configured (nothing is stored). `DELETE .../{id}`: **204**, or **404** unknown id. Both
> management calls are ADMIN only (403 for Lite and Pro, 401 unauthenticated).

### Auth (Bearer required)
`POST /api/auth/logout|change-password` | `PUT /api/auth/marketing-emails`

> The three event streams (`GET /api/status/stream`, `GET /api/forecast/run/notifications`, `GET /api/forecast/run/{id}/progress`)
> authenticate with the `Authorization: Bearer` header like every other call; the client streams with `fetch`. A `?token=` query
> parameter is not read (`JwtAuthenticationFilter.extractToken` is header-only), so such a request is 401.

### Forecast (Bearer)
`GET /api/forecast` | `GET /api/forecast?date=YYYY-MM-DD` | `GET /api/forecast/{id}`

> **`GET /api/forecast?date=`** (outcome-rating plan O2) is the Map's older-day fetch: ONE past day, the same array shape and the same
> rules as the list. The day must lie in T-4 to T-1 on the UK civil calendar (`ForecastHorizon.MAX_PAST_DAYS` = 4); anything else —
> today, the future, T-5, a malformed value — is a 400 `{"error": "date must be between T-4 and yesterday"}` (the parameter is read as a
> STRING, because a `LocalDate` conversion failure would fall into `GlobalExceptionHandler`'s catch-all as a 500). It also admits T-1 and
> T-2, which the list already carries; the client simply never asks for a day it holds. **Stored rows only** — nothing here reaches a
> run, a batch or a model. The path is still exactly `/api/forecast`, so `HttpCachingConfig`'s ETag allow-list needed no change
> (the query is not in the key; the body's hash separates days), and the body is shared, never personal.
>
> ⚠️ **A PASSED slot is served AS OF ITS EVENT**, on the list's past days (`SERVE_PAST_DAYS` = 2) and on `?date=` alike
> (`ForecastController.serveRange`; passed = `PlanWindowProjector.hasPassed` on THIS place's own event time, `now` from the injected
> clock; a place with no event time is unpassed). `AsOfEventResolver` supplies the row available by the event and unretracted at it
> (*Where a rating lives*), and for a PRO/ADMIN reader that row's `rating` is the resolver's captured star — always, so a canopy-bearing
> place whose composite star is not recoverable is served UNSCORED (no star, no scores, the stand-down reason kept) and never with the
> row's sky-only star; a served result with no row behind it is a sparse row rated by the captured star; nothing available is NO row, and
> every passed slot is marked covered so the sparse cache read cannot resurrect a post-event entry. A LITE reader keeps the row exactly as
> the list has always served LITE (`ForecastDtoMapper` shapes only the potentials, `summary` and detail `headline` by role, never `rating`). A SPARSE passed row has `id: null` (so it cannot be opened through `GET /api/forecast/{id}`, which is not as-of-event) and its `rating` is the captured star for EVERY role, LITE included; the answer for a slot can change between two reads (step 1 needs the CURRENT served result to have arrived by the event) and the list wins on a merge; the resolver's step-1 retraction is as of NOW, not as of the event. An UNPASSED slot (a future
> day, today before its event) is served as before. ⚠️ Array order is no longer by location: unpassed rows, passed rows, sparse passed rows,
> then cached-only rows — group by date and type. Consequences: for the days between V178 and V183 a window scored before V178 showed fewer stars or
> none (no row had a `scored_at`; V183 closed that gap), and `colourForecastDates` can drop a date whose slots are all absent; the list now runs one
> `resolveAll` per (date, type) over the passed places (about 5–6 queries each) where it ran about four queries in all.

> **Role-shaped (scores and written why Pro gate S2, `docs/engineering/pro-features-gate-plan.md` §6).** `GET /api/forecast`, `?date=` and `GET /api/forecast/{id}` serve PRO_USER and ADMIN the enhanced `fierySkyPotential`, `goldenHourPotential`, `summary` and (detail) `headline`, plus the detail tide heights `nextHighTideHeightMetres`, `nextLowTideHeightMetres` and the match `tideAligned` (Pro since 2026-10-26, shaped in `ForecastDtoMapper.toDto`; the list DTOs `ForecastListDto` carry no tide heights). Anyone else (LITE, and anything not positively PRO or ADMIN, fail-closed) gets all of them `null`, on rich rows, sparse cached-only rows (`toSparseListDto`, which carries `summary` inline for PRO) and passed sparse slots alike. `rating`, `triageReason`, `triageMessage`, `tideState`, the high/low-water times, `lunarTideType` and every `surge*` field are free for every role; `bluebellSummary` is untouched (a separate follow-up). The `basic_*` columns are never served. The ETag is hashed from the role-shaped body, so the variants never share a validator.

### Forecast backtesting (ADMIN)
`GET /api/forecast/history` | `GET /api/forecast/compare`

> **Both gained `@PreAuthorize ADMIN` on 2026-08-28** — until then they inherited `/api/**` →
> `.authenticated()`, so any LITE account could call them. Like `POST /api/locations` (fixed
> 2026-08-26), the *code* was the wrong side: both javadocs describe backtesting tools (history's
> span-cap javadoc literally says "the admin backtesting tool"), neither has ever had a frontend
> consumer, and history can pull up to 366 days of rows across every enabled location from the
> insert-only `forecast_evaluation` table. No data was leaking in the meantime — both already
> applied `ForecastDtoMapper`'s role-based score selection, which they keep as defence in depth.

### Forecast runs (ADMIN)
`POST /api/forecast/run` | `POST /api/forecast/run/very-short-term|short-term|long-term` | `POST /api/forecast/run/tide` | `POST /api/forecast/run/tide/backfill` | `POST /api/forecast/run/{runId}/retry-failed`

> **`POST /api/forecast/run/{runId}/retry-failed` re-runs exactly the slots that FAILED**, not the failed
> places over the failed dates over both events. `FailedSlotRetryService` reads the (location, date,
> sunrise/sunset) triples that are FAILED in the original run's progress-tracker entry and hands the
> executor that list (`ForecastCommand.slots`); it runs under the original run's own `run_type`, taken
> from its `job_run` row. ⚠️ That means the CURRENT model and strategy configuration of that run type:
> a hand-started run stores no `active_strategies` snapshot and a null `evaluation_model`, so what it ran
> under is not recoverable. Sentinel sampling is off for a retry (it would answer for unnamed slots with
> canned results); weather triage and tide alignment still apply and the stability filter stays bypassed.
> **409 `{error}`**, nothing started, for a run stopped on a rejected API key, a light-pollution run, or a
> run whose failed tasks are not forecast slots; **404** with no body when nothing is left to run
> (unknown or evicted run, no failures, or every failed slot left out). A failed slot that cannot be run
> (place disabled, renamed or deleted, no longer a sky location, dated before today's UK civil date) is
> listed in the 202's `skipped` with a reason; `slots` is how many the new run holds. The panel reads the
> same answer from the `run-complete` payload's `retryBlockedReason`. **Only a finished run, and only once:**
> **409 `{error}`** "This run is still going. Retry is offered when it has finished." while the original
> run has not completed, and "This run has already been retried as run N. Retry that run's failures
> instead." for a second retry of one run (a retry of the retry run is allowed: retries chain). The
> check, the start and the "retried as N" record happen under one per-run lock
> (`RunProgress.retryLock()`), so two concurrent requests start exactly one run. ⚠️ The record lives on
> the tracker's in-memory entry, so it does not survive a restart, and goes when the entry is evicted.
> A recorded retry that never ran does not block: if run N completed holding no task (it failed before
> registering any), or its entry has been gone for 5 minutes (`RETRY_REGISTRATION_GRACE`, so a retry that
> has not yet registered itself is not mistaken for a missing one), the original can be retried again; a
> run N that ran, even with its own failures, still takes the retry.
> **Eviction** (`RunProgressTracker.cleanupStaleEntries`, run every 5 minutes by the `run_progress_cleanup`
> job): only a COMPLETED run is evicted, 30 minutes from when it COMPLETED (the Retry window). A run that
> has not completed is never evicted, however long it has been silent (the weather and cloud prefetch
> records no progress and can wait a minute on each rate-limited chunk), so "still going" 409s for as long
> as the entry is unfinished; every hand-started run reaches `completeRun` or `failRun`, and a dead JVM
> takes the in-memory tracker with it, so there is nothing to leak.

> **`POST /api/forecast/run/tide` answers 409 while a tide refresh is running from any route** — the
> Monday `tide_refresh` schedule, the Scheduler's Run Now, or an earlier press. One `AtomicBoolean` in
> `ScheduledForecastService` guards all three, taken on the request thread before the refresh is
> handed to the executor, so there is no accepted-but-not-started gap. Two overlapping refreshes both
> paid WorldTides for the same window, and the slower one's inserts could collide on
> `uq_tide_extreme`. A refused schedule fire or Run Now is only logged — Run Now still shows
> "Triggered ✓", because `triggerNow` queues before the guard is consulted. The backfill is not guarded.

### Locations & Regions (Bearer / ADMIN for writes)
`GET /api/locations` | `POST /api/locations` (ADMIN; optional `subRegionId`, 400 unless it is a sub-region of the location's region) | `PUT /api/locations/{id}` (ADMIN; 404 unknown id; optional `subRegionId` moves the membership, a region change drops a mismatched one) | `PUT /api/locations/{id}/enabled` (ADMIN) | `PUT /api/locations/reset-failures?name=` (ADMIN; the path is `/reset-failures`, not `/{name}/reset-failures`) | `GET /api/locations/grid-cells` (ADMIN) | `GET /api/locations/enrich?lat=&lon=` (ADMIN) | `GET /api/locations/aliases` (ADMIN) | `PUT /api/locations/{id}/aliases` `{aliases[]}` (ADMIN; 404 unknown location, 400 missing `aliases` or bad alias shape, 409 name clash; `[]` clears; never via `PUT /api/locations/{id}`) | `GET /api/areas` (ADMIN; each area carries `kind` NAMED or SUB_REGION, `regionId`, `regionName`; a sub-region's `locationIds` come from `location_sub_region`) | `GET /api/areas/uncovered-locations` (ADMIN; enabled, regioned locations with no sub-region) | `POST /api/areas` `{name, aliases[], kind?, regionId?}` (ADMIN; 201, 400 bad shape or a sub-region without `regionId`, 409 clash) | `PUT /api/areas/{id}` `{name, aliases[]}` (ADMIN; keeps members; 400, 404, 409) | `PUT /api/areas/{id}/locations` `{locationIds[]}` (ADMIN; 400 missing list, unknown location id or a sub-region, 404; `[]` clears) | `DELETE /api/areas/{id}` (ADMIN; 204, 404) | `GET|POST /api/regions` | `PUT /api/regions/{id}` | `PUT /api/regions/{id}/enabled` | `PUT /api/regions/{id}/base` (ADMIN) | `GET /api/regions/drive-times`

> **`POST /api/locations` is ADMIN**, and was not until 2026-08-26 — it was the one mutation on
> `LocationController` carrying no `@PreAuthorize`, so `/api/**` → `.authenticated()` let any LITE
> account create one. Unlike the tide and almanac endpoints this file once mis-documented as ADMIN
> (where the doc was wrong and Bearer was the intent), here the *code* was wrong: every sibling
> mutation was gated, the admin UI already mounted the creation screen behind `isAdmin`, and
> creation has real side effects — `enabled` defaults true, so the row joins the global roster
> immediately, and a coastal one spends a billable WorldTides request from inside
> `LocationService.add`.

> **`GET /api/regions/drive-times`** is the shared region-base drive-time matrix — `{regionId:
> {locationId: minutes}}`, from each region's admin-entered base town to the whole roster. Bearer
> with **no role gate**, by inheritance from `SecurityConfig`'s `/api/**` → `.authenticated()`; the
> Plan tab's origin move is ungated for the pilot, so a role gate here would break it for every
> non-admin.
>
> ⚠️ **It is ETag-revalidated, and that is only safe because it is user-independent.** "How far is
> this from Keswick" is the same answer for every reader; "how far is this from your house" is not,
> and stays on the never-cached `/api/user/settings/reach` for the reason that section records. The
> two must not be merged on the client either — `planOrigin.originReachMap` builds the away map
> from this matrix **alone** and never borrows `distanceMiles`, because those miles are measured
> from home and would put two journeys in one line on a card whose leave-by time a reader acts on.
>
> **`PUT /api/regions/{id}/base`** sets or clears a region's base town, and is deliberately its own
> endpoint rather than three more fields on the rename: a rename body carrying only `name` would
> deserialise the base fields to null, so every rename would silently clear the base and discard
> that region's whole drive-time matrix. All three null clears it; a partial base is a 400. Moving
> the base **discards** that region's stored drive times rather than leaving figures that measure a
> journey from the old town — unknown is safe, wrong is not — and the nightly
> `region_drive_time_refresh` job refills them.

### Sub-region pick limit (ADMIN)
`GET /api/admin/sub-region-policy[?candidate=n]` (`{liteLimit, maxLimit, liteUsersOverLimit, candidateLimit, updatedAt, updatedBy}`; `maxLimit` is the number of sub-regions, at most 24; `liteUsersOverLimit` counts Lite readers holding more picks than `candidate`, or than the saved limit when none is asked about, so the Operations control can warn BEFORE a lowered value is saved) | `PUT /api/admin/sub-region-policy` `{liteLimit}` (400 unless 1..`maxLimit`; answers the same body with the count against the new limit; readers over it keep their picks until they next change).

### Users (ADMIN)
`GET|POST /api/users` (each summary carries `subRegions`, the names of the sub-regions the user has picked, `[]` for none) | `PUT /api/users/{id}/enabled|role|reset-password|email` | `POST /api/users/{id}/resend-verification` | `DELETE /api/users/{id}` | `GET /api/users/{id}/sub-regions` (the user's picks and standing, without options; 404 unknown user) | `PUT /api/users/{id}/sub-regions` `{areaIds[]}` (sets the picks past the change rule and clears the user's lock stamp, leaving the grace end; `[]` clears them; 400 for an id that is not a sub-region, a duplicate or more than the limit, 404 unknown user) | `DELETE /api/users/{id}/sub-regions/lock` (clears `sub_regions_changed_at` so the next change is free; 204, 404)

> `DELETE /api/users/{id}` takes the same row lock and the same `purgeUserRows` as self-service deletion (so a user holding
> drive times is deletable on Postgres — it was not before 2026-10-17), refuses to delete the calling admin (409), and sends the
> "account removed" email after commit to the stored address. Admin create and admin email edit refuse a non-ASCII address and
> a case-insensitive duplicate; admin create refuses a username containing `@` (it would be ambiguous at sign-in).

### Account (Bearer)
`DELETE /api/user/account` `{password}` — the signed-in reader deletes their own account (`AccountController`; not under `/settings`,
it is not a setting). Every role except ADMIN: an ADMIN is refused with 409 "Admin accounts are deleted by another admin" (checked
first, and again under the row lock for an account promoted mid-request) so the sole admin cannot lock the instance out. Statuses:
**204** deleted; **400** "Password is required"; **401** not authenticated, or "Your session has changed. Sign in again." when the
password or `credentials_version` moved while the password was being checked (nothing is deleted); **403** "Password is incorrect";
**409** as above; **429** after 5 attempts per 15 minutes, counted BEFORE the BCrypt comparison (`AuthAttemptLimiter`, key
`delete:<username>`). The delete is a compare-and-set on `(id, password hash, credentials_version, role <> ADMIN)` decided by its
row count; `user_drive_time` is purged first (V67 has no `ON DELETE`); `refresh_token`, `email_verification_token`,
`password_reset_token`, `ask_usage` and `outcome_rating` cascade; `ask_log` keeps its rows with `user_id` set null until the
90-day prune; the email claim is released if no other account uses the address. The access token dies with the row (the filter).
Proven on Postgres only in CI (`AccountDeletionIntegrationTest`).

### Outcomes (Bearer)
`GET /api/outcome/ratings?from=&to=` | `PUT /api/outcome/ratings` (PRO_USER/ADMIN) | `GET /api/outcome/ratings/all?locationId=&date=&eventType=` (ADMIN)

> The legacy free-text stack (`GET|POST /api/outcome`, `GET /api/outcome/all`, `actual_outcome`) was deleted in code (`docs/engineering/audit-followups-plan.md` §O1); its table was empty and is dropped by a later migration (O2). `docs/engineering/v1-retirement-plan.md` §8 has the history.
>
> **`PUT /api/outcome/ratings`** `{locationId, eventDate, eventType, rating}` records — or replaces: upsert-only, one row per (user, location, date, type) — the caller's rating of a window that has HAPPENED. `rating` is 1–5, or absent/null for **"didn't see it"**; ⚠️ **not seen is `rating: null`** on the wire and in the table (a row's existence is the answer, there is no separate flag), the gate skips it and the Map's count and badge include it. 400 `{error}` for a missing slot, an `eventType` other than SUNRISE/SUNSET, a rating outside 1–5 (those three are checked BEFORE any lookup, so they answer 400 even for an unknown location), an event that has not happened (THIS place's own event time plus the 30-minute afterglow, on the real clock — a PUT is never rewound) and an event older than **`OutcomeRatingService.MAX_RATING_AGE_DAYS` = 24** (the disposition retention of 30 days less the 5-day candidate window less one day of slack for the instant-based prune: the stability-skip evidence the provenance needs is pruned 30 days after it was RECORDED, which is up to five days before its event, so an older date could resolve a retracted forecast as served); 404 for an unknown location; 403 for LITE. The provenance (`forecastRunAt`, `pipelineRunId`, `forecastEvaluationId`, the captured `forecastRating`) is resolved by `AsOfEventResolver` the FIRST time and **kept on a re-rate** when that first record held a `forecast_run_at` (a row recorded with no forecast at all is resolved afresh) — a restart empties the past-date cache the resolver's first step reads, so resolving again could only degrade a full record; the converse is that a rating FIRST recorded after a restart has a null `pipelineRunId` and, for a canopy-bearing place, a null `forecastRating`, and is then never a calibration pair. The first insert is a find-or-insert outside any transaction (`OutcomeRatingStore`, with a unique-violation retry, because local H2 has no `ON CONFLICT`). `GET …/ratings?from=&to=` is the caller's OWN rows, oldest first, no role gate (400 for `from` after `to` or a span over 31 days); `GET …/ratings/all` is every reader's row for one slot, newest first, `mine` marked on the caller's, ADMIN only, no age bound. All three are personal data under `/api/outcome/ratings`, never ETag'd (`HttpCachingConfigTest.personalDataPathsAreNeverFiltered`). **The optional `seen` tag** (V187, valley-mist-plan M3): `COLOUR | MIST | INVERSION | FLAT | NOT_SEEN`, never required, independent of the star, three states on the wire - ⚠️ **absent keeps the earlier tag, an explicit `"seen": null` clears it, a name sets it** (`SeenChoice`, deserialised by `SeenChoiceDeserializer`, which overrides `getAbsentValue` because Jackson's default reads an absent property as the null value). Two 400s, checked before any lookup: `NOT_SEEN` beside a star, and a sighting tag beside "didn't see it" (a "didn't see it" record may carry `NOT_SEEN` or nothing). One further rule on KEEP: an earlier tag the new rating contradicts (`NOT_SEEN` replaced by a star, a sighting replaced by "didn't see it") is dropped rather than kept, since it described the answer being replaced; V187's second CHECK states the same pairing. The tag is served (omitted when null) on `GET …/ratings` and `GET …/ratings/all`, and on the PUT response; the same PRO/ADMIN gate as the rating, so a LITE tag is a 403. ⚠️ **An existing row is never read-modified-written (Codex review of #1273, the user-settings lost-update rule): the request picks ONE column-scoped statement** (`OutcomeRatingStore.Write`) - no `seen` sent: `updateRating`, the star and its provenance/build, with the pairing rule IN THE SQL (`seen = CASE …` drops a sighting beside "didn't see it" and NOT_SEEN beside a star, keeps anything else); `seen` sent with a star: `updateSeen`, the tag and `updated_at` ONLY, so a tag tap can never carry a stale star back (and does not restamp `app_build`); `seen` sent with "didn't see it": `updateRow`, rating and tag together in the one statement (they are one answer). The response is the row as STORED (re-read after an update). If a tag-only update loses to a star that changed underneath (another device) and the CHECK refuses it, the answer is **409** `{"error": "The rating changed — reload and try again"}`. The provenance rule is unchanged. The record is the calibration gate's second input — see *Calibration gate*.

### Admin tools (ADMIN)
`GET /api/metrics/job-runs|api-calls` | `GET|PUT /api/models` | `PUT /api/models/active|optimisation` | `POST /api/model-test/run|run-location|rerun` | `GET /api/model-test/runs|results` | `POST /api/prompt-test/run|replay` | `GET /api/prompt-test/runs|runs/{id}|results|git-info` | `GET /api/admin/calibration?from=&to=`

### Cloud verification (ADMIN)
`GET /api/admin/cloud-verification` | `POST /api/admin/cloud-verification/backfill` | `GET /api/admin/cloud-verification/backfill/status` — scores past forecasts against **ERA5 reanalysis**, needing no recorded outcome. The legacy `actual_outcome` store was empty (zero rows ever) and is retired; the calibration gate scores readers' `outcome_rating` ratings only. But every threshold the scoring rules turn on is a *cloud* claim, and cloud is machine-checkable. ⚠️ **Reanalysis is not observation.** ERA5 is a model reconstruction that assimilates observations unavailable when the forecast was issued — genuinely independent information, but still a model field, not a measurement. Where ERA5 and the forecast model share a bias (low stratocumulus, marine layers, orographic cloud) the comparison flatters the forecast. Read a disagreement as *"the forecast differs from a better-informed model"*, not *"the forecast was wrong"*. It says nothing at all about whether a sunset was beautiful — that still needs recorded outcomes. **Five archive points per evaluation** (3 solar-cone bearings + observer + 226 km far-solar), answering the two claims a forecast makes plus two questions about the sampling geometry itself: the *gap* (low cloud at the 113 km solar horizon — did the low sun get through?), the *canvas* (mid/high overhead at the observer — was there a screen to light up?), the *cone structure* (`horizon_low_min`/`max`, V142 — the forecast persists only the cone **mean**, which renders a uniform 60% deck and a 90/0/90 wall-with-gap identically), and the *far corridor* (`far_low_cloud`, V142 — the canvas-underlighting corridor the 113 km gate never reads: 226 km = √(2R·4 km) is the exact centre of a 4 km **mid** canvas's blocking corridor and only the near *edge* of an 8 km cirrus one, whose centre is ~319 km — physics corrected 2026-08-13, see the veto doc §8; also verifies the forecast's own `far_solar_low_cloud` claim for the first time). Since V175 the far point's ERA5 mid and high, and its low/mid/high at the dark-side hour, are stored beside it (`far_mid_cloud`, `far_high_cloud`, `far_*_cloud_dark`) for far-cloud-lid-plan P1 — recorded only, read by no report yet, and deliberately **not** in `deleteIncompleteVerifications`, so the history is not re-walked. The observer point's 2 m temperature and dew point at the event hour (`observer_temp_c`, `observer_dew_point_c`, V187, valley-mist-plan M3) are stored the same way: `ARCHIVE_HOURLY` now carries `temperature_2m,dew_point_2m` beside the three cloud layers (five variables, inside Open-Meteo's ten-variable weight unit, so no more of the daily budget), they are read at the same `findBestIndex` slot as the observer layers, and they are recorded only - not in `deleteIncompleteVerifications` (`CloudVerificationRepositoryTest` pins that a row with both null is not deleted), no backfill, no report. Report splits: `vetoFired`/`vetoNotFired` (`gapActuallyOpen` counts clear-horizon skies the veto suppressed), **`vetoUncapped`/`vetoCapped`** (below `MAX_UPWIND_DISTANCE_M` the upwind sample is a real advection nowcast; at the cap it is not — this is the D7 question), `byWindSunAngle`, **`byConeStructure`** (uniform/mixed/gapped by ERA5 cone spread — does gap error grow where the mean hides structure?) and **`byCorridor`** (near-vs-far divergence ≥30pp, the strip-vs-blanket threshold, with both `&midCanvas` and `&highCanvas` sub-buckets — `farCloudier&*` is the false-optimism case no current scoring rule covers. The **mid** cut is the direct one: 226 km is the geometric centre of a 4 km mid canvas's blocking corridor, where for an 8 km cirrus canvas it is only the near edge of one centred ~319 km, so `&highCanvas` is synoptic-scale proxy evidence pending a sixth archive point). The new statistics are reanalysis-internal, so the baseline offset cancels. Backfill is **async** (`202` immediately, `409` if already running) and works the backlog in committed batches of 100 — a synchronous pass cannot finish inside a proxy timeout. It is **self-healing**: rows missing any observation the current sampling REQUIRES are cleared at the start of each run (the V175 far mid/high and dark-hour columns are recorded, not required, so they never trigger a re-walk) (so the first run after a sampling change deliberately re-verifies the whole history — that is the measurement pass), and a batch that returns no observations at all stops the run (`lastError`) rather than blanking the remaining backlog — those two rules only work as a pair. Gated on the archive's ~6-day lag; reads the same `findBestIndex` slot the forecast scored. See `docs/engineering/cloud-approach-veto-fix.md`.

### Calibration gate (ADMIN)
`GET /api/admin/calibration` — forecast accuracy vs **readers' recorded ratings**. The only non-self-referential accuracy measure in the project: prompt-regression tests compare Claude to hand-written expectations, the sky-rating eval harness to fixtures, and model-comparison to other models — all can stay green while forecasts drift from reality. `ForecastCalibrationService` joins `forecast_evaluation` to each reader's `outcome_rating` (on the resolved row id, below), keeps the newest run per (slot, horizon), and buckets **overall / per `daysAhead` / per model**. Each bucket carries signed mean error (separates optimism from pessimism), mean absolute error, exact-match and within-one rates, plus two decision-error counts: **missedOpportunities** (predicted ≤2, actual ≥4) and **wastedTrips** (predicted ≥4, actual ≤2). Run over a fixed window before and after any prompt or sampling-geometry change and diff the buckets — aggregation is deterministic per window. An absolute rating ceiling can only create missed opportunities, so that count gates relaxing the cloud-approach veto (see `docs/engineering/cloud-approach-veto-fix.md`).

⚠️ **The mist-prone bucket and the tag (valley-mist-plan M3, V187).** `CalibrationPair` carries two more fields: `mistPotential` (the forecast row's `forecast_evaluation.mist_potential`, Claude's echo from M2, the String band; null for a site that is not mist-prone and every row scored before V186) and `seen` (the rating's `SeenTag`). `CalibrationReport` gains `mistProne`, a `CalibrationBucket` keyed `MIST_LIKELY` over the pairs whose forecast carried **`mist_potential == LIKELY`** (not POSSIBLE, not FOGBOUND; null when the window holds none), and `mistProneSeenCounts`, tag name to count over those pairs with `UNTAGGED` for a rating given with none (counts sum to `mistProne.sampleCount`; `NOT_SEEN` never appears because a "didn't see it" rating is not a pair). **`mistProne.missedOpportunities` is the number the whole valley-mist plan exists to drive to zero**: the forecast said stay home (2 stars or fewer) on a morning the reader rated 4 or 5. The bucket is a SUBSET of `overall`, so a mist-prone pair is counted in both. No admin view renders the calibration report (the Operations tab has none), so this is the `GET /api/admin/calibration` JSON alone. `findCalibrationPairsFromRatings` selects `mistPotential` and `seen`.

⚠️ **One source (ratings only since audit-followups O1).** The pairs come from `findCalibrationPairsFromRatings` alone (before O1 they were the union with a join on the legacy `actual_outcome`, which never held a row): each reader's `outcome_rating` joined to `forecast_evaluation` on **`r.forecastEvaluationId = e.id`** — the one row proven available by the event and unretracted at it — never on the natural key, which would admit every rated row of the slot and let a batch that landed after the event win the newest-run rule. The PREDICTED star is the rating's captured **`forecastRating`**, the star the reader saw, never `e.rating` (for a recombined OPEN_FELL slot that is the sky lane alone); `daysAhead`, run time, model and potentials are the row's own, and a rating has no fiery-sky/golden-hour actuals or `wentOut` (null — no reader infers a trip from it). A "didn't see it" rating, a rating with a null `forecastEvaluationId` or `forecastRating`, and a resolved row with no rating of its own are NOT pairs. `CalibrationPair` carries a canonical `outcomeKey` (`rating:<userId>`) and `slotHorizonKey()` appends it, so `latestPerSlotHorizon` still collapses RUNS of one forecast but keeps every reader: two readers of one slot are two pairs, and the counts are PAIRS, not slots. `outcomesInRange` counts every rating in the window, "didn't see it" included (`OutcomeRatingRepository.countByEventDateBetween`). `CalibrationBucket` no longer carries `meanAbsoluteFierySkyError` (a rating has no fiery-sky actual, so it had no reader).

⚠️ **Per-build and per-prompt buckets (V182, forecast-build-provenance).** `CalibrationPair` carries the FORECAST row's `appBuild` and
`promptHash` (both JPQL projections select `e.appBuild, e.promptHash`, so a reader's rating is bucketed by the build that scored the
forecast it was shown, never by the build that took the rating) and the report gains `bucketsByBuild` and `bucketsByPromptHash`
beside `byDaysAhead` and `byModel`, built by the same `CalibrationBucket.of` over the same deduplicated pairs, ordered by key. A
pair whose row recorded none (every row before V182, and a synchronous row's prompt) lands in an `UNKNOWN` bucket, so
history stays visible instead of vanishing. Read it as: same prompt hash, different build = a code change that is not the system
prompt (or the user-message wording); different hash = the system prompt was edited; same build and hash, different model = the
model. The report does not say how many builds a prompt spans; group the JSON yourself.

### Aurora (PRO_USER/ADMIN except `GET /api/aurora/nights` and `GET /api/aurora/alert`, any authenticated role; `/api/aurora/admin/*` and `/api/aurora/forecast/preview|run` ADMIN)
`GET /api/aurora/alert` (**any authenticated role** — `AuroraAlertController`, its own controller so `AuroraController`'s PRO/ADMIN gate does not reach it; returns `{level, active, triggerType, allOvercast, currentNightDate, currentNightEndsAt}` — the Lite banner's role-free alert: no Kp, counts or ratings; a simulation answers QUIET/inactive; ETag-revalidatable; 401 unauthenticated) | `GET /api/aurora/nights` (**any authenticated role** — `AuroraNightsController`, its own controller so `AuroraController`'s PRO/ADMIN gate does not reach it; returns `{nights: [ISO dates, ascending], currentNightEndsAt}` — only the nights worth a row (a stored night counts only if its best rating is at least 3 stars, so a quiet run gives nobody a row), never a rating, location or summary; ETag-revalidatable; 401 unauthenticated) | `GET /api/aurora/status` | `GET /api/aurora/locations` | `GET /api/aurora/viewline` | `GET /api/aurora/viewline/forecast` (all PRO_USER/ADMIN — `AuroraController` is `@PreAuthorize` PRO_USER/ADMIN, so a LITE account gets 403) | `GET /api/aurora/forecast/results|results/available-dates` (PRO_USER/ADMIN, `AuroraForecastController` — the map's read path; `available-dates` lists only nights whose best stored rating is at least 3 stars, so admins no longer browse quiet runs on the map; `results?date=` still answers for any date) | `GET /api/aurora/forecast/preview` and `POST /api/aurora/forecast/run` (**ADMIN** — see the ⚠️ below) | `POST /api/aurora/admin/enrich-bortle` (ADMIN) | `POST /api/aurora/admin/run` (ADMIN — runs the scheduled polling cycle now and waits for it; 409 while a cycle is running; returns `{status, dark, level, action, trigger, held}`) | `POST /api/aurora/admin/reset` (ADMIN) | `POST /api/aurora/admin/simulate` and `POST /api/aurora/admin/simulate/clear` (ADMIN)

> `/preview` and `/run` shared `PRO_USER/ADMIN` with the two read endpoints above until
> 2026-09-15 — the only frontend caller of either is `AuroraForecastModal`, mounted only inside the
> Admin-only Operations tab, and `/run` spends real Claude cost the way every other forecast-run
> endpoint in this app does (all ADMIN-gated) — the same more-permissive-than-intended shape as
> `POST /api/locations` and the forecast backtesting endpoints, above. ⚠️ **A simulated run never
> reaches `/results` or `/results/available-dates`, for any caller.** `POST
> /api/aurora/admin/simulate` injects fake Kp/storm data into `AuroraStateCache` for admin UI
> testing; before 2026-09-15 nothing downstream checked `isSimulated()`, so the fake reading reached
> signed-in users' Plan cards (`AuroraHotTopicStrategy`), the best-bet advisor's prompt
> (`BriefingRollupBuilder`, `BriefingAuroraSummaryBuilder`), and — via a real Claude call persisted
> with no simulation marker — the map, as if all three were real. Fixed by gating the first two on
> `isSimulated()` and by a durable `aurora_forecast_result.simulated` column (V154) the
> repository's read methods filter on; `deleteByForecastDateIn` stays unfiltered so a real re-run
> still clears an earlier simulated test run for the same night.

> ⚠️ **An admin simulation ends on the next real reading that reaches `AuroraStateCache.evaluate`, or
> on `POST /simulate/clear`, whichever comes first — and Clear is not `/reset`.** A quiet reading
> clears the simulation; an alert-worthy one replaces it with a new real alert (a NOTIFY at the real
> level, never measured against the simulated one). A night poll that would CLEAR *holds* instead
> while a block's NOAA reading is still due, and `AuroraStateCache.wouldClear` counts a simulated
> alert, so a simulation can outlive the next poll. `simulate/clear` calls `endSimulation()`, which
> acts **only while a simulation stands**: with none running — a real reading got there first,
> perhaps leaving a real alert — it changes nothing, and still answers 200 with "No aurora simulation
> was running — nothing cleared". `POST /reset` is the one that wipes the machine, a real alert
> included, so the Aurora photography bullet's “only the admin reset and simulate/clear do” holds for
> `simulate/clear` on a simulated alert alone.

### Briefing (Bearer / ADMIN for writes)
`GET /api/briefing` | `GET /api/briefing/digest` (Bearer) | `POST /api/briefing/run` (ADMIN; 409 while a build is running) | `GET /api/briefing/evaluate/scores` (Bearer) | `DELETE /api/briefing/evaluate/cache` (ADMIN) | `POST /api/briefing/compare-models` (ADMIN) | `GET /api/briefing/compare-models/runs` (ADMIN) | `GET /api/briefing/compare-models/results` (ADMIN)

> **`GET /api/briefing` is role-shaped (tide Pro gate B1, `docs/engineering/tide-pro-gate-plan.md` §7).** `BriefingController.getBriefing(Authentication)` serves PRO_USER and ADMIN the full body. Anyone else (LITE, and anything not positively PRO or ADMIN, fail-closed) gets it through `BriefingRoleShaper.forLite`: each window's `tide` loses `curve` (served as the empty list, never omitted), `windowPosition`, `windowLevel`, `sunrisePosition`, `sunsetPosition`, `extremes` and `heightAtWindow`; each `tideFacts[]` entry loses `tideAligned`, `tideAlignmentQuality`, `tideShortfall`, `tideFitPhrase`, `tideHeight` and `tideLevel`; each slot's `flags` lose "Tide aligned" and "Tide not aligned"; and each slot's own tide block (`BriefingSlot.TideInfo`, when present) loses `tideAligned`, `tideAlignmentQuality`, `tideShortfall`, `tideFitPhrase`, `tideHeight`, `tideLevel` and `nearestHighTideHeight` (`TideInfo.forLite()`, applied by `BriefingSlot.forLite()`; `tideAligned` is a nullable `Boolean`, `NON_NULL`). Kept for everyone: `state`, `direction`, `nearest*`, `range`, `rangeAnomaly`, `seas`, `locationName`, and a fact's `tideState`, `tideDirection`, `tideOnTheLight` and `nearestSolarOffsetPhrase`. The strip happens only at this HTTP edge, never in `getCachedBriefingForApi` (the digest and Ask keep the full data). `LocationTideFact.tideAligned` is a nullable `Boolean` and `BriefingWindowTide.windowPosition`/`windowLevel` nullable `Double` (`NON_NULL`), so PRO's JSON is byte-identical (pinned by `TideWireGoldenTest`). The ETag is hashed from the shaped body, so the two variants never share a validator (pinned by `HttpCachingIntegrationTest`). `/api/tides*` is not shaped but gated: PRO_USER and ADMIN only, LITE gets 403 (2026-10-26; see its entry). The digest is shaped separately, below).
>
> **The best-bet picks are Pro too (`pro-features-gate-plan.md` §8, P2).** The same `BriefingRoleShaper.forLite` also nulls each `window.pick`, serves `bestBets` as the empty list (the field is `@JsonInclude(ALWAYS)`, so it stays present and empty), and nulls `bestBetModel`, `bestBetStatus` and `bestBetsWithdrawn` (explicitly null, so LITE is never told a pick was withdrawn beside an empty list). Region prose (`glossHeadline`/`glossDetail`/`summary`) is untouched. PRO and ADMIN get the full body. Pinned by `BriefingRoleShaperTest`, `BriefingControllerTest` and the ETag case in `HttpCachingIntegrationTest`.
>
> **`GET /api/briefing/digest`** is a flat, bounded, chronological projection of the briefing's
> solar windows — the same forecast `GET /api/briefing` serves, through the same
> `BriefingService.getCachedBriefingForApi()` accessor, with the
> `days[] → eventSummaries[] → regions[] → slots[]` tree removed. It exists for clients that cannot
> afford to walk that tree (an iOS widget decodes inside a ~30 MB ceiling, on a battery budget).
> Bearer with **no role gate** (any authenticated caller may read it, by inheritance from
> `SecurityConfig`'s `/api/**` → `.authenticated()`) but **role-shaped like the briefing** (P2): a
> reader who is not positively PRO_USER or ADMIN (fail-closed) gets each window through
> `BriefingDigestResponse.Window.withoutPick()`, which nulls `pick`, `headline`, `regionName`,
> `locationName` and `locationId` (all five are the forecast-wide pick's, so they are absent from the
> JSON for LITE); the date, event, time, verdict, `bestRating` and `confidence` stay. The ETag is
> hashed from the shaped body, so LITE and PRO never share a validator. `limit` defaults to `PlanRenderLimits.MAX_VISIBLE_EVENTS`
> and is **clamped** to [1, 24] rather than rejected, because the intended caller is an unattended
> background refresh and a widget that draws a short list beats one that fails to draw. 204 when no
> briefing exists, matching `GET /api/briefing`.
>
> ⚠️ **It derives nothing** — every field is copied off the `BriefingWindow` that
> `PlanWindowProjector` already authored, and the elapsed test is the *shared*
> `PlanWindowProjector.hasPassed`, so the digest and the Plan tab retire a window at the same minute
> (pinned by an agreement test that drives both from one rule). **If a figure is not already on
> `BriefingWindow`, it does not belong on the digest — it belongs on `BriefingWindow` first, where
> both clients read it.** A projection that computed anything of its own would be the aggregator
> divergence `plan-panel-data-contracts.md` §3 records, one process further out. ⚠️ **A small
> payload is not a cheap request**: this is the full Plan-tab assembly (live hot-topic
> recomputation, the cached-evaluation re-enrichment, movement queries, the window tide rollup) with
> ten scalars per window kept — the "paid twice, one copy thrown away" shape `ServedBriefingAssembler`
> was extracted to stop. It is the only accessor that attaches a window, so the fix if it ever bites
> is a windows-only assembly or a short-TTL memo, never a cheaper accessor. **No frontend caller
> today**; it exists ahead of its client.
>
> **`bigSeas`** (big seas B2, `docs/engineering/big-seas-plan.md`): an optional array on the `GET /api/briefing` payload, one `BigSeasDay` per day (today to today+4, ascending) with at least one qualifying high water at a coastal structure; **omitted** (not empty) when there is none or the build failed. `ServedBriefingAssembler.assembleForPlan` attaches it as its last step, from `marine_high_water` and `BigSeasRule` on every serve, so it is never in `daily_briefing_cache`. It carries no personal data and is safe on the ETag-shared payload. Each day: `date` (UK civil date), `windowStart`/`windowEnd` (the lead group's union), `hwTime` (the anchor member's high water), `alignedEvent` (`SUNRISE|SUNSET`) with `gapMinutes` (nearest minute, positive) and `gapSide` (`BEFORE|AFTER`, whether the high water is before or after the event), both omitted unless the anchor is aligned, `waveHeightM` (group maximum), `waveDirectionDeg` and `wavePeriodS` (of the max-wave member), `structures` (the lead group only: `locationId`, `label`, `structure`, `waveHeightM`, `waveDirectionDeg`, `wavePeriodS`, `facesDeg`, `hwTime`, `aligned`) and `otherWindows` (further qualifying groups that day). The lead group is chosen aligned first, then a daylight group, then most structures, highest wave, earliest start. Three optional fields (hot-topics-by-day B1): `daylightHighWater` (`true`, **omitted when false**: no lead member aligned, the group's union window lies strictly between the latest member sunrise and the earliest member sunset, and the anchor's `hwTime` is strictly between its own), and `daylightStart` / `daylightEnd` (the anchor structure's own sunrise and sunset, UTC with no zone), both present only when `daylightHighWater` is true. All times are `LocalDateTime` UTC with no zone. Pinned by `JsonDateFormatContractTest`.
>
> **`hotTopics[].glance`** (coming-up-glance B1, `docs/engineering/coming-up-glance-plan.md` §2): an optional `{value, shortValue, badge, weather}` on each hot topic, the Coming up chip's second line. `value` (desktop line 2) and `shortValue` (phone line 2) and `badge` (`PEAK` | `FULL`) are each **omitted when null**; `weather` is always present (`true` = weather-driven, only knowable from the forecast). Composed by the strategies that own the number (inversion, mist) and `HotTopicGlanceDecorator` at the end of `HotTopicAggregator.getHotTopics`; the whole field is absent on a topic type nothing decorates. No personal data; safe on the ETag-shared payload.
>
> **`GET /api/briefing` slots and `GET /api/briefing/evaluate/scores` are also role-shaped for scores and the written why (Pro gate S2, `docs/engineering/pro-features-gate-plan.md` §6).** For anyone who is not positively PRO_USER or ADMIN, `BriefingRoleShaper.forLite` strips `fierySkyPotential`, `goldenHourPotential`, `claudeSummary` and `claudeHeadline` from EVERY slot (via `BriefingSlot.forLite()`, before the tide-flag filter, so a slot with no tide flag is stripped too), and `/evaluate/scores` serves each row through `LocationEvaluationView.forLite()` (null `fierySkyPotential`, `goldenHourPotential`, `summary`). The star, `displayVerdict`, stand-down reasons and region prose are untouched. The ETag is body-derived, so the variants never share a validator (`HttpCachingIntegrationTest#scoresEtagDoesNotCrossRoles`).
>
> The former SSE `GET /api/briefing/evaluate` and the `/evaluate/cache[/timestamp]` GETs no longer exist — `BriefingEvaluationController` exposes only `GET /scores` and `DELETE /cache`. This matters for HTTP caching: `/api/briefing`, `/api/briefing/digest` and `/api/briefing/evaluate/scores` are ETag-revalidated (`HttpCachingConfig`), and that whitelist is exact-match precisely so it can never catch a streaming endpoint — which is also why the digest is listed in its own right rather than inheriting `/api/briefing`'s treatment as a prefix.

**Coming up merged shape** (`docs/engineering/supermoon-tide-run-plan.md`, S1–S4): a supermoon on a tide run is one entry (D10: span = union, host = longest span, a dated line per topic), serving `dayMarks` on a merged entry and `dayRanges` (`peak` server-owned) on every tide-run entry with two or more derivable days; `buildRangeScale`'s min/max/lo/hi (with the served average) for the chip range bars is geometry over served values, the licensed filter/map class (the percentile and the average are served, `docs/engineering/tide-range-rank-plan.md`; the client only scales them and applies the served-value `<= 5` big tier).

### Almanac (Bearer)
`GET /api/almanac?days=90` — the 90-day "Coming up" feed. The response is `{builtFor, bands, counts, entries}`: the `conditions` array (the Recurring conditions panel's data) was removed by coming-up-glance B2, and a client that still reads it gets no such key. Bearer with **no role gate**, by **Role-shaped (tide-light-pro-plan.md B2):** PRO_USER and ADMIN get the cached feed itself; any other role gets `ComingUpRoleShaper.forLite(feed)` with the `EVENT_WATER` fact rows (sunrise/sunset high water against the light) removed, the coincidence alignment clock dropped from `coincidence[].factsLabel`, and `lightRowsWithheld: true` on each entry that lost something (absent from Pro's JSON). `ComingUpFact.kind` and `ComingUpCoincidenceLine.liteFactsLabel` are `@JsonIgnore` in-memory carriers and never serialise. The ETag is hashed from the shaped body, so a validator never crosses roles.
inheritance from `SecurityConfig`'s `/api/**` → `.authenticated()`; stated here because this project
has already documented one endpoint as ADMIN that the code never enforced, and `AlmanacControllerTest`
pins LITE/PRO/ADMIN access and anonymous rejection so "no `@PreAuthorize`" cannot be read as an
oversight. ⚠️ **Do not serve this from `HotTopicAggregator`.** Its signature takes a range and ten of
its thirteen strategies ignore one — two tide strategies read a 5-day briefing cache, NLC a clarity
cache, six read slot signals — ⚠️ since "record conditions for every place" (Phase 1, owner
decision 2026-09-30; cloud inversion moved onto this rule at Phase 2, V158, 2026-09-30) the two
halves of that read reach different horizons: **six** `slot_atmosphere`-backed strategies
(dust, fresh snow, snow on the tops, storm surge, cloud inversion and, since valley-mist M1b, valley
mist — gated on the stored `mist_potential` band, LIKELY only) see a reading for every
candidate the batch fetched weather for, out to the full 5-day candidate window (T+0..T+4) — the
batch never writes readings *past* T+4 only because it never collects a candidate past it, not
because of any gate on the write itself. **One** `forecast_score`-backed strategy (bluebell) is
unchanged and still never writes past T+3, because a component there requires a completed Claude
evaluation and Gate 4 never submits T+4+ to Claude at all — bluebell has no deterministic substitute
for the Claude rating and stays scored-only by design (cloud inversion's did exist, in
`InversionScoreCalculator`, and Phase 2 is the strategy moving onto it) — aurora inspects only
`fromDate` and `fromDate+1`. It is also travel-day filtered and simulation-overridable. `AlmanacSource` is a separate
interface whose contract is *answer for the whole range or do not exist*; seven implementations cover
tide runs, meteor peaks, supermoons, equinox/solstice, the NLC season, `EclipseAlmanacSource` and
`LunarEclipseAlmanacSource` (the lunar eclipse catalogue, `docs/engineering/lunar-eclipse-plan.md`).
**The tide path measures
water, not the moon** — `TideSizeIndex` applies the Plan tab's own two tests (a day's biggest high
water clearing that location's `springTideThreshold`, or its `p95HighMetres`, strictly greater, *any*
coastal location — `TideFactDeriver`'s comparisons and `KingTideHotTopicStrategy.findKingTide`'s
roster-wide rule, lifted unchanged) across the whole 90 days, then `TideRunBuilder` supplies metres
and clock times. ⚠️ **`LunarPhaseService.classifyTide` was the detector and must not be restored as
one.** It qualifies a date within ±1 day of syzygy and the biggest water of a cycle arrives a day or
two *later* — the age of the tide, a property of the coastline that no epoch arithmetic recovers. In
Aug 2026 that put the feed's run on 12–13 Aug while the roster's peak was 14–15 Aug: the Plan tab
carried a tide card on the Friday and the feed showed nothing. The tell was already on screen — both
runs reported their biggest day as the *last* day of their span. `classifyTide` keeps two jobs: the
**fallback** when `Sizes.usable()` is false (a cold DB — dates a day or two early beat none, and
`UNMEASURED` must never be rendered as "no spring tides in 90 days"), and one arm of the **king**
label, since a perigean spring is the event the copy describes while P95 is a size test that says
nothing about the moon. King is **not** assumed to imply spring: the two thresholds come from one
sample but neither is defined in terms of the other. Beyond the stored-extremes window the entry
keeps its dates and carries no numbers (`AlmanacEvent.metaOf` drops anything null or blank, so the
degrade rule is mechanical rather than per-caller). Never synthesise. **The light is a question about
the run, asked per event and of a different day from the figures** (tide-both-lights W1,
`docs/engineering/tide-both-lights-plan.md`, owner decision 2026-10-19): `TideRunBuilder
.buildWithEventWaters` returns, from the same extremes fetch and as a separate output (`TideRunDay`
is persisted and not grown), each derivable day's HIGH water nearest sunrise and nearest sunset, and
`TideAlmanacSource` picks one day per event — the true peak day when its water is aligned (within 60
minutes), else the day with the smallest |gap|, ties to the earliest — writing `sunriseHw`,
`sunriseHwGap`, `sunriseHwDate`, `sunriseHwAligned` and the four `sunsetHw…` twins, plus
`eventWaterPartial` when a run date was not derivable. High water only: a low water at the light is
no longer named on the card, and an event with no measured high water has no row at all. The old
`alignment`/`alignmentDate`/`noAlignment` meta are no longer written. ⚠️ **Never publish `verdict` under an "alignment" label again** — the verdict has an
*unaligned* form, so reading it always finds a sentence: taken from the biggest day it printed
`alignment  peak range · LW 2h12 after sunset`, claiming an alignment for a low water two hours into
the dark and restating a range already chipped above it. `alignmentPhrase` is built beside the
verdict from the same `Point` rather than trimmed out of its text (which would make that sentence's
punctuation load-bearing), so it keeps the clock time and never carries the `peak range · ` prefix.
ETag-revalidated; safe to share because it carries no per-user data.

**The feed is cached per UK day and refreshed, not just filled.** `AlmanacService.refresh()` builds the default
90-day feed and swaps it in whole (never evict-then-build), and runs from the pipeline's tail
(`PipelineOrchestrator.dispatchAlmanacRefresh`, after `finishRun`, on the background executor, nothing it throws reaches the run)
and once on `ApplicationReadyEvent`, so the chronology follows each cycle's data (the served `conditions` array and its builder were deleted by coming-up-glance B2; the warm stays for the chronology) and a restart or a new day does not leave
the first reader to pay for the build. There is no midnight job: the first reader after midnight still builds, which the memos
below keep short. `TideService.getTideStats` is memoised per location per UTC cutoff day and `TideSizeIndex.measure` and
`TideRunPeakHistory.peakRanges` per question per day (`DayScopedMemo`, whose in-flight loads are discarded if an eviction lands
meanwhile); every write to `tide_extreme` through `WorldTidesIngestionService` (forward refresh, backfill) evicts all three
through `TideService.evictTideStats`, after the transaction completes. A new writer of `tide_extreme` must call it too
(`AskLocalFixtureSeeder`, local H2 only, does not).

**The tab badge (plan `docs/engineering/coming-up-plan.md`, P5) is not on this payload at all —
deliberately.** It counts rare arrivals into this feed's window since the reader last opened the
tab, which needs a per-user `lastSeenAt` this ETag-shared endpoint must never carry (the same
reasoning that keeps reach off `GET /api/briefing`). `app_user.coming_up_last_seen_at` (nullable,
no backfill) is served as `comingUpLastSeenDate` on `GET /api/user/settings` — the Europe/London
civil date, derived server-side (`ForecastHorizon.civilDate`) so the client only ever compares two
ISO date strings — and written by its own `PUT /api/user/settings/coming-up-seen`. Every ALMANAC
entry carries a server-computed `enteredWindow` and non-null `bits`/`interim`; the client's
`isNew`/badge/since-line derivation (`utils/comingUpArrivals.js`) is the fourth member of the
Backend-heavy bullet's licensed per-user-join class, above. The band edges (`bands` on this
payload) are **census-set**, not placeholders: `ComingUpAnnualBadgeCensusTest` runs a synthetic
year through the real sources and the real assembler, landing 11 badge arrivals/year with 1
interrupt — the design's own ~10/year, one-or-two-interrupts target.

### Ask PhotoCast (Bearer / PRO_USER and ADMIN)
`GET /api/ask/ready?scope=all|<regionId>` | `POST /api/ask` | `GET /api/user/settings/ask` (listed under User Settings) | ADMIN: `POST /api/admin/ask/dry-run` | `POST /api/admin/ask/ready/precompute` | `GET /api/admin/ask/metrics?days=`

> `GET /api/ask/ready` and `POST /api/ask` are **PRO_USER and ADMIN only** (owner decision 2026-10-24, `ask-pro-gating-plan.md`): a
> `SecurityConfig` matcher on `/api/ask` and `/api/ask/**` sits above `/api/**` → `.authenticated()`, in the security chain and so ahead of
> `AskBodyLimitFilter`, `AskFlagInterceptor` and `AskAdmissionInterceptor`; `AskController` also carries a class-level `@PreAuthorize`, and
> `AskService.admit` and `ask` begin with the same `AskUserContext.mayUseAsk` guard (Ready matches, cache hits and pre-filter can't-answers
> skip the allowance, so a limit of 0 alone would not refuse). A LITE caller is **403 `{error: "Ask PhotoCast is part of Pro.", code:
> "NOT_ENTITLED"}`** (an `accessDeniedHandler` scoped to `/api/ask/**` writes Ask's own shape; every other 403 keeps Boot's default body),
> **whatever the flag says, so the flag-off 404 is never shown to LITE**; a 403 never takes an ETag, so a LITE reader cannot revalidate a PRO
> reader's body into a 304. `GET /api/user/settings/ask` is not under that matcher and stays 200 for every role; for LITE with the flag on it is
> `{enabled: true, used: 0, limit: 0, left: 0, typedAvailable: false}`. **With `photocast.ask.enabled` false every Ask endpoint answers 404 for
> an entitled caller — except `GET /api/user/settings/ask`, which is always 200** so the client can tell "off" from "unreachable" — from ONE place, `AskFlagInterceptor` (registered in `AskWebConfig` over
> `/api/ask/**` and `/api/admin/ask/**`, ahead of the admission interceptor, before the body is read); no controller checks the flag. Every
> Ask route checks the role first (403) and the flag second (the public routes in the security chain, the admin routes by method security): the interceptor runs before method security, so its admin-route instance stands down
> unless the caller holds `ROLE_ADMIN` and `@PreAuthorize` answers everyone else; the order 401, 403, 404 is pinned through the real chain in
> `AskFlagOffPrecedenceTest`. Controllers parse and delegate (`AskDryRunService` holds the dry-run).
>
> **`GET /api/ask/ready`** → `{scope, questions: [{id, text, tabs, generatedAt, runLabel, answer: {answerable, kind: "ready", summary, picks,
> events, missing, try}}]}`. `scope` defaults to `all`; anything that is neither `all` nor the id of an ENABLED region is 400 `{error}` (no
> `code`). Each question carries its own `generatedAt` and `runLabel` (the briefing run it was written from, `HH:mm` Europe/London), and a
> question that fails the serve-time freshness test is withheld whole. ETag-revalidated on the exact path `/api/ask/ready` (`?scope=` does not
> change the key; the body's hash keeps scopes apart). **`POST /api/ask`** takes `{question, windowId?, regionIds?, view, thread?}` (`view` ∈ `map | plan |
> coming-up`; empty `regionIds` = all; `thread` is the earlier exchanges, oldest first, each `{question, summary, picks: [{locationId, windowId}], events: [{type, date}], generatedAt, ready?, nightVersion?}`, at most 8) and answers 200 `{answerable, kind, summary, picks, events, missing, try, allowanceLeft, allowanceLimit,
> charged, generatedAt, runLabel, threadReset?, threadResetReason?, nightVersion?}` (`threadReset` and `threadResetReason` only when a thread was dropped, `true` with `"forecast updated"` when the briefing was rebuilt or `"night forecast updated"` when only a night's forecast moved, otherwise absent from the wire; `nightVersion` only on an `own` answer that consulted or named a night, the opaque string the client echoes on that exchange) where `kind` is `ready` (matched to a fresh Ready answer, free), `own` (Claude's answer or a typed-cache hit; a
> hit is free) or `cant` (the pre-filter or `answerable:false`: `picks` and `events` empty, `missing` set, `try` up to two fresh Ready questions,
> `charged` false). An event carries `{type, label, date, why, safetyNote?}` — `safetyNote` is omitted on the wire when null and is never the
> model's. Errors are `{error: "human sentence", code}`:
>
> | Status | `code` | When | Allowance |
> |---|---|---|---|
> | 400 | `INVALID` | blank, over 200 characters, outside the allow-list, an unknown or disabled region id, an unreadable or wrongly typed body, a body over the derived limit (88 KiB at the default thread cap), a bad `thread` or a malformed `nightVersion` (see *Ask — the thread*) | nothing used |
> | 401 | `UNAUTHENTICATED` | a token for a user that has since been deleted | — |
> | 403 | `NOT_ENTITLED` | the caller is LITE_USER (flag on or off; refused in the security chain, before the body is read or anything is counted) | nothing used |
> | 404 | — | the flag is off (PRO and ADMIN only) | — |
> | 429 | `RATE_LIMITED` | more than 5 requests a minute for this user (counted before the body is read; anonymous callers are 401 first and never counted) | nothing used |
> | 429 | `ALLOWANCE_EXHAUSTED` | `used` has reached the role's limit (named first when both ceilings are reached) | — |
> | 429 | `DAILY_LIMIT` | the never-refunded `engine_calls` ceiling (3 × the limit) reached with the allowance not | — |
> | 502 | `ENGINE_FAILED` | any other failed engine run, including an engine that throws | refunded; "No question used" |
> | 503 | `TYPED_UNAVAILABLE` | the spend cap is reached, the accounting latch is set, no briefing has been built, or the database cannot reserve | refunded if a question had been reserved |
>
> **`POST /api/admin/ask/dry-run`** `{question, regionIds, windowId?}` → `{engine: "stub"|"claude", status, answer, personal, turns, reason, trace}`:
> the real engine with the lenient sanitiser and NONE of the typed guards (no rate limit, allowance, pre-filter or cache — an admin is trusted),
> counted toward the typed spend when the engine is Claude; 400 for a blank or over-200 question or an unknown, disabled or more than 20 region
> ids, 409 when no briefing exists, and a FAILED run is a 200 carrying `status: FAILED` and its `reason`. **`POST /api/admin/ask/ready/precompute`**
> runs the Ready precompute now on the calling thread → 200 `{written, skipped, failed}`, or 409 `{error}` when it was refused whole (no fresh
> briefing, a simulation active, another precompute running); with the Claude engine it spends real money. **`GET /api/admin/ask/metrics?days=`**
> (clamped 1..90, default 7, non-numeric 400): outcome counts, `cacheHitRate`, `readyMatchRate`, `cantRate` (each null on a zero denominator),
> the top `missing` phrases, and the typed and Ready spend; it never returns a question, raw or normalised.

### Astro Conditions (Bearer)
`GET /api/astro/conditions` | `GET /api/astro/conditions/available-dates`

### User Settings (Bearer)
`GET /api/user/settings` | `POST /api/user/settings/home/lookup` | `POST /api/user/settings/home/reverse` ("Use my location": `{latitude, longitude}` → the nearest UK postcode within 2 km, persisting nothing; 400 for a missing, non-finite or out-of-range value or a point with no postcode, 502 "Postcode lookup is unavailable right now" when postcodes.io fails — neither echoes the upstream) | `PUT /api/user/settings/home` |
`POST /api/user/settings/drive-times/refresh` (PRO_USER/ADMIN; LITE 403) | `GET /api/user/settings/drive-times` (PRO_USER/ADMIN; LITE 403) |
`GET /api/user/settings/reach` (PRO_USER/ADMIN; LITE 403) | `GET /api/user/settings/light` |
`GET /api/user/settings/ask` (Ask PhotoCast's allowance read — `{enabled, used, limit, left, typedAvailable}`, always 200 for every role and zeros with `enabled:false`
when the flag is off, and zeros with `enabled:true, typedAvailable:false` for a LITE reader; `typedAvailable` reads false while the accounting latch is set; pinned in `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`) |
`PUT /api/user/settings/map-colours` | `PUT /api/user/settings/map-tide-mode` (the Map tab's
persisted phone tide mode — Auto/Always/Off, `map-mobile-sheet-plan.md` M4 — copies `map-colours`'
shape exactly; read and written by the phone Layers section's Tide segment since M5) |
`PUT /api/user/settings/coming-up-seen` (Coming up tab badge's last-seen write, plan D3/P5 — see
CLAUDE.md's Almanac section) |
`GET /api/user/settings/sub-regions` (any role; sub-regions-plan §4.3: `{enforced, limit, picks[{areaId,name,regionName}], options[{regionId,regionName,subRegions[{areaId,name,locationCount,exampleLocations[2]}]}], suggested[areaId], status, freeUntil?, nextChangeOn?, overLimit?}`, `status` FIRST_CHOICE, FREE_UNTIL, OPEN, LOCKED or NOT_APPLICABLE; Pro and Admin get NOT_APPLICABLE with no options; `suggested` is the `limit` sub-regions nearest the reader's home, only when they have a home and no picks; `enforced` only reports `photocast.sub-regions.enforce`; the path is pinned in `HttpCachingConfigTest.personalDataPathsAreNeverFiltered`) | `PUT /api/user/settings/sub-regions` `{areaIds[]}` (LITE_USER only, Pro/Admin 403; replaces the picks under the change rule; 400 for an empty list, more than the limit, a duplicate or a non-`SUB_REGION` id; 409 `{error, code: "SUB_REGIONS_LOCKED", nextChangeOn}`, or a plain 409 for a lost duplicate-key race; 200 with the new state; an identical set is a 200 no-op) |
`PUT /api/user/settings/postcode-prompt-dismissed` (no body, idempotent: stamps `postcode_prompt_dismissed_at` and answers the settings with `postcodePromptDue: false`; its response carries a null `homePlaceName`, so a client must not feed it into place state)

> Previously documented as `GET|PUT /api/user-settings`, a path that has never existed —
> `UserSettingsController` is mapped at `/api/user/settings` (with a slash). The doc was wrong, not
> the controller, and the wrong form was never a live route.
>
> **Everything under this prefix is home-derived personal data, and that is what the prefix is for.**
> `HttpCachingConfig`'s revalidatable set is an exact-match allow-list, so a path here can never pick
> up the `Cache-Control: private, no-cache` that ETag revalidation requires — which would persist the
> body to a browser HTTP cache JavaScript cannot evict on logout.
> `HttpCachingConfigTest.personalDataPathsAreNeverFiltered` pins it per path, so a new route here
> must be added to that list. `/reach` and `/light` both live here for that reason rather than beside
> the payloads they are joined to on the client.
>
> Bearer with **no role gate**, by inheritance from `SecurityConfig`'s `/api/**` → `.authenticated()`,
> **except the three drive-time routes** (`POST /drive-times/refresh`, `GET /drive-times`, `GET /reach`), which carry a
> method-level `@PreAuthorize("hasAnyRole('PRO_USER','ADMIN')")` (`pro-features-gate-plan.md` D2): a LITE caller is 403 and the
> service is never reached. `GET /api/user/settings` and `PUT /home` stay open to every role: saving a home postcode has always been open to any account, and since
> the masthead's light rule is drawn from it the frontend no longer gates it either — light times
> free, drive times and the local radius Pro. ⚠️ **Do not re-gate the postcode input.** The band's
> empty state nudges the reader to that field, so a Pro gate on it makes the nudge a dead end and
> leaves the rule permanently dim for exactly the accounts it is written for.
>
> `GET /light` answers **204** when no postcode is saved. That is the masthead's designed empty
> state, not an error — and the client must not fold a *failed* request onto the same value, because
> "you have no postcode" is a claim about the reader's account that a 502 is no evidence for.

### Rewind (ADMIN)
`GET /api/admin/rewind/events` — the recent solar events (last three UK days, sunrise and sunset, newest first) timed across the enabled sky roster, each with the `rewindTo` instant the Operations tab's Rewind view offers; plus `now` and the cached briefing's `briefingGeneratedAt`. The rewind itself is **not a resource**: the client attaches the chosen instant to its ordinary GETs as `X-Rewind-To`, which `RewindFilter` honours for an admin only (GET only, never under `/api/admin/`, never in the future, never more than three days back; malformed or out of range → 400 `{error}`), and the application `Clock` answers with it for that request alone. See the Admin features bullet under *What's Built*.

### Scheduler (ADMIN)
`GET /api/admin/scheduler/jobs` | `PUT /api/admin/scheduler/jobs/{jobKey}/schedule` | `POST /api/admin/scheduler/jobs/{jobKey}/pause` | `POST /api/admin/scheduler/jobs/{jobKey}/resume` | `POST /api/admin/scheduler/jobs/{jobKey}/trigger` | `PUT /api/admin/scheduler/jobs/{jobKey}/schedule-type` (body `{scheduleType: CRON|SOLAR_OFFSET, solarEvent?, solarOffsetMinutes?}`; only the two main forecast cycle jobs may become `SOLAR_OFFSET` (never `evening_sunrise_refresh`); 400 changes nothing) | `GET /api/admin/scheduler/solar-preview`

### Tides (PRO_USER and ADMIN)
`GET /api/tides` | `GET /api/tides/stats` | `GET /api/tides/stats/all`

> **PRO_USER and ADMIN only since 2026-10-26** (owner decision: tide heights are Pro). `TideController` carries a
> class-level `@PreAuthorize("hasAnyRole('ADMIN', 'PRO_USER')")`, as `AuroraController` does; LITE_USER gets 403,
> unauthenticated 401, `/api/tides/stats` answers 204 when no extremes are stored. The only callers are admin
> screens (`TideManagementView`, `LocationManagementView`, `TideIndicator` in the admin `PromptTestView`). The ETag
> filter is body-derived and tags 2xx only, so a 403 never carries or honours a validator a PRO 200 produced.

### Push (mixed auth)
`GET /api/push/vapid-public-key` (none) | `POST|DELETE /api/push/subscribe` (Bearer)

---
