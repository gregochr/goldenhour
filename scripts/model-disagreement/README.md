# Model-disagreement sizing runbook (owner)

Does "other models see cloud" predict the mid and high cloud that `best_match` (UKMO-led) misses? This sizes
it on the forecast history we already hold, against ERA5, and ends in **pass / fail / inconclusive**.
Plan: [docs/engineering/model-disagreement-sizing-plan.md](../../docs/engineering/model-disagreement-sizing-plan.md).
Read-only: no production code, no migration, no Claude spend. Run everything from this directory. Outputs land
in `out/`; `*.csv`, `out*/` and `cache/` are git-ignored (the export holds production data).

It reuses `../far-cloud-p1/` (`openmeteo.py` cache and budget client, `geometry.py`, `slots.py`, `stats.py`,
`export_forecasts.sql`) and never edits it.

## 0. Prerequisites

- Python 3, standard library only. Nothing to install.
- Network to open-meteo.com for `md_probe.py` and `md_fetch.py` only. `md_sample.py` (until `--go`) and
  `md_size.py` touch no network.
- SSH access to the production host for the export (`<host>` below, e.g. `gregochr@<host>`).
- **One cache, one budget.** The scripts default to P1's `../far-cloud-p1/cache/`, so a single weighted-call
  counter covers both studies. **Never run this and a P1 fetch on the same UTC day.** If you hold a commercial
  Open-Meteo key, export it in your shell; never paste it into a file or onto a command line.

## 1. Export (read-only SQL; run once)

P1's export, unchanged. It carries every column needed:

```
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' \
  < ../far-cloud-p1/export_forecasts.sql > md_forecasts.csv
head -1 md_forecasts.csv        # starts evaluation_id,location_id,target_date,...
```

A psql error stops the export; an empty CSV means it failed. Do not commit the CSV.

## 2. T0: probe (a few calls)

```
python3 md_probe.py --no-wait
```

Defaults reproduce the 31 Oct 2026 Angel of the North sunset (`--lat --lon --az --date --event-time --type`
change it). It sends one single-model request per model (`ukmo_seamless`, `best_match`, `icon_seamless`,
`gfs_seamless`, `meteofrance_seamless`, `ecmwf_ifs025`) for low/mid/high and 700/500/300 hPa at the observer and
the three cone points, prints which are served and the cone value M per model, and records whether the Single
Runs API serves the layer bands (record only).

- Every model must serve low/mid/high (`[PASS]`); an unserved pressure level only costs `S2-PL` that model.
- **Real cost.** The weighted-call number it prints is P1's assumed formula and ignores `models`. Read
  Open-Meteo's usage (dashboard or response headers) **before and after** the run, and note the measured
  factor (real calls / printed calls). It goes into step 3 as `--call-weight`.

## 3. T2: sample (stops for your go)

```
python3 md_sample.py md_forecasts.csv --n 300 --call-weight <measured factor>
```

Deduplicates to units (latest pre-event run per location, date, type and lead), filters to the population,
and prints the funnel, the strata, the **expected fetch days** against the observed ~600 weighted calls per
day (`--daily-calls`), and a **simulated power table**. It then **stops, writing nothing**. Rough guide before
T0: about 18 calls per unit before grid-cell deduplication, so 600 units is about 18 days and 300 about 9.

Read the days and the power table. If you accept them, re-run the same command with `--go`:

```
python3 md_sample.py md_forecasts.csv --n 300 --call-weight <factor> --go     # writes out/fetch_list.csv
```

The sample is T+0 only, drawn proportionally to month within event with a fixed seed. The fetch list also
carries the 31 Oct trigger and the training cases (`role=worked`) for the worked-example table.

## 4. T3: fetch (resumable; takes days)

```
python3 md_fetch.py out/fetch_list.csv --budget <real daily limit> --no-wait
```

One request per model, grouped by date, deduplicated by date and 0.1 degree cell. It stops cleanly at the
budget, an hourly cap or a rate limit (`BUDGET REACHED`, exit 2): run the **same command** after 00:00 UTC and
it carries on from the cache. `--dry-run` prints what would go live. Add `--observer` last, and only if budget
allows (the observer-point secondary needs it; then pass `--observer` to `md_size.py` too).

## 5. T4: size (no network)

```
python3 md_size.py md_forecasts.csv --list out/fetch_list.csv        # add --observer if you fetched them
```

Writes `out/report.md` (the dedup counts and funnel, the **alignment control first**, the decision table
for the lead-fair population P' with full P beside it, lead bands, per-model, `S1`/`S3`/`S2`-without-GFS/`S2+E`/
`S2-PL` tables, outcome variants, baselines, the observer secondary, the worked examples, and the notes where
the plan was silent) and `out/units.csv` (one audit row per unit). A unit whose model never reached the cache is
"not voting", never 0, so a half-finished fetch gives an honest, smaller report.

## 6. Tests (no network)

```
python3 -m unittest discover -s tests
(cd ../far-cloud-p1 && python3 -m unittest discover -s tests)      # P1 must still pass
python3 -m py_compile *.py
```

`tests/fixtures/export_small.csv` is a synthetic export; `python3 tests/fixture_tools.py --csv
tests/fixtures/export_small.csv --cache /tmp/md-cache` fills a cache from it through the real scripts and the
model-switching fake, so `md_size.py` can be exercised end to end without the network.

## 7. Send back to Claude

- `out/report.md` (it holds no production identifiers beyond location names and dates).
- The measured cost factor from step 2, and anything `md_probe.py` marked `FAIL` or `INFO` about unserved models.
- The number of days the fetch actually took.

A **pass** means "write the capture plan" and is an upper bound (the comparators run at a short stitched lead,
plan section 3). A **fail** is recorded in the plan and `architecture-decisions.md`. **Inconclusive** is its
own outcome: it comes with the power table, and you decide whether to extend the sample.
