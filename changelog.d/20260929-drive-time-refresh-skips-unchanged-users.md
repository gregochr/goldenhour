### Changed — the nightly drive-time refresh only re-measures a user who needs it

`drive_time_refresh` used to re-measure every enabled user with a saved home, every night, one
OpenRouteService matrix call per user against the whole location roster — whether or not anything
had changed. On a free ORS plan that cost grows with the user count for no benefit on most nights,
since most answers are identical to the night before.

A scheduled fire now measures a user only when they are due: their `driveTimesCalculatedAt` stamp
is null (never measured, or a postcode change discarded it — see `UserSettingsService.saveHome`),
or the location roster has grown since that stamp, so a location added after everyone's last
refresh does not stay unmeasured forever. Everyone else is skipped with no ORS call at all, decided
from one query for the roster's newest `created_at` rather than one query per user. A quiet night —
nobody's postcode moved and nothing was added — now makes zero OpenRouteService calls.

The admin "Run now" trigger (`DynamicSchedulerService.triggerNow`) is unchanged: it still measures
every enabled user with a home, exactly as before, because it is the deliberate override for the
one thing the stamp cannot detect on its own — a location's coordinates being corrected in place.
`DynamicSchedulerService` now tells a manually-triggered job target from a scheduled one via a new
`registerJobTarget(String, Consumer<Boolean>)` overload; every job that does not care about the
distinction keeps using the existing `Runnable` overload and behaves exactly as it always has on
both routes.

The manual Settings-dialog refresh button, its cooldown and its 409 behaviour are untouched.

A follow-up migration (V156) corrects the job's admin-facing `scheduler_job_config.description`,
seeded by V133 with the old "recalculates every user's" wording, to describe the new skip logic and
say that "Run now" always re-measures everyone.

