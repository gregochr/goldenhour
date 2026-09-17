### Fixed — a fresh local H2 database boots more than once

On the local profile (H2 file database, no Flyway, `ddl-auto: update`) the app started once and then
failed on every later start with `Value not permitted for column "('SENTINEL_SAMPLING',
'TIDE_ALIGNMENT')": "SKIP_LOW_RATED"` from `OptimisationStrategyService.seedDefaults`. Hibernate 7
generates an `@Enumerated(STRING)` column on H2 as a native `ENUM(...)` of the enum's current
constants, and H2 refuses to compare that column with any other value — and the startup prune, by
design, names only the seven types V153 retired. The first boot got through because the table was
still empty: H2 converts the bound value to the column's type only when it compares it against a
row, so the first boot's seed rows are what made every later boot fail. Production runs Flyway,
whose column is a plain `VARCHAR(30)` (V41), and was never affected.

**The fix** is one clause: `OptimisationStrategyRepository.deleteByStrategyTypeIn` now compares
`CAST(strategy_type AS VARCHAR)`, which makes the comparison a string one on H2 and changes nothing
on Postgres. Mapping the column as VARCHAR so H2's schema matched V41 was tried first and rejected:
Hibernate then adds a `CHECK (strategy_type IN (...))` of the current constants that neither a
`columnDefinition` nor an `AttributeConverter` removes, and H2 2.4.240 stops evaluating such a
check once the connection that created the table closes (`Check constraint invalid ... The database
has been closed`), so every write to the table would have failed as soon as Hikari retired
Hibernate's schema-update connection — a worse defect than the one being fixed.
`OptimisationStrategyRepositoryTest` gains the second boot as a test: the prune's own seven-name
list against the ENUM column exactly as Hibernate generates it, and it fails with the production
error when the cast is deleted. Its pre-V153 fixtures moved out of `@BeforeEach` so that test runs
on the untouched schema.

Still true, and unchanged: a local database built before an enum gained a constant refuses the
`INSERT` seeding it, because `ddl-auto: update` never widens an existing column. That is every H2
enum column's rule — reset the local database after adding a constant — not this table's.
