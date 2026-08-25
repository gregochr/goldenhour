# Plan-tab panel data contracts — should each panel have its own REST API?

**Status:** Decision recorded. No code change in this document.
**Asked during:** the "Close to home" feature (`feature/close-to-home`), whose selection logic
ships as a client-side derivation and so raised the question directly.
**Method:** investigated against the real code. Every load-bearing claim below carries a
`file:line`. Where a claim is an estimate rather than a measurement, it says so.

---

## TL;DR — the two facts most needed

**Rewritten 2026-08-25** — the panel roster and Close to home's status below both changed under
this document since it was written; see Section 1 for the current, verified state of each panel.

1. **The correct seam is not "panel". It is shared-vs-per-user, and this codebase already
   enforces it, tests it, and has decided it once before.** The briefing payload is a single
   shared snapshot with no notion of a caller; per-user data is deliberately kept off it and off
   the ETag whitelist for a *security* reason, not a caching one. Splitting the shared-snapshot
   panels (3, 4, 5 — Section 1) into separate endpoints would buy nothing and cost cross-panel
   consistency.

2. **"Close to home" was the one panel that genuinely earned its own contract — and it earned it
   by data ownership, not by being a panel.** It was per-user by construction (home postcode +
   per-user drive times). Both endpoints it depended on are already named in the ETag exclusion
   list *by path*, with "home-derived personal data" as the stated reason, pinned by a test. Its
   client (`CloseToHome.jsx`) died in v1 retirement D2, leaving `GET /api/briefing/close-to-home`
   live with zero callers (Section 1); the principle survives it, now protecting
   `/api/user/settings/reach` and `/api/user/settings/light`.

**The precedent that settles it:** per-user drive times were already removed from the shared
briefing build path, and the dead parameter still carries the note —
`BriefingBestBetAdvisor.java:193` and `:515`: `@param driveMap unused — retained for API
compatibility (pass Map.of())`. This exact question has been answered once, the same way.

---

## The question

The Plan tab renders three panels sharing one snapshot, plus a fourth for per-user data. The three
are derived **entirely server-side** now (Section 1) from one `GET /api/briefing` payload. Should
each panel instead be a distinct entity with its own REST contract? — the question this document
answers, from a point where a fourth shared-snapshot panel (Best Bet) still rendered and one of the
three (the regional planner grid) still carried a client-side derivation alongside the backend one.

The question had force because `CLAUDE.md` states an explicit principle —

> **Backend-heavy** — all calculations on backend. Frontend is a pure render layer.

— and at the time, several panels plainly carried business logic in JavaScript.

## Section 1 — what the panels are today

**Rewritten 2026-08-25, post v1 retirement** (`docs/engineering/v1-retirement-plan.md`) — `DailyBriefing.jsx`,
`BriefingSummaryStrip.jsx`, `CloseToHome.jsx` and `CardHoverPreview.jsx` are all deleted (D2); the
Plan tab is the window-first matrix + popup/sheet + doors, served by `WindowFirstBriefingProvider`.

| # | Panel | Source | Where it is shaped | Presentation or business logic? |
|---|---|---|---|---|
| 1 | Best Bet / Also Good | `GET /api/briefing` (`bestBets`) | Backend `BriefingBestBetAdvisor` + serve-time fallback | **Dead panel.** Died with `BestBetBanner` in v1 retirement D2 — the fields still populate but have no frontend renderer (v1-retirement §8.1) |
| 2 | **Close to home** | `/api/briefing` + `/api/user/settings*` | Was **frontend** `utils/closeToHome.js`, inside the now-deleted `CloseToHome.jsx` | **Dead panel, backend survives.** `GET /api/briefing/close-to-home` + `CloseToHomeService`/`CloseToHomeResponse` are live with zero callers since D2 (v1-retirement §8.3) — the migration this document recommends in Section 5 already happened on the backend, just with no client left to point at it |
| 3 | Hot topics | `GET /api/briefing` (`hotTopics`) | Backend strategies | Backend. Rendered by `HotTopicStrip.jsx`, a survivor |
| 4 | Regional planner grid | `GET /api/briefing` | Backend `BriefingRegion.meanRating` + `displayVerdict` | Backend. `HeatmapGrid.jsx` reads `meanRating` unconditionally — the `serverCellRating` caller opt-in and the client-side slot-tree join it gated collapsed away entire in v1 retirement D3 |
| 5 | Plan matrix (day × event) | `GET /api/briefing` | Backend `PlanWindowProjector` | Backend. `BriefingDay.peak` has been write-only since the day rail was retired at P2 — the matrix's own per-window aggregates (spread histogram, best-reachable line) are the licensed reach-scoped client class CLAUDE.md's Backend-heavy bullet names, not a peak read |

The Plan tab is **already multi-request** — it is not a monolith being defended.
`WindowFirstBriefingProvider` (`context/WindowFirstBriefingContext.jsx`) alone calls seven
endpoints: `getDailyBriefing` (`/api/briefing`), `getAllEvaluationScores`
(`/api/briefing/evaluate/scores`), `getReach` + `getSettings` (`/api/user/settings/reach` +
`/api/user/settings`), `fetchRegions` + `fetchRegionDriveTimes` (`/api/regions` +
`/api/regions/drive-times`), and `fetchTravelDayRanges` (`/api/travel-days`). So "give each panel
an endpoint" is an extension of a pattern already half-adopted, not a departure from a single fat
call.

