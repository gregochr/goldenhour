### Fixed — a postcode saved while drive times recalculate is no longer put back to the old one

A reader could save a new postcode and press **Refresh drive times** before the save had
committed; the settings dialog allows it. The refresh read the old home, routed from it for
several seconds, stored those drive times over the save's discard, and then saved the whole user
row it had loaded, writing the old postcode and coordinates back over the new ones. The server
ended on the old home, with drive times measured from it, while the page showed the new one. The
same shape lost other writes: a colour or home save made while the nightly drive-time job routed
that user was reverted by the job's save, and a home save and a colour save in two tabs could lose
one of the two. `AppUserEntity` has neither `@Version` nor `@DynamicUpdate`, so every one of those
saves wrote every column from its own copy.

**The fix.**
- **Every settings write is column-scoped**, following `updateComingUpLastSeenAtByUsername`:
  `updateHome` writes the postcode, coordinates and radius together, `updateMapColourScaleByUsername`
  writes the colour alone, and the drive-time stamp has its own two statements. A null radius keeps
  the stored one (`coalesce`), as the in-memory save did.
- **The seven settings columns are `updatable = false`.** Column-scoping protects a column only
  if no whole-entity save still writes it, and several do: the JWT filter's last-active save (at
  most hourly, from whichever authenticated request comes first), login, a password change, the
  marketing opt-in and the admin paths. None of those can now write a home, colour, radius, stamp or
  Coming-up instant. ⚠️ So `setX()` then `save()` on an existing user writes none of these columns;
  inserts are unaffected.
- **A refresh stores only while the home is still the one it measured from.**
  `DriveDurationService` now only measures (`measureForUser`). `UserDriveTimeWriter.storeIfHomeUnchanged`
  compare-and-sets the stamp on the measured coordinates FIRST, then replaces the rows in the same
  transaction, and writes nothing when that matched no row. The coordinates are compared because they
  are the measurement's whole input, and `DOUBLE PRECISION` round-trips a Java `double` exactly.
- **`POST /api/user/settings/drive-times/refresh` answers 409** when the home moved while it measured,
  having written nothing. It does not re-measure: a retry re-runs every precondition against the row as
  it stands, including the cooldown, which answers 429 if another refresh already measured from the
  new home. The retry is always allowed after a move, because the save released the cooldown.
- **The nightly job stores through the same guard** and counts a moved user as superseded, not failed.
- **`saveHome` decides whether the home moved from a `SELECT … FOR UPDATE` read**
  (`findByUsernameForUpdate`), so nothing can change the row between that decision and the commit.
  Both it and the store take the user row before the drive-time rows, so they cannot deadlock.

