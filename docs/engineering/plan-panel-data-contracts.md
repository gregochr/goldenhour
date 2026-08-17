# Plan-tab panel data contracts — should each panel have its own REST API?

**Status:** Decision recorded. No code change in this document.
**Asked during:** the "Close to home" feature (`feature/close-to-home`), whose selection logic
ships as a client-side derivation and so raised the question directly.
**Method:** investigated against the real code. Every load-bearing claim below carries a
`file:line`. Where a claim is an estimate rather than a measurement, it says so.

---

## TL;DR — the two facts most needed

1. **The correct seam is not "panel". It is shared-vs-per-user, and this codebase already
   enforces it, tests it, and has decided it once before.** The briefing payload is a single
   shared snapshot with no notion of a caller; per-user data is deliberately kept off it and off
   the ETag whitelist for a *security* reason, not a caching one. Splitting the four snapshot
   panels into four endpoints would buy nothing and cost cross-panel consistency.

2. **"Close to home" is the one panel that genuinely earns its own contract — and it earns it by
   data ownership, not by being a panel.** It is per-user by construction (home postcode +
   per-user drive times). Both endpoints it depends on are already named in the ETag exclusion
   list *by path*, with "home-derived personal data" as the stated reason, pinned by a test.
   It therefore must never ride `GET /api/briefing`; if its logic moves server-side it needs its
   own authenticated, non-ETag'd endpoint.

**The precedent that settles it:** per-user drive times were already removed from the shared
briefing build path, and the dead parameter still carries the note —
`BriefingBestBetAdvisor.java:192` and `:487`: `@param driveMap unused — retained for API
compatibility (pass Map.of())`. This exact question has been answered once, the same way.

---

## The question

The Plan tab renders five panels. Four of them are derived **client-side** by slicing one
`GET /api/briefing` payload. Should each panel instead be a distinct entity with its own REST
contract?

The question has force because `CLAUDE.md` states an explicit principle —

> **Backend-heavy** — all calculations on backend. Frontend is a pure render layer.

— and several panels plainly carry business logic in JavaScript.

## Section 1 — what the panels are today

| # | Panel | Source | Where it is shaped | Presentation or business logic? |
|---|---|---|---|---|
| 1 | Best Bet / Also Good | `GET /api/briefing` (`bestBets`) | Backend `BriefingBestBetAdvisor` + serve-time fallback | Almost entirely backend |
| 2 | **Close to home** | `/api/briefing` + `/api/user/settings*` | **Frontend** `utils/closeToHome.js` | **Business logic — geospatial gate + ranking** |
| 3 | Hot topics | `GET /api/briefing` (`hotTopics`) | Backend strategies | Backend |
| 4 | Briefing summary strip / day rail | `GET /api/briefing` | Backend `PlanWindowProjector` (`BriefingDay.peak`) | Backend, in the v2 arm. Frontend `buildSummaryPills` remains in the frozen v1 arm |
| 5 | Full briefing grid | `GET /api/briefing` | Backend `BriefingRegion.meanRating` + `displayVerdict` | Backend, behind the `serverCellRating` caller opt-in; v1 keeps its own derivation |

The Plan tab is **already multi-request** — it is not a monolith being defended. `DailyBriefing`
alone calls five endpoints (`/api/briefing`, `/api/briefing/evaluate/scores`,
`/api/astro/conditions`, `/api/user/settings/drive-times`, `/api/travel-days`), plus
`getSettings()` from `App.jsx`. So "give each panel an endpoint" is an extension of a pattern
already half-adopted, not a departure from a single fat call.

## Section 2 — the seam, with evidence

**The briefing payload is shared, not per-user.**
- `BriefingService.java:104` — `private final AtomicReference<DailyBriefingResponse> cache`.
  One reference for the whole application.
- `DailyBriefingCacheEntity.java:16,28-31` — persisted to a literal **singleton row**:
  *"The row with `id = 1` always holds the most recent generated briefing"*, `/** Singleton row
  identifier — always 1. */`.
