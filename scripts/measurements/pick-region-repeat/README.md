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
docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 < repeat.sql
```

The container is `goldenhour-db` and the role is `goldenhour` — both read from
`application-prod.yml`'s datasource, not guessed. (The first attempt guessed a `name=postgres`
filter, which matches no container on the host, and the wrong role.) Run it from the directory
holding the file, or pipe the file's contents in with a heredoc.

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

`./verify.sh` runs `repeat.sql` **unmodified** against SQLite over four
fixtures whose expected answer was taken from the **real `PlanWindowProjector`**, not from reading
this SQL back. All four agree:

| fixture | real `selectPicks` | reconstruction |
|---|---|---|
| A — same region, only differing window 1.0 back | `BEST Lakes 5.0`, `ALSO Lakes 4.5` | same-region ✓, forcing → **silence** |
| B — same region, a differing window ties at 4.5 | `BEST Lakes 5.0`, `ALSO Lakes 4.5` | same-region ✓, forcing → **finds one, costs 0.0** |
| C — control, picks already differ | `BEST Lakes`, `ALSO Dales` | same-region false ✓ |
| D — control, runner-up 1.0 back | `BEST Lakes`, **no** `ALSO` | `awarded` false ✓ |

This is why `repeat.sql` uses `ROW_NUMBER() … = 1` rather than Postgres' `DISTINCT ON`: the
window-function form runs unchanged on SQLite, which is what makes the file executable on a machine
with no Postgres and no Docker. It proves the **logic**; it does not prove Postgres syntax, and the
first production run is where that is established.

## ⚠️ What it does NOT model

Stated here because a number from an unqualified reconstruction is exactly how the label-obstacle
measurement went wrong eight times (`map-landing-plan.md` §4b.1).

- **Gloss eligibility.** `selectPicks` skips a window whose top region has no usable Claude gloss
  headline (`candidate() != null`); the snapshot table stores no gloss. This can only *add* eligible
  windows, so `awarded` here is an **upper bound** and `same` is measured over a
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
  look like two, which biases `same` **downward**.

## Reading the output

`forcing_silence` is the column that decides Q5. The choice is not "same region twice" versus
"two different regions" — it is "same region twice" versus, in some fraction of builds, **no second
pick at all**, because any differing window is further down a rating-sorted list and still has to
clear `AlsoGoodFloor`. `AlsoGoodFloor`'s own javadoc states the principle that fraction has to be
weighed against: *"An honest silence is better than a padded recommendation."*

## Result — production, 2026-09-11 — Q5 DECIDED: leave it

39 builds, 2026-08-20 → 2026-09-11 (~23 days, two builds a day).

| | builds | distinct days |
|---|---|---|
| with two picks | 21 of 39 | 12 |
| both picks the **same region** | **10 (47.6%)** | 6 of 12 |
| …forcing a different region **finds one** | 2 | 1 |
| …forcing a different region yields **no second pick** | **8** | 5 |

**The repeat is common, and forcing it apart would mostly delete the second pick rather than
replace it.** In 8 of the 10 same-region builds the best *different* region fails
`AlsoGoodFloor`: six are below the 3.0 floor (2.2–2.7★), one is 1.0★ behind the leader, and one
build has no second region at all. So the design spec's both-differ rule would have removed ALSO
GOOD from 8 of the 21 builds that carried one — about 40% — to avoid repeating a region's name.

The only case where forcing finds a qualifying alternative (09-04, both builds, costing 0.2★) sits
**exactly** on `AlsoGoodFloor`'s inclusive 0.5 gap (3.7 vs 3.2) — the `NUMERIC(3,1)` rounding case
above. If the true gap is 0.51, production refuses it too, and forcing yields silence in all ten.

⚠️ **How far the numbers hold.**
- **Builds come in near-identical pairs** (01:xx and 14:xx on the same day), so 39 builds is closer
  to ~20 independent observations. The day column is the more honest of the two, and agrees.
- **The silence finding is robust to rounding** — none of the eight is near a boundary. The
  *frequency* is softer: three of the ten same-region rows sit on an inclusive boundary for
  `awarded` itself (08-30 twice at a gap of exactly 0.5; 09-02 with ALSO at exactly 3.0), so the true
  rate is roughly **39–48%**. Common either way.
- `awarded` is an upper bound (no gloss data), and three weeks of late summer is a modest, seasonal
  sample.

None of that sits near a boundary that would change the decision, so this instrument was sufficient
and the heavier run through the real `PlanWindowProjector` — reserved above for close calls — was not
needed. `PlanWindowProjectorTest.picksMayNameTheSameRegion` now pins the decision, with a fixture a
both-differ rule would answer differently; mutation-tested, it is the **only** test in the suite that
fails when that rule is added.