**Why not `@Version` or `@DynamicUpdate`.**
- `@DynamicUpdate` narrows the save of a *managed* entity to the columns that changed. With
  open-in-view on (Boot's default, not overridden here), the manual refresh held a managed entity, so
  it would have stopped that refresh writing the old postcode back. It cannot help a save that merges a
  detached copy, as the nightly job and the JWT filter's last-active write do: a merge counts every
  stale value as a change. And it does nothing about the drive-time rows, which the refresh stored for
  the old home either way.
- `@Version` would catch every stale write, at two costs. It turns independent writes into conflicts
  whenever they overlap: a colour save, a home save, the hourly last-active bump. And it needs a
  migration plus a conflict response at every writer. The one real conflict is narrower than "the row
  changed": drive times measured from a home that has since moved. A compare-and-set on the
  coordinates answers exactly that.

**Behaviour changes to know.**
- The nightly job no longer clears a user's drive times when ORS answers with no valid duration to any
  location. It stores nothing then, as it already did for an empty response, so the stored drive times
  keep matching their stamp. The manual refresh still clears in that case.
- The cooldown and both stamps read the injected `Clock` rather than `Instant.now()`.

**The frontend contract is unchanged.** The refresh's response is still
`{locationsUpdated, calculatedAt}`, and `useReaderSettings` still compares stamps as instants. The
dialog renders a 409 as "Something went wrong — please try again", and focus goes back to the Refresh
button; pressing it again is the remedy. The reason reaches the client in the error body's `error`
field.

**Tests.**
- **Mocked unit tests** pin each write to its column-scoped method with explicit arguments, and pin
  that none calls `save()`. They also cover both branches of the guard, the 409, the cooldown at
  1799/1800/1801 s on a fixed clock, that the locked read comes before every write, and the job's
  branches.
- **`AppUserRepositoryTest` (H2)** proves each update writes its own columns and no others, covers
  both compare-and-set branches (latitude and longitude varied alone), and shows the `coalesce`. It
  also reproduces the lost update itself: a stale copy saved back now emits an `UPDATE` without the
  settings columns, and a positive control proves that save really wrote.
- **`UserSettingsRaceSequenceTest` (H2)** replays each failure through the real services and
  transactions, driving the interleaving from inside the stubbed routing call: a postcode saved
  mid-refresh (409, the new home kept, nothing stored, the retry measuring from the new home), a
  colour saved mid-refresh (kept, and no conflict), a home saved while the nightly job routes, a move
  releasing the cooldown, and a radius-only save keeping drive times. A second connection proves the
  locked read really holds the row.
- **`UserSettingsRowLockIntegrationTest` (Postgres, CI only; not run locally, since there is no
  Docker here)** proves that a store waits on a save holding the row and then matches the home that
  save committed. It also replays the postcode failure against the production engine.

**Proved by mutation**, in a scratch copy of `backend/`, running only the classes each mutant
concerns. After every run the file was restored and checked byte for byte. All 31 were caught locally:
30 by an assertion, and one by an error from the product itself.

| mutant | caught by |
|---|---|
| `updatable = false` removed from each of the 7 settings columns (7 mutants) | the stale whole-entity save test, each on its own column |
| compare-and-set ignores latitude / longitude / the whole home (3) | the matching compare-and-set test; the whole-home one also by the postcode sequence (no 409) |
| `updateHome` resets a null radius | the `coalesce` test and the radius-only sequence |
| `updateHome` also clears the stamp | the column-scoping test and the radius-only sequence |
| `@Lock` removed from `findByUsernameForUpdate` | the second-connection lock test |
| the store ignores the compare-and-set's result | the writer test, and both the refresh and nightly sequences |
| the store deletes rows before the compare-and-set | the writer's order and no-write tests |
| the no-answer stamp always reports success | the writer test |
| `saveHome` decides from an unlocked read | `readsUnderTheLockBeforeWriting`, among others |
| a move keeps the stamp / keeps the rows (2) | the unit tests; the stamp one also by the cooldown sequence (429) |
| every save / no save counts as a move (2) | the unit tests and the radius-only sequence / the cooldown sequence |
| the refresh reports success when nothing was stored | both 409 tests and the postcode sequence |
| the cooldown reads the wall clock | the 429 tests |
| the cooldown boundary made inclusive | the 1800 s case errors on the mutant's own 429 |
| the no-answer stamp skips the guard | the no-answer and 409 tests |
| the colour saved through the whole entity | the unit test and the colour sequence (`verdict` written back) |
| the radius no longer clamped | the clamp tests |
| the job stores an empty measurement / saves the user row back / stamps from the wall clock (3) | the job tests; the wall-clock one also by the nightly sequence |
| an answer with no valid duration collapses to no answer | the measurement test |

**Not proved here.** The Postgres-only properties — the compare-and-set re-checking its condition
after a lock wait, and the absence of a deadlock between a save and a store — rest on
`UserSettingsRowLockIntegrationTest`, which runs only in CI. Nothing was checked in the running app.

**Found on the way, not fixed here.** The shared region matrix has the same race: its nightly job
routes from a region's base and stores unconditionally, so an admin moving the base during that run
gets the old base's drive times. The account columns still left updatable (enabled, role, password
and the rest) are still written back whole by the JWT filter's last-active save, so an admin's
change landing in that gap can be undone.