- `BriefingController.java:57` — `public ResponseEntity<DailyBriefingResponse> getBriefing()`.
  **No parameters. No `Authentication`.** The endpoint cannot see who is asking.
- `BriefingService.java:387` — the horizon is `List.of(today, today.plusDays(1),
  today.plusDays(2), today.plusDays(3))`: four dates, identical for everyone.

**Per-user data is excluded from the ETag whitelist for a security reason.** This is the part
that decides the question, and it is already documented at `HttpCachingConfig.java:48-66`:

> Enabling an ETag requires `Cache-Control: private, no-cache`, which lets the browser persist
> the body to its on-disk HTTP cache (that is how a 304 reconstructs it) — and, unlike the
> localStorage SWR cache, **the browser HTTP cache cannot be evicted from JavaScript on logout**.
> So anything carrying user-authored text or home-derived personal data stays on Spring
> Security's `no-store` default rather than lingering at rest on a shared machine.

The list names, by path, `/api/user/settings` and `/api/user/settings/drive-times` — *"home
postcode / lat-lon and home-proximity data"*. Those are **exactly** the two endpoints Close to
home depends on.

**And the exclusions are pinned so they cannot be quietly reversed.**
`HttpCachingConfigTest.java:84-88` — `personalDataPathsAreNeverFiltered`, parameterised over
those same paths.

**Consequence:** enriching the shared `/api/briefing` payload with a per-user Close-to-home block
is not merely awkward, it is **excluded by an existing, tested, security-motivated rule**. Either
the payload leaves the ETag whitelist (losing the 304 that every other panel benefits from), or
one user's home-derived data becomes reachable from a shared cache entry. Neither is acceptable,
so that option is off the table — not on taste, on a rule already written down.

## Section 3 — the decision

**Do not give each panel its own REST API.** Split by *data ownership* instead:

- **Panels 1, 3, 4, 5 — the shared forecast snapshot.** These are four *views of one answer*.
  They should keep deriving from one payload. Splitting them multiplies requests against a
  payload that is already ETag-revalidated to a **304**, and it reintroduces cross-panel
  divergence that this codebase deliberately engineered out. Two panels fetching independently
  can show 4★ and 3★ for the same location. Consistency beats modularity here.

  ⚠️ **`DailyBriefing.jsx`'s claim that one source makes "strip/grid disagreement structurally
  impossible" was too strong, and this document repeated it.** One source removed disagreement
  about the *data*; it said nothing about the *aggregators*. Three surfaces went on reducing that
  one payload three different ways — the window row by the max rating, the grid cell by the region
  mean, the day card by an any-of over region verdicts — and on 2026-08-16 production showed a row
  reading "Worth it · best 4★" above a grid of "Poor" cells. Verified and fixed in
  `plan-verdict-consolidation-plan.md`: since Phase 3 the backend computes each of the three and
  the client renders them, so the claim now holds for the aggregators as well. The lesson worth
  keeping is that "one payload" is a necessary condition and was mistaken for a sufficient one.

- **Panel 2 — Close to home.** Per-user by construction. This one earns its own endpoint, and
  it earns it because of who owns the data, not because it is a panel.

The general rule, stated so the next feature does not have to re-derive it:

> A panel earns its own REST contract when it answers a **different question about differently
> owned data**. A panel that is another view of the same snapshot does not — it should be derived
> from that snapshot, on whichever side of the wire the snapshot already lives.

## Section 4 — what this means for Close to home

The logic in `frontend/src/utils/closeToHome.js` is ~360 lines of genuine business logic —
a haversine distance gate, a GO/MAYBE filter, a dedupe-by-location, and a ranking comparator.
Consequences of it living in JavaScript:

- It **violates the backend-heavy principle** in `CLAUDE.md`, plainly.
- It **cannot be reused** by any non-web consumer. Two are on the roadmap: Web Push notifications
  and the macOS menu bar widget. "What's good near me tonight" is exactly the kind of thing a
  push notification wants, and it cannot call a JS module.