## Section 2 — the seam, with evidence

**The briefing payload is shared, not per-user.**
- `BriefingService.java:132` — `private final AtomicReference<DailyBriefingResponse> cache`.
  One reference for the whole application.
- `DailyBriefingCacheEntity.java:16,28-31` — persisted to a literal **singleton row**:
  *"The row with `id = 1` always holds the most recent generated briefing"*, `/** Singleton row
  identifier — always 1. */`.
- `BriefingController.java:57` — `public ResponseEntity<DailyBriefingResponse> getBriefing()`.
  **No parameters. No `Authentication`.** The endpoint cannot see who is asking.
- `BriefingService.java:569` — the horizon is built from `BRIEFING_WINDOW_DAYS` (now 5, not 4 —
  the extra date exists so the window still reaches T+3 after ageing overnight, per that
  constant's own Javadoc at `:101-121`): identical for everyone regardless of the count.

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

- **Panels 3, 4, 5 — the shared forecast snapshot** (panel 1, Best Bet, is now a dead panel with
  no v2 renderer — see Section 1). These are *views of one answer*. They should keep deriving from
  one payload. Splitting them multiplies requests against a payload that is already
  ETag-revalidated to a **304**, and it reintroduces cross-panel divergence that this codebase
  deliberately engineered out. Two panels fetching independently can show 4★ and 3★ for the same
  location. Consistency beats modularity here.

  ⚠️ **`DailyBriefing.jsx`'s claim that one source makes "strip/grid disagreement structurally
  impossible" was too strong, and this document repeated it.** One source removed disagreement
  about the *data*; it said nothing about the *aggregators*. Three surfaces went on reducing that
  one payload three different ways — the window row by the max rating, the grid cell by the region
  mean, the day card by an any-of over region verdicts — and on 2026-08-16 production showed a row
  reading "Worth it · best 4★" above a grid of "Poor" cells. Verified and fixed in
  `plan-verdict-consolidation-plan.md`: since Phase 3 the backend computes each of the three and
  the client renders them, so the claim now holds for the aggregators as well. The lesson worth
  keeping is that "one payload" is a necessary condition and was mistaken for a sufficient one.

- **Panel 2 — Close to home.** Per-user by construction. This one earned its own endpoint, and
  it earned it because of who owns the data, not because it was a panel. `GET
  /api/briefing/close-to-home` shipped (Section 5) and then lost its only caller when
  `CloseToHome.jsx` was deleted in v1 retirement D2 — the endpoint is unconsumed, not wrong; a v2
  client is an owner decision (v1-retirement §8.3), not a re-litigation of this section.

The general rule, stated so the next feature does not have to re-derive it:

> A panel earns its own REST contract when it answers a **different question about differently
> owned data**. A panel that is another view of the same snapshot does not — it should be derived
> from that snapshot, on whichever side of the wire the snapshot already lives.

## Section 4 — what happened to Close to home

**Overtaken by events, 2026-08-25.** This section originally weighed whether to leave the ranking
logic in `frontend/src/utils/closeToHome.js` (~360 lines of client-side business logic — a
haversine distance gate, a GO/MAYBE filter, a dedupe-by-location, a ranking comparator) or move it
server-side, and recommended shipping as-is with a follow-up endpoint. Neither branch of that
argument matters any more: the client module and the panel that rendered it (`CloseToHome.jsx`,
`CardHoverPreview.jsx`) are **both deleted** (v1 retirement D2), so there is nothing left in
JavaScript to violate the backend-heavy principle, and no frontend suite left to cover it either.

The endpoint side of the recommendation *did* happen, independently — `GET
/api/briefing/close-to-home` + `CloseToHomeService`/`CloseToHomeResponse` exist on `main` today —
but arrived after the client was already gone, so it now has **zero callers**
(`docs/engineering/v1-retirement-plan.md` §8.3). "What's good near me tonight" for Web Push or the
macOS widget, and any v2 UI, both remain live reasons to keep and consume the endpoint; deleting it
is not implied by this section's history, only left as an owner decision.

## Section 5 — migration path, when it comes

In priority order.

1. ✅ **Done.** `GET /api/briefing/close-to-home` — authenticated, reads the caller's home from
   `UserSettings`, returns the ranked cards + breadcrumb. Not on `REVALIDATABLE_READ_PATHS`
   (Section 2 held). `closeToHome.js` was ported to `CloseToHomeService`. The client that
   consumed it was then deleted in v1 retirement D2, so the endpoint currently has no caller
   (Section 4) — the migration itself is complete, independent of that.
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
~691 bytes each. The 4-date window was live at measurement time; `BriefingService`'s window is
5 dates now (`BRIEFING_WINDOW_DAYS`, `:122`), a later change (V103) this measurement predates —
the shared-payload argument is unaffected by the count either way.

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
