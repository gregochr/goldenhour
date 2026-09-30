### Changed — `survivor_atmosphere` and every class built around it are renamed to `slot_atmosphere`/`Slot*`

Since #947/#948 (2026-09-30, "record conditions for every place", Phases 1–2) this table holds a
row for EVERY candidate slot the pipeline fetched weather for that cycle — triaged-out and
Gate-4-stood-down candidates included — not only the ones that survived triage and Gate 4
("survivors"). "Survivor" was already a false name on the table, and on every class built around it,
the day those two PRs shipped; both said so and deliberately left the rename for its own change so
the behaviour change was not buried under a mechanical diff. This change is that rename, and it
changes no behaviour.

This repository's word for `(location, date, event_type)` is "slot" (`BriefingSlot`, "slot"
throughout CLAUDE.md), so the new names use it:

| Old | New |
|---|---|
| table `survivor_atmosphere` | `slot_atmosphere` |
| `SurvivorAtmosphereEntity` | `SlotAtmosphereEntity` |
| `SurvivorAtmosphereRepository` | `SlotAtmosphereRepository` |
| `SurvivorAtmosphereWriter` | `SlotAtmosphereWriter` |
| `SurvivorSignals` (+ nested `Readings`, `Scores`) | `SlotSignals` |
| `SurvivorSignalReader` | `SlotSignalReader` |

**Migration V159** renames the table and, separately, its primary key, its foreign key
(`fk_survivor_atmosphere_location` → `fk_slot_atmosphere_location`), its unique constraint
(`uq_survivor_atmosphere` → `uq_slot_atmosphere`) and its index
(`idx_survivor_atmosphere_date` → `idx_slot_atmosphere_date`) — Postgres does not rename a table's
dependent objects when the table itself is renamed, so each needed its own `RENAME CONSTRAINT`/
`RENAME TO`, including the primary key, which V115's `CREATE TABLE` never named explicitly and so
was auto-named `survivor_atmosphere_pkey`. A new Testcontainers migration test
(`SlotAtmosphereRenameMigrationTest`, CI-only) seeds a row under the old table name at V158, applies
V159, and asserts all five old names are gone, all five new names exist, and the row's data survived
untouched.

**The config flag is renamed too, with the old key still read.** `photocast.survivor-atmosphere.write`
is now `photocast.slot-atmosphere.write` (default `true`, unchanged). Production's `application.yml`
is not in this repository and may still set the old key, so `SlotAtmosphereWriter` binds both — the
old key wins when it is set, so a deployment that has not yet picked up the new key name keeps
behaving exactly as before. Once every deployment is updated, the legacy parameter can be deleted.

**Everywhere the old identifiers appeared as literal names — class references, `{@link}`/`{@code}`
javadoc, log lines, the entity's `@Table`/`@UniqueConstraint`/`@Index` annotations, and test fixture/
method names — is renamed to match.** A new unit test, `SlotAtmosphereEntityTest`, pins the entity's
`@Table` name and its constraint/index names to the `slot_atmosphere` spelling so a later refactor
cannot silently split the entity from the migration that creates the table it maps to.

**What is deliberately left alone.** Every migration file through V158, and both 2026-09-30
changelog entries from #947/#948, are immutable history and still say "survivor" — that is what the
table and classes were called on that date, and rewriting history would make an old log line or an
old changelog entry unreadable against the code as it stood then. CLAUDE.md's "Where a rating
lives" section and the "record conditions for every place" bullets now read the new names, with one
sentence noting the table was called `survivor_atmosphere` until this migration. A handful of
generic English uses of the word "survivor" — describing candidates that survived triage in
`ForecastCommandExecutor`, remaining best-bet picks in `BestBetRanker`/`BriefingBestBetAdvisor`, and
similar unrelated ranking/filtering code — are untouched, since they never named the renamed table or
classes in the first place. The frontend has no reference to any of these identifiers (confirmed by
search) and is untouched.
