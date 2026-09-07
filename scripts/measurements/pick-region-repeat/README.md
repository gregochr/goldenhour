# Q5 — do the two picks name the same region, and what would forcing them apart cost?

`docs/engineering/map-landing-plan.md` §6 **Q5**, argued at §4 **#2**.

`PlanWindowProjector.selectPicks` ranks **windows** and gives each pick its own window's top region.
Differing in *window* is guaranteed by construction — each window appears once. Differing in
*region* is not constrained at all, so BEST BET and ALSO GOOD can name the same place on two
nights. That is reachable, not theoretical: two days both topped by one region produce
`BEST/Lakes 5.0` and `ALSO/Lakes 4.5` through the real projector.

The open question is **how often**, and **what forcing them apart would actually cost** — which is
what this measures.

## Running it

```bash
docker exec -i <pg-container> psql -U <user> -d <db> -f repeat.sql
```

Read-only with respect to your data. The only object it creates is one session-scoped `TEMP VIEW`,
dropped at the end, so the reconstruction has a single definition instead of being pasted twice for
the two reports.

## Why `briefing_region_snapshot` and not the briefing cache

`daily_briefing_cache` is a **single-row** table (`id = 1`, upserted on every build), so it holds
only the latest briefing and can answer a frequency question for exactly one build. V144's
`briefing_region_snapshot` is the run-to-run sink: one row per region × date × event **per build**,
carrying `mean_rating` — the voting-slot region mean that `selectPicks` ranks on
(`Draft::averageRating`). That is why the reconstruction can read it directly rather than
re-deriving a mean from locations.

⚠️ **The table has only existed since 2026-08-19** (V144), and retention is 90 days. Early runs
therefore have a much shorter window than 90 days — check `builds` in the headline before reading
any percentage as stable.

## Validation — the logic is tested, the dialect is standard

`./verify.sh` runs `repeat.sql` **unmodified** (bar psql's `\echo` lines) against SQLite over four
fixtures whose expected answer was taken from the **real `PlanWindowProjector`**, not from reading
this SQL back. All four agree:

| fixture | real `selectPicks` | reconstruction |
|---|---|---|
| A — same region, only differing window 1.0 back | `BEST Lakes 5.0`, `ALSO Lakes 4.5` | same-region ✓, forcing → **silence** |
| B — same region, a differing window ties at 4.5 | `BEST Lakes 5.0`, `ALSO Lakes 4.5` | same-region ✓, forcing → **finds one, costs 0.0** |
| C — control, picks already differ | `BEST Lakes`, `ALSO Dales` | same-region false ✓ |
| D — control, runner-up 1.0 back | `BEST Lakes`, **no** `ALSO` | `also_awarded` false ✓ |

This is why `repeat.sql` uses `ROW_NUMBER() … = 1` rather than Postgres' `DISTINCT ON`: the
window-function form runs unchanged on SQLite, which is what makes the file executable on a machine
with no Postgres and no Docker. It proves the **logic**; it does not prove Postgres syntax, and the
first production run is where that is established.

## ⚠️ What it does NOT model

Stated here because a number from an unqualified reconstruction is exactly how the label-obstacle
measurement went wrong eight times (`map-landing-plan.md` §4b.1).

- **Gloss eligibility.** `selectPicks` skips a window whose top region has no usable Claude gloss
  headline (`candidate() != null`); the snapshot table stores no gloss. This can only *add* eligible
  windows, so `also_awarded` here is an **upper bound** and `same_region` is measured over a
  slightly larger population than production used.
- **`rendered`.** `PlanRenderLimits.MAX_VISIBLE_EVENTS = 6` is taken here as the six earliest solar
  windows in the build. The real filter is the set the rail actually drew, which also drops elapsed
  windows — so this can admit a window production had already retired.
- **The tie-break.** `BY_PICK_RANK` splits equal averages on the window's earliest **event time**;
  this uses `(date, SUNRISE before SUNSET)`, which orders identically but cannot split an exact tie
  the way a clock time would.
- **⚠️ Rounding, and this one can flip a verdict.** `mean_rating` is `NUMERIC(3,1)`, while
  `Draft::averageRating` is a full `double`. `AlsoGoodFloor`'s bounds are **inclusive** (`>= 3.0`,
  gap `<= 0.5`), so a pair whose true gap is 0.51 stores as 0.5 and qualifies here while production
  refused it. Expect this to matter only at the boundary — but if the headline lands near a decision
  point, do not split it with this instrument. Drive the real `PlanWindowProjector` instead.
- **Region renames.** The join is on region *name*, and `RegionService.setName` makes a rename an
  ordinary admin action (V137 renamed two). A rename inside the measured window makes one region
  look like two, which biases `same_region` **downward**.

## Reading the output

`forcing_yields_silence` is the column that decides Q5. The choice is not "same region twice" versus
"two different regions" — it is "same region twice" versus, in some fraction of builds, **no second
pick at all**, because any differing window is further down a rating-sorted list and still has to
clear `AlsoGoodFloor`. `AlsoGoodFloor`'s own javadoc states the principle that fraction has to be
weighed against: *"An honest silence is better than a padded recommendation."*
