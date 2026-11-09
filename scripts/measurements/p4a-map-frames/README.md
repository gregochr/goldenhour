# P4a — map-frame performance driver

The Playwright / CDP driver behind the P4a numbers in
`docs/engineering/performance-review-2026-10-plan.md` ("P4a measurement detail" and "After P4b and
P4c"). It logs in as the local admin, throttles the CPU 4x, synthesises drag, pinch and wheel
gestures on the Map tab for 10 s each, and buckets per-frame costs into count / median / p90 / p99 / max.

## Before you run it

- **It needs temporary instrumentation that is not in this directory.** The driver reads
  `performance.measure` entries named `p4a:drawTiles`, `p4a:labels.paint` and `p4a:labels.layout`.
  Those marks were put round the `drawTiles` call in `MapHeatLayer` and round `MapLabels`' `paint` and
  layout effect by hand, never committed (the header comment's `instrumentation.diff` was not kept).
  Re-add them locally, and do not commit them.
- A local backend on 8083 (`APP` / `API` env vars override the URLs; default app port is 5190) with an
  invalid Anthropic key so nothing can bill, a dense roster (`scripts/dev-seed-locations.sh --dense`) and
  synthetic `cached_evaluation` ratings for today to +3.
- It hard-codes the default local login (`admin` / `golden2026`); run it only against a local backend.
- Run it on an idle host. A saturated host inflates every millisecond figure.

```bash
cd frontend && node ../scripts/measurements/p4a-map-frames/measure.mjs
# RUNS=2 GESTURE_MS=10000 OUT=<dir> are the other knobs
```