- It **cannot be covered by the backend test suite**, so it is invisible to the calibration and
  metrics tooling that exists for every other scoring decision.

Against that: it works, it is covered by 50 frontend tests, and the design handoff explicitly
mandated the client-side approach (*"No new backend, no new settings — one new client-side
derivation"*). The handoff and the architecture principle disagreed; the handoff was followed.

**Recommendation: ship as-is, follow up with the endpoint.** The branch is green and delivers the
handoff. Moving the ranking server-side is a contract change deserving its own PR, and there is a
natural forcing function: the first time push or the widget needs "what's good near me", the
logic *has* to move — and that is the right moment to design the contract, with a second consumer
actually in hand rather than imagined.

## Section 5 — migration path, when it comes

In priority order.

1. **`GET /api/briefing/close-to-home`** — authenticated, reads the caller's home from
   `UserSettings`, returns the ranked cards + breadcrumb. **Must NOT be added to
   `REVALIDATABLE_READ_PATHS`** (Section 2). Port `closeToHome.js` to a service; the 31 unit
   tests transfer almost directly, as they are pure-function tests over fixtures.
2. **Join on `location_id`, not on the location name.** The client currently matches briefing
   slots to the locations roster by **name string**, so renaming a location silently empties the
   block. The FK already exists (V47).
3. **Make `RADIUS_MILES` server-side configurable**, which is the precondition for ever making it
   a user setting without a client release.
4. *(Separate concern. Measured since this was written and demoted — see Section 7.)* Trim the
   briefing payload.

## Section 6 — the strongest argument against this decision

**That "shared vs per-user" is an implementation detail of today's caching, not a durable
architectural boundary.** If PhotoCast ever personalises the *forecast itself* — ranking regions
by the user's drive time, or hiding regions beyond a travel radius — then Best Bet and the
summary strip become per-user too, the shared snapshot dissolves, and the seam this document
draws moves underneath it. At that point per-panel endpoints look much more reasonable, because
every panel would be per-user and the consistency argument for one shared payload would be
substantially weaker.

That is a real risk and it is not remote: per-user drive times *already* exist, and the fact they
were explicitly removed from the shared path (`BriefingBestBetAdvisor.java:192`) shows the
pressure has been felt once already. The counter is that the removal went the *other* way — the
codebase chose to keep the shared snapshot shared — and that reversing it is a much larger
decision than this document is scoped to make.

## Section 7 — the adjacent finding: MEASURED, and it was not what this document predicted

> **Corrected 2026-07-28.** This section originally called a payload trim *"likely a bigger and
> cheaper win than restructuring contracts"* on the strength of a research agent's ~770 KB
> estimate, while flagging that estimate as unverified. It has now been measured against
> production, and **the prediction was wrong**: the payload is roughly twice that raw, and almost
> irrelevant on the wire. The original wording is left described rather than deleted, because the
> useful lesson is that the number the document told you to measure is exactly the number that
> overturned its recommendation.

**Production ground truth** (`daily_briefing_cache`, 2026-07-28): **1,338,649 bytes raw**, from
**242 enabled locations across 7 regions** — 242 × 4 dates × 2 event types = **1,936 slots**, at
~691 bytes each. The 4 dates are `BriefingService.java:387`.

**Why the trim is not the win it looked like.** Gzip is on in production for `application/json`
(`application-prod.yml:162-165`), and this payload is highly repetitive:

| | Measured |
|---|---|
| Raw | 1.28 MB |
| **Gzipped — the actual wire cost** | **~133 KB** (~10×) |
| `JSON.parse` (desktop Chrome) | 5.2 ms |
| Re-`stringify` for the SWR cache | 4.4 ms |
| `localStorage.setItem` | 10.3 ms |
| **Total main-thread per fetch** | **19.9 ms** |

Two things fall out that the estimate hid. First, **the wire cost was never the problem** — 133 KB
is under two main JS bundles, on a payload fetched once per mount and then ETag-revalidated to a
304. Second, **only a quarter of the client cost is the payload at all**; the remaining ~15 ms is
the SWR cache re-serialising and synchronously writing the object that was just parsed
(`swrCache.js:41-46`, called at `DailyBriefing.jsx:1046`). *That* is the cheap win, and it is not
a payload-shape problem.

The genuinely material size finding is a **quota** one, not a bandwidth one: at ~2.6 MB of UTF-16
the briefing alone takes ~52% of iOS Safari's ~5 MB `localStorage` budget, before `useForecasts`
writes its own (larger, modelled ~2.0 MB) payload. If the pair exceeds quota, `writeSwrCache`
swallows the error silently and instant paint is dead on the field device, invisibly. That is
tracked separately from this document.

What *is* verified about the shape of the waste, and still stands:

- The **only** panel that needs the whole slot tree is the full briefing grid — and it is
  **collapsed by default** and does not render on a fresh session
  (`DailyBriefing.jsx:936-938`, render gate at `:1509`).
*(Two bullets that stood here — the grid recomputing each cell's mean rating in the browser, and
`BriefingEventSummary` carrying no event time — are **deleted rather than struck through**, because
both are fixed and a list of fixed debt is a list nobody can act on. `BriefingRegion.meanRating` now
publishes the cell's mean from the same statistics as its verdict word, read behind the
`serverCellRating` caller opt-in; the summary has carried `solarEventTime` since well before this
section was last revised, and each window carries its own `eventTime`.)

So the Plan tab does ship a large slot tree on every cold load, mostly to satisfy a panel that is
usually not rendered — and a summary-shaped payload with the slot tree fetched on grid expansion
is still the right *shape*. But it is now a **low-priority** change, not the headline win: it
would save ~133 KB of gzipped transfer and ~5 ms of parse, against a real risk of the summary and
the grid disagreeing (Section 3). Do it for tidiness, not for speed.

**Where the server cost actually is**, established while measuring the above: every
`GET /api/briefing` runs `applyBestBetFallback(BriefingHonestyFilter.apply(reEnrichVerdicts(...)))`
(`BriefingService.java:271-275`) — a DB query, a full walk *and reallocation* of all 1,936 slots
(the records are immutable, so `enrichWithCachedScores` rebuilds the tree), a second walk for the
honesty filter, ~1.3 MB of serialization, and only then does `ShallowEtagHeaderFilter` buffer and
hash it. A shallow ETag saves bandwidth, never server work: **on a 304 all of it has happened and
is discarded.** `HotTopicAggregator` additionally runs 13 strategies' `detect()` per request with
no cache (`HotTopicAggregator.java:57-71`).

That is the real cost centre — but **instrument the five phases before caching any of it**. No
measurement has isolated which of the query, the two walks, the topic fan-out or the serialization
dominates, and the caching design has sharp edges (request-time London date feeds the confidence
horizon; two of four `cache.set` sites publish no invalidation event; a region *rename* blanks the
grid through name-keyed lookups). In particular **do not** cache serialized bytes and hand-roll
the ETag: dropping `/api/briefing` from `REVALIDATABLE_READ_PATHS` to avoid double-hashing
silently reverts the response to `no-store` (`HttpCachingConfig.java:148-152`), killing the 304
outright — a bandwidth regression that every existing test would pass.

## Section 8 — what was NOT researched

A four-lens research pass was started; **two lenses were cut short and their conclusions are
absent from this document**. What is missing:

- **Coupling / blast radius** — the name-string joins are noted in Section 5 from direct reading,
  but no systematic sweep for duplicated derivations that could drift was completed.
- **Cost of splitting / reuse** — the round-trip and loading-state costs of N endpoints are
  argued here from first principles and from the verified request inventory, **not** from a
  completed analysis.

Sections 2 and 3 rest on directly verified evidence and are safe to act on. Sections 5 and 7
would benefit from the missing work before anyone commits engineering time.