A P1 fix (review of PR #942): both the scheduled job and the manual Settings refresh now stamp
`driveTimesCalculatedAt` with the instant the location roster was READ, captured immediately before
`measureForUser`, rather than an instant taken once the answer was back — the earlier version could
silently leave a location unmeasured indefinitely if it was created while routing was in flight;
`rosterGrewSince`'s boundary is now inclusive (`created_at` equal to the stamp counts as grown too),
so a tie can never be missed.

Two more P1s (second review of PR #942), both rooted in the same mistake: `driveTimesCalculatedAt`
was being read as proof that the roster had been covered, when it was not always true.

First, the manual Settings refresh could advance that stamp for an attempt that stored no rows.
When OpenRouteService gave no answer at all, `UserSettingsService.refreshDriveTimes` used to write
the stamp anyway (through a now-deleted `UserDriveTimeWriter.stampIfHomeUnchanged`) purely so the
30-minute cooldown still caught a repeated press. On the one realistic sequence that reaches this —
a postcode change, whose save has already cleared the stamp, is the only way this button is enabled
— that left a non-null stamp newer than the roster with zero rows behind it, and the scheduled job
skipped that user every night thereafter. The stamp now moves only together with stored rows, on
both routes; an attempt that measures nothing leaves it exactly as it was (`null` on that sequence),
and the response reports the user's own unchanged stamp rather than "now", so the Settings dialog
cannot print "Last calculated: Just now" for a refresh that stored nothing. The 30-minute cooldown
for a *failed* attempt is now tracked separately, in an in-memory `ConcurrentHashMap<Long, Instant>`
on `UserSettingsService` keyed by user id, consulted alongside the persisted stamp and cleared on a
successful store, a 409 (home moved mid-measurement), or `saveHome` moving the home — an in-memory
limiter resets on an app restart, which is accepted for a rate limit on a single-instance app.

Second, `LocationEntity.createdAt` is assigned by the application before the row is saved, so there
is a gap — however small — between that reading and the row becoming visible to another
connection's query. A roster read landing inside that gap sees the table one location short, while
the row it missed still carries a `created_at` earlier than the stamp this class goes on to store —
which the Round-2 inclusive-tie fix does not catch, since the two instants are not equal, only
close. `DriveTimeRefreshJob.ROSTER_VISIBILITY_MARGIN` (one hour) widens `rosterGrewSince` further in
the same direction: a location counts as "added since" a stamp when its `created_at` is at or after
`stamp minus ROSTER_VISIBILITY_MARGIN`, not only at or after the stamp itself. The real gap this
covers is bounded by one `JpaRepository.save()` call (`LocationService.add` is not
`@Transactional`, and the tide fetch that follows `save()` runs after the row is already committed)
— milliseconds, not the hour chosen; the margin is deliberately generous rather than tight. The
bounded cost: a user whose stamp lands within the margin after such a location is measured once
more on the very next scheduled run and never again for that location, because the fresh stamp that
run stores is then a full day clear of it — the common trigger being an admin adding a location and
pressing "Run now" within the hour. The residual is stated rather than hidden: an `INSERT` whose own
transaction stays open longer than the margin would still be missed, and an admin's "Run now"
remains the remedy for that case, exactly as for a location's coordinates being corrected in place.

One more P1 (third review of PR #942): the second fix above stopped a NEW stamp-without-rows state
from being written, but did nothing about one an earlier build had already left in the database.
`DriveDurationService.measureForUser` documents two different kinds of "nothing": no answer from ORS
at all (an empty `Optional`), and ORS answering with no valid duration to *any* location (a present
but empty list) — the javadoc's own words are "ORS answered, and storing it clears the stored ones".
`UserSettingsService.refreshDriveTimes` only closed the first kind; the second still called
`UserDriveTimeWriter.storeIfHomeUnchanged` with an empty list, which clears a user's stored rows and
stamps in the very same compare-and-set — recreating the exact stamp-without-rows state the previous
fix exists to prevent. Both kinds of nothing are now handled identically on the manual path: no
writer call, rows and stamp both left exactly as they were, matching what the scheduled job's own
`run` method has always done for an empty measurement regardless of cause.

A new migration, V157, reconciles the state an earlier build could already have left behind: it
clears `drive_times_calculated_at` for every user who has a stamp and zero `user_drive_time` rows,
using `NOT EXISTS` so it is a safe no-op on re-run. Production, checked read-only before this
migration was written, holds zero affected rows today (4 users, 2 with a home, 2 stamped, none
stamped without rows) — the migration is precautionary for other environments and the window before
deploy, not a repair for a live incident. `DriveTimeRefreshJob.needsRefresh`'s javadoc now states the
one cost this rule accepts on purpose: a user whose home can never be routed to anywhere gets no
rows, ever, so their stamp stays null and every scheduled run measures them again, one ORS call each
— exactly this job's pre-skip-logic behaviour, now confined to the users it actually applies to.
Local H2 dev databases run no migrations at all (see this file's own "no Docker" section), so a
local database holding a legacy stamp-without-rows row — reachable only by having exercised the old
buggy code path before pulling this fix — keeps it; no startup repair was added for that case, since
it is narrow, self-inflicted, and already covered by this project's documented local-reset procedure
(delete `backend/data/goldenhour.mv.db` and `.lock.db`).

One more P1 (fourth review of PR #942), against the previous fix's own decision: merging the two
kinds of nothing on the manual path went too far. `DriveDurationService.measureForUser`'s
confirmed-unreachable answer (ORS answered; no destination has a valid duration) is not "nothing
learned" — ORS has just told us the stored drive times are stale, and the previous fix's merge left
them in place, so the reach lens and a leave-by time went on using journeys ORS had just
invalidated. The decision: a confirmed-unreachable manual refresh CLEARS the user's rows and sets
the stamp to `null` together — a new guarded `UserDriveTimeWriter.clearIfHomeUnchanged`, using the
same compare-and-set shape `storeIfHomeUnchanged` uses, on its own dedicated repository method
(`AppUserRepository.clearDriveTimesCalculatedAtIfHomeIs` — a literal `SET ... = NULL`, added after
review rather than calling `stampDriveTimesIfHomeIs` with a `null` instant, so the write never
depends on how a bound null binds on a given JDBC driver) and `clearForUser`'s row delete — no third
way to delete rows. That leaves the EXACT state
a home move already produces (rows gone, stamp `null`), which the rest of the product already
handles honestly: no "Last calculated" line, the reach lens reads the location as unknown, and the
scheduled job measures the user again on its very next run because the stamp is null. The no-answer
case (ORS gave no answer at all) is unchanged from the previous fix: rows and stamp both left
exactly as they were.

The scheduled job's own behaviour is unchanged and now stated as a deliberate decision, not merely
inherited: it treats both kinds of nothing identically, as a failure that stores nothing, because it
runs unattended overnight and a transient ORS wobble returning zero valid durations must not
silently wipe a user's drive times before anyone is looking. `UserDriveTimeWriter`'s class javadoc
now carries the full outcome table — {rows stored, no answer, confirmed unreachable, home moved} ×
{manual, scheduled} — as the single source of truth for which route does what to the rows and the
stamp on each outcome.

The 30-minute manual-refresh cooldown for a confirmed-unreachable attempt is enforced entirely by
the in-memory `pendingDriveTimeAttempts` map now, deliberately left in place (not cleared) after a
successful clear: the persisted stamp that attempt leaves behind is `null`, so unlike a successful
store it cannot cover the cooldown on its own. A home move during a confirmed-unreachable
measurement still answers 409 with nothing written, clearing the pending-attempt entry exactly as
the stored path's 409 already did, and `saveHome` moving the home clears a confirmed-unreachable
attempt's pending entry too, releasing the cooldown for an immediate retry from the new home.

V157 is unaffected: the invariant it repairs — no non-null stamp with zero rows — still holds after
this change, since `clearIfHomeUnchanged` sets rows and stamp together, atomically, guarded by the
same compare-and-set `storeIfHomeUnchanged` uses.

A CI failure surfaced a defect in `ClearDriveTimeStampWithoutRowsMigrationTest`'s own seeding,
unrelated to V157 itself: the test hard-coded primary keys (`app_user.id = 1`, `locations.id = 1`)
that collided with rows Flyway migrations seed on every fresh database (V10's admin user, V84's
bluebell locations) — a defect the local gate cannot catch, since local dev runs no migrations and
this class only runs in CI. Fixed by reading every id back via `INSERT ... RETURNING id` instead of
assuming one, and by reusing a location Flyway had already seeded rather than inserting a new one.
Every assertion now selects by the ids the test itself created, so the seeded admin row (no stamp,
no rows) cannot affect a result even incidentally. The container field was already non-static
(a fresh container, and therefore a fresh schema, per test method), so the two test methods were
never able to see each other's rows regardless of run order — the collision was each method against
Flyway's own seed data, not the two methods against each other.

A further review pointed out that `clearIfHomeUnchanged`'s compare-and-set had only ever been
exercised through mocks — no test had run the actual `SET ... = NULL` statement against a real
database, so a null bind that a JDBC driver could not type would only fail at runtime, on a rare
path. `AppUserRepository` gained its own dedicated `clearDriveTimesCalculatedAtIfHomeIs` method (a
literal `SET u.driveTimesCalculatedAt = NULL ...`, not `stampDriveTimesIfHomeIs` called with a
`null` instant), removing the question rather than relying on an untested answer, and
`clearIfHomeUnchanged` now calls it. Two real-database tests were added either way, because they
prove the method's whole behaviour (the delete, the atomicity, the guard), not only the null-bind
question the repository change already settles: `UserSettingsRaceSequenceTest` (H2, runs locally)
and `UserSettingsRowLockIntegrationTest` (Postgres, CI-only) each gained a pair proving the rows are
deleted and the stamp nulled when the home matches, and that both survive untouched when it does not.

One more P1 (fifth review of PR #942), on `DriveDurationService.measureForUser`'s OTHER kind of
partial result: a successful ORS answer that omits one or more destinations (a null or negative
duration for that destination specifically) while storing valid durations for the rest. `run` was
already advancing the stamp on such an answer, so an omitted destination was never retried on the
schedule alone. Verified before deciding: `OpenRouteServiceClient.fetchDurations` makes one
un-chunked call per measurement, and a transient failure (unconfigured, rate-limited, malformed or
empty response) fails that whole call rather than returning a partial list — so a null or negative
entry can only come from ORS's own per-destination answer within an otherwise successful response, a
definitive "no route exists" result, never a transient one. Decision: **keep the behaviour.**
Refusing to advance the stamp until every destination succeeds would make every user due every
night for as long as one location in the roster is unroutable from anywhere — not hypothetical,
since production held exactly such a location (a latitude typo placing it in the sea) until this
date, undetected. That is a data problem an admin fixes by correcting the coordinates, and the
admin's "Run now" (which bypasses this predicate) re-measures everyone the moment they do; nightly
retries cannot fix coordinates, and the location renders honestly meanwhile (no drive time is
"unknown," passing every reach tier). `DriveTimeRefreshJob.needsRefresh`'s javadoc now records this
as a decision taken in review, dated 2026-09-29, and the scheduled route logs one aggregated WARN per run
(never one per user) naming every location a successful measurement omitted that run, so an
unroutable location is visible to an operator the first night it appears — a manual "Run now" skips
this query and its WARN, since it already hands its result straight to the admin who pressed it.

One more P1 (sixth review of PR #942), on the in-memory `pendingDriveTimeAttempts` map itself:
it was keyed only by user id, so it could throttle a refresh reading a DIFFERENT home from the one
the pending entry was actually about. Sequence: a manual refresh reads home A; before it records
its attempt, `saveHome` commits a move to home B and removes that user's pending entry — finding
nothing, since the refresh has not recorded it yet; the refresh then records its attempt (still
against A, the home it read) and ORS gives no answer or throws. The entry left behind carried no
origin, so an immediate refresh reading the new home B was refused with 429 for up to 30 minutes,
even though a home move is documented to release the cooldown.

Each entry now carries the home coordinates the refresh read alongside the attempt instant
(`UserSettingsService.PendingAttempt`, a package-private record: `attemptedAt`, `originLat`,
`originLon`). The 30-minute cooldown only counts a pending entry when its origin matches the
caller's CURRENT home, read fresh at the top of the same refresh; a mismatched entry is ignored
exactly as if it were absent, and `refreshDriveTimes` unconditionally overwrites whatever was there
with a fresh entry for the origin it just read — "ignored and replaced," never merely ignored.
Origin comparison is exact `==` on both coordinates, the same equality
`AppUserRepository.stampDriveTimesIfHomeIs`'s JPQL already uses on the same `DOUBLE PRECISION`
columns — no tolerance introduced that the persisted compare-and-set does not already have.

An attempt that THROWS is handled by the same rule, not a separate one: nothing in
`refreshDriveTimes` catches `measureForUser`'s exception, so the entry recorded a moment earlier
(against the origin that call was reading) survives it exactly as it survives a no-answer response.
A retry from that SAME origin within the cooldown is still throttled; a retry from a DIFFERENT
origin is not, for the identical reason a no-answer entry releases on a different origin.

`saveHome`'s own removal of the pending entry is kept, unconditionally, on every move — but it is
now a tidy-up rather than the thing correctness depends on, since the origin check already makes a
stale entry harmless even where this removal's own race finds nothing yet to remove. A move away
and back to the same coordinates within the cooldown (A to B and back to A) does not resurrect the
earlier A-scoped entry: `saveHome` clears the pending entry on EVERY move, including the one back to
A, with no memory of what the home used to be — consistent with the persisted stamp and rows, which
`saveHome` also clears unconditionally on every move and never restores merely because the
coordinates recur.

Every read-then-write on the map that removes an entry it wrote itself now uses the value-conditional
`remove(key, value)` rather than a bare `remove(key)` — after a successful store, on the 409 path,
and on the confirmed-unreachable-but-home-moved 409 — so a concurrent refresh for the same user
cannot have its own fresh entry clobbered by a stale one finishing late. Two simultaneous refreshes
for the same user and the same origin: the second is refused with 429 if its own throttle check runs
after the first has recorded its attempt (the ordinary case, since the entry is written before the
ORS call); a genuine race where both read the map before either writes is unchanged from before this
fix and remains out of scope here, as it was before.

New tests in `UserSettingsServiceTest`'s `ManualAttemptCooldown` nested class reproduce Codex's
exact interleaving for both a no-answer attempt and one where ORS throws — both fail against the
prior commit, whose map carried no origin at all — plus a same-origin-after-exception throttle test
and an A-to-B-and-back-to-A test pinning that a move-away-and-back does not resurrect an earlier
attempt.
