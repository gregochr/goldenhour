# Open-Meteo cool-off: stop asking when Open-Meteo is refusing or struggling

**Status:** planned 2026-11-02. One PR, one file of production code (`OpenMeteoClient`).

## What happened (production, 2026-11-02, UTC)

- 12:29–12:34 the scheduled cycle fetched cleanly (forecast 11/11, air quality 11/11, cloud 59/59,
  pressure-level 226/226).
- 12:46 the late-batch briefing rebuild (`LateBatchBriefingRebuild`) started its own fetch. Chunks 6
  and 7 each hit the 30 s read timeout; the in-method retry tried again after 2 s and 4 s.
- From 12:48 every request was answered `429 {"reason":"Too many concurrent requests"}`, although we
  send one chunk at a time. The 61 s "minutely" backoff was applied between every chunk, so the run
  spent about nine more minutes sending requests that were all refused: briefing batch 5/11, the next
  batch 0/5, briefing 136/263 slots.
- A single `curl` from the host was still refused 30+ minutes later, then got a 503.

Likely reading: Open-Meteo was struggling; a request our client abandons on a read timeout keeps
running on their side, so quick retries pile up concurrent requests against our address. We cannot
prove that from here, but both changes are correct client behaviour either way.

## The two changes

### 1. Longer backoff after a read timeout

In `fetchBriefingChunkWithRetry` and `fetchAirQualityChunkWithRetry` (the only in-method retries),
when the failure is a **read timeout** (a `java.net.SocketTimeoutException` anywhere in the cause
chain of the `ResourceAccessException`), wait `TIMEOUT_RETRY_BASE_BACKOFF_MS` = 30 000 ms before the
first retry and 60 000 ms before the second (base × 2^attempt), instead of 2 s / 4 s. Connection
resets, connect timeouts that are not read timeouts, and 5xx keep 2 s / 4 s. A test-overridable field
`timeoutRetryBackoffMs`, like `chunkRetryBackoffMs`.

Note a connect timeout is also a `SocketTimeoutException` ("Connect timed out"). Treat any
`SocketTimeoutException` the same: either way the server is not answering promptly, and a longer wait
is the polite response.

### 2. A client-wide cool-off

`OpenMeteoClient` (a singleton) holds `volatile long coolOffUntilMs` and a reason string. When it is
in force, every **batch chunk** request fails fast without touching the network, and the batch loops
stop instead of walking the remaining chunks.

**What starts a cool-off** (decided once, in one private method fed the caught exception):

| Signal | Cool-off |
|---|---|
| 429 whose message contains `Too many concurrent requests` | 10 minutes |
| 429 whose message contains `Hourly API request limit` | until the next whole UTC hour |
| 429 whose message contains `Daily API request limit` | until the next UTC midnight |
| A chunk that exhausted all its retries **and** whose last failure was a `SocketTimeoutException` | 10 minutes |
| Any other 429 (the minutely limit, or a 429 with no recognised reason) | **none**: keep today's 61 s backoff |

The reason strings are matched case-insensitively on the exception message, which already carries the
response body (production logs show `429 Too Many Requests: "{"error":true,"reason":"Too many
concurrent requests"}"`).

A later signal can only **extend** a cool-off (`max` of the two end times), never shorten it.

**Where it is enforced:** at the top of the two chunk chokepoints, `fetchForecastChunk` (the 5-argument
one every forecast, cloud, pressure-level and inversion-profile chunk goes through) and
`fetchAirQualityChunk`. While cooling off they throw `OpenMeteoCoolOffException` (a new
`RuntimeException` subclass, package-private or nested is fine; its message must **not** contain
"429", so no loop mistakes it for a rate limit and no retry classifier treats it as transient).

**The loops** (`fetchForecastBriefingBatchBody`, `fetchAirQualityBatchResilientBody`,
`fetchForecastBatchInternal`, and the in-method retries):
- After any chunk failure, if a cool-off is now in force, **break** out of the loop: one WARN line
  naming the reason, the end time, and how many chunks were left unfetched; the remaining positions
  stay `null` (callers already guard nulls). No 61 s sleep is spent before breaking.
- A chunk that finds the cool-off already in force (thrown before the request) is recorded through
  `recordChunk` as a failure like any other, so the batch pre-fetch job-run rows still show it; the
  remaining unfetched chunks after the break are **not** each recorded (one WARN covers them).
- The in-method retries never retry an `OpenMeteoCoolOffException`, and stop retrying the moment a
  cool-off starts.
- `fetchPressureLevelCloudBatch` and `fetchInversionProfileBatch` already stop on their first failed
  chunk; they need nothing but the chokepoint check.
- The single-chunk paths (`coords.size() <= BATCH_COORD_LIMIT`) simply throw the exception, as they do
  for any other failure.

**Not in scope:** `fetchForecast` / `fetchAirQuality` (single-location calls through the declarative
`forecastApi` / `airQualityApi`), the health probe `OpenMeteoHealthIndicator`, `MarineClient`, the
Resilience4j YAML. A cool-off exception thrown out of a decorated single-chunk call may count as a
breaker failure; that is acceptable (the breaker opening during a cool-off changes nothing).

**Time source:** the existing test-overridable `LongSupplier elapsedClockMs` (wall clock in
production) for "now"; the hour/midnight ends are computed in UTC from it.

**Logging:** one WARN when a cool-off starts (`Open-Meteo cool-off until HH:mm UTC: <reason>`), one
WARN per loop that breaks on it. No per-chunk spam during a cool-off.

## Tests (`OpenMeteoClientTest`, existing style, exact matchers per `backend/AGENTS.md`)

1. Read timeout retry waits 30 s then 60 s (assert via the overridable field and a recorded sleep, or
   by the backoff values logged/passed, whichever the existing retry tests use); a 5xx still waits the
   short backoff.
2. `Too many concurrent requests` on chunk 2 of 5: chunks 3–5 make **no** HTTP request, positions stay
   null, chunks 1's results survive, no rate-limit sleep is spent.
3. A second batch call made during the cool-off makes no HTTP request at all and returns all nulls
   (multi-chunk) or throws `OpenMeteoCoolOffException` (single chunk).
4. After the clock passes the end, requests flow again.
5. A minutely 429 (or a bare 429) keeps today's behaviour: 61 s backoff, the next chunk is still tried.
6. Daily → ends at next UTC midnight; hourly → next whole hour; extension never shortens.
7. A chunk whose three attempts all read-time-out starts a 10-minute cool-off; one that times out then
   succeeds does not.
8. Air-quality resilient batch: same break behaviour.

Coverage stays ≥ 80 % per class (`jacoco:check`).

## Docs

`docs/engineering/reference/pipeline.md`, the **Resilience** paragraph: one sentence on the cool-off
and the timeout backoff.
