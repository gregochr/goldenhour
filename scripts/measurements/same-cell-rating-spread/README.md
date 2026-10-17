# Same-cell rating spread

The instrument behind **`docs/engineering/same-cell-rating-spread-plan.md`**. It answers one question:

> When the batch pipeline rates places that see the same sky, in the same run, for the same window,
> with the same model, how often do the stars disagree by two or more?

It exists because of a lone 4★ at Caw Gap (Sunday 18 Oct 2026 sunrise, T+2, Haiku) that Ask
offered as its top pick while its neighbours, given an almost identical prompt, were rated 1★ and
2★. Nothing in the pipeline compares a rating to its neighbours, so this measures how often that
happens and on which model.

**Read-only.** Every file is `SELECT` or `CREATE TEMP VIEW` (session-scoped, gone on exit).

## Running it

The queries run on the production host. Pipe them from this machine (the files are here, not
there, so `\i` and `< file.sql` on the host do not work):

```bash
D=scripts/measurements/same-cell-rating-spread
R="docker exec -i goldenhour-db psql -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -q"
SINCE=$(date -v-60d +%F)   # the last 60 days; GNU date: date -d '60 days ago' +%F

cat $D/cell-groups.sql $D/summary.sql          | ssh dockermacmini "$R -v since=$SINCE"
cat $D/cell-groups.sql $D/top-cells.sql        | ssh dockermacmini "$R -v since=$SINCE"
cat $D/cell-groups.sql $D/neighbour-pairs.sql  | ssh dockermacmini "$R -v since=$SINCE -v km=12"
cat $D/cell-groups.sql $D/guard-candidates.sql | ssh dockermacmini "$R -v since=$SINCE -v km=12"
cat $D/far-cost.sql                            | ssh dockermacmini "$R -v since=$SINCE"
```

`cell-groups.sql` defines three temp views the next four files read, so it always goes first.
`far-cost.sql` stands alone.

## What each file measures

| file | unit | question |
|---|---|---|
| `summary.sql` | a **same-cell group**: 2+ rated places sharing `locations.grid_lat`/`grid_lng`, one window, one run, one model, one horizon | how many groups span 2+ and 3+ stars, by model × `days_ahead` |
| `top-cells.sql` | the same groups, per cell | which cells disagree most |
| `neighbour-pairs.sql` | a **near-twin pair**: two places within `:km` km, same run, window, model and horizon, whose solar-horizon low/mid/high cloud agree to within 5 points | how often near-identical inputs get stars 2+ apart |
| `guard-candidates.sql` | a rated row with at least one near-twin | how many 3★+ ratings sit 2+ stars above every near-twin (what a neighbour guard would flag) |
| `far-cost.sql` | a successful batch sky call (`custom_id` `fc-…`) in `api_call_log` | calls a day and cost a call, by model × horizon |

## Definitions that matter

- **Places counted.** Every location with a grid cell and no WOODLAND, BLUEBELL or WILDLIFE type.
  A canopy site is rated by the woodland prompt, a hide is never rated, so neither is a sky twin.
- **The rating.** `forecast_evaluation.rating`, the served star. For a coastal tidal place it is the
  sky component averaged with that place's own tide score, so two tidal neighbours can differ for a
  reason that is not noise. Every table therefore also reports an **inland** column (no tidal member)
  and, where every member has one, a **sky** column on `sky_rating` (the sky component alone,
  written by the batch since V164). Read the inland and sky columns as the noise measure.
- **One run.** `forecast_run_at` truncated to the HOUR, not the minute. A nightly batch stamps its
  rows over about eight seconds starting near 01:07:5x UTC, so a minute key splits one run's cell
  into two singleton groups and under-counts; the nightly (01:0x) and intraday (13:1x) cycles are
  hours apart, so an hour key cannot merge two runs.
- **Exact cell vs near twin.** `grid_lat`/`grid_lng` is the point Open-Meteo returned for the
  location's own coordinates (`GridCellBackfillJob`), about 2 km on a side. It is finer than the
  sampling some prompt inputs actually come from: Caw Gap and Cawfields sit in different cells yet
  received identical observer-point cloud. That is why the near-twin measure exists beside the
  brief's exact-cell one.
- **Distance** is equirectangular (`111.32 km × √(Δlat² + (Δlon·cos lat)²)`), accurate to well under
  1% at 12 km.

## Portability and validation

`cell-groups.sql`, `summary.sql`, `top-cells.sql`, `neighbour-pairs.sql` and
`guard-candidates.sql` use no `DISTINCT ON`, `date_trunc` or interval arithmetic, and run unchanged
on sqlite 3.43 once `:'since'` and `:'km'` are substituted. They were checked there against a
seven-location fixture whose answers were worked out by hand: a three-site inland cell spanning 2
stars, a two-site tidal cell spanning 3, a canopy site that must be excluded, a twin pair 30 points
apart on solar low cloud that must not pair, a run straddling a minute boundary that must stay one
group, and a row before `since` that must be dropped. `far-cost.sql` subtracts dates and is
Postgres-only.

## Results

`results.md` holds the run of 2026-10-16. The numbers will age; the instrument is the point.
