# PhotoCast — window-first Plan tab redesign. Continue at P13.

*Handover written 2026-08-09, immediately after P12 merged. Paste this as the opening prompt of the
new session. Every file:line was checked against HEAD on the day this was written — but this project
has had citations rot in a week twice running, so **verify before building on any of them**.*

⚠️ **P13 is a FRONTEND phase again.** P12 was backend-only, so the muscle memory from that session
is the wrong muscle memory: different build ladder, a different test-standards document
(`frontend-test-standards.md`, not the backend one), and the browser matters again.

## Read first, in this order

1. `docs/engineering/window-first-redesign-plan.md` — the spec. For P13: **§3 in full** (the feed's
   design), the **P13 row in §5**, and **§5g**, which records the eight decisions P12 made. §5g is
   the single most useful thing to read; it is where the traps are written down.
2. `docs/engineering/frontend-test-standards.md` — before touching any test.
3. CLAUDE.md § "UI Work — Review Cadence" — **this phase is bound by it.** build → tests →
   adversarial review of the diff → fix survivors → re-verify → commit. Review agents are
   **READ-ONLY** (one destroyed uncommitted work on this project). `git add -A` first.
4. `docs/engineering/window-first-p12-handover.md` — the previous handover. Its "Traps that carry
   forward" and "Running it locally" sections still hold.

## State

- Phases done: P0…P11, **P12 (#443, merged 2026-08-09)**.
- **Branch from `main`.** At handover it is `471e368c`, CI green.
- Frontend suite **2,852 tests / 118 files**. Backend **6,819 tests**.
- Latest migration is **`V139__tide_refresh_description_horizon.sql`** — read it off the tree before
  naming a new one; P13 should need none.
- Flag default is still `v1`. The flip is P15's. **Nothing P4–P12 built is visible to a pilot user.**
- ⚠️ **`main` is 8 commits ahead of the newest tag (`v2.17.13` → `c319f445`), and `Deploy` fires
  only on `push: tags: v*`.** So none of P12, and none of the CVE fixes, is in production. That is
  the owner's call to make, not something to fix in passing.
- ⚠️ **One PR may still be open: #450, the tide tail-fetch.** It changes how `tide_extreme` is
  populated, not its shape, so it does not block P13 — but `git log --oneline -5` before assuming
  the tree matches this doc.

---

## Task — P13: the "Coming up" tab

Render the 90-day almanac feed. The backend exists and is tested; **nothing has ever been drawn.**

### What you are rendering

`GET /api/almanac?days=90`, Bearer, no role gate. Returns `AlmanacEvent[]`, ascending by start date:

```
{ startDate, endDate, kind: ALMANAC|FORECAST, type, title, detail,
  meta?: {string: string}, regions?: string[] }
```

- **Entries are spans, not days.** `startDate`/`endDate`, both inclusive. A spring run is ~4 days,
  an NLC season ~11 weeks.
- **`meta` and `regions` are omitted entirely when empty** (`@JsonInclude(NON_EMPTY)`) — pinned by
  `AlmanacControllerTest`. Do not write `meta.range` without a guard.
- **`isDatesOnly()` exists server-side but is not on the wire.** The client tells a degraded entry
  from an enriched one by `meta` being absent.
- Sources and their `type` values: `spring-tide`, `king-tide`, `meteor`, `supermoon`, `equinox`,
  `solstice`, `nlc-season`.

### ⚠️ Three traps that are already visible from the payload

**1. There are now TWO certainty vocabularies, and they disagree.** The backend ships
`AlmanacKind` (`ALMANAC` | `FORECAST`) on the wire. The frontend already has
`utils/topicCertainty.js` with **three** kinds (`almanac` | `forecast` | `chance`) derived
client-side from the topic type. They are not the same axis under two names — and on NLC they
contradict outright: `NlcSeasonAlmanacSource` emits `kind: ALMANAC` (the *dates* are fixed) while
`topicCertainty` maps NLC to `chance` (the *display* cannot be forecast). **Decide which vocabulary
the Coming-up tab speaks before writing a single component**, and record it. CLAUDE.md has a
standing rule against a value derived two ways in two places.

**2. The type keys will not match, and both lookups fail silently to a default.** The almanac feed
uses lowercase-hyphenated (`spring-tide`); `topicCertainty.TYPE_TO_KIND` and `HOT_TOPIC_STYLES` key
on UPPERCASE (`SPRING_TIDE`). Reusing either lookup as-is gives you the fallback for every row and
no error. Verified: nothing in `frontend/src` references `/api/almanac` today.

**3. `regions` is empty on every entry.** All five sources pass `List.of()`, and `NON_EMPTY` means
the key never reaches the wire. So the tab **cannot filter or group by region** without a backend
change. §5g argues why that was right at 90 days (the question is *when*, not *from where*), but if
the design assumes a region filter, that assumption is unmet.

### Decisions P13 must make, and write down

- **Spans vs the Plan tab's one-card-per-day.** CLAUDE.md records that a multi-row card for a tide
  run was **tried and reverted** on the Plan tab, because its ordering spine is time. The almanac
  feed does the opposite — one entry per span. Is that a justified difference between two surfaces,
  or a contradiction P13 inherits? Argue it from the documented reason the Plan tab reverted.
- **What a dates-only row looks like.** A tide run beyond the stored-extremes window has no `meta`.
  It must read as *"we know when, not how big"* — not as a broken row, and not with a placeholder
  figure. This is the degrade rule reaching the screen for the first time.
- **`meta` is `Map<String,String>`.** Every value is pre-formatted by the backend. If P13 needs to
  sort, compare or threshold on a range, it will be parsing a string the backend had structured.
  Push formatting back rather than parsing forward.
- **Where the tab lives** relative to the existing tabs, and whether it shares the arm's provider
  (`WindowFirstBriefingProvider`) or fetches its own. The feed is a *different* question about
  *differently owned* data, so per `docs/engineering/plan-panel-data-contracts.md` its own contract
  is defensible — but it is also cacheable and user-independent, which the briefing payload is not.

---

## Traps that carry forward

1. **There is no local data path.** `BriefingHonestyFilter.fullRewrite` empties every region's slots
   because the batch pipeline has never run locally. **But the almanac feed is different**: it is
   ephemeris-driven and needs no evaluation. `tide_extreme` is the only part that needs rows, and
   ⚠️ **the local table is EMPTY** (verified 2026-08-09 by querying the H2 file directly — the P12
   handover's claim that it "does have real rows" was wrong for this machine). So tide runs will
   render dates-only locally, which is *convenient*: it is the degrade path, free.
2. **The Browser pane freezes `setTimeout` and dispatches no scroll events while hidden.** Sandwich
   timing-dependent checks between `computer{action:"screenshot"}` calls. The DOM is authoritative —
   read `getBoundingClientRect()`, not the screenshot.
3. **Read DOM state in a LATER tool call than the one that triggered it.** React batches.
4. **Editing source mid-browser-session breaks fixtures via HMR.** Finish the browser pass, then
   edit.
5. **`npm run test` is NOT the frontend CI job.** That job is lint → Vitest → `npm audit
   --audit-level=high` → build. Run all four.
6. ⚠️ **`npm ci` before trusting a local frontend failure.** On 2026-08-09 four `WindowPickDialog`
   tests failed with a jsdom crash; the cause was a stale `node_modules` (jsdom 30.0.0 installed
   against a lockfile pinning 30.0.1). CI runs `npm ci` and was green throughout. It looked exactly
   like four real accessibility defects.
7. **Tokens in the plain `@theme` block are pruned to the empty string** unless referenced by a
   literal `var()`. Verify with `getComputedStyle` on the running app.
8. **Prefix new testids `wf-`** — the v1 arm's tests assert `cth-`-prefixed ids are absent.

---

## What P12 left unverified — do not assume otherwise

- **Nothing has been rendered.** No component, no screenshot, no browser pass. P13 is the first
  time any of this is seen.
- **No production data.** The enriched half of the tide path is tested only against a stubbed
  `TideRunBuilder`.
- **`V139` and the two new tide JPQL queries** had not run against Postgres at the time P12 merged
  (Docker was unavailable locally). CI has since exercised V139.
- **The equinox/solstice anchors are approximate fixed dates** (Mar 20, Sep 22, Jun 21, Dec 21), and
  one review lens claimed the September anchor is astronomically wrong for 2026 — **from its own
  recall, explicitly unverified.** Check against a real ephemeris before either acting on it or
  dismissing it. The anchors are pinned against `EquinoxHotTopicStrategy` by a day-by-day agreement
  test across two years, so the feed and the Plan tab agree with each other whether or not they
  agree with the sky.
- **Two CVE suppressions expire 2026-11-30** (`CVE-2026-66299` tomcat, `CVE-2026-53914` kotlin).
  The weekly Security Scan will go red then. That is the deadline working, not a regression.

---

## Running it locally

```bash
cd backend && ./mvnw -Plocal-dev spring-boot:run -Dspring-boot.run.profiles=local
```

Port **8083**; `-Plocal-dev` is load-bearing (H2 is `test` scope). Frontend: add a `launch.json`
entry on **5181** (5175–5180 are taken by earlier phases). Login `admin` / `golden2026`.

Flip to v2 through ⚙ → "Window-first Plan". The stored value is JSON-encoded — `'"v2"'` **with the
quotes** — so a hand-set `localStorage.setItem` silently does nothing.

Check the feed is alive before building against it:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8083/api/almanac
```

`401` means it is up and secured. With a token you should get equinox/solstice, meteor peaks,
supermoons and NLC-season entries with no `meta` on the tide runs — the local `tide_extreme` is
empty.

---

## What the last two reviews found, because these species recur

- **The most valuable findings are tests that cannot fail.** P11 found three; P12 found one asserting
  a spring run's type where every fixture fed a king run — a bare `TYPE_KING` in place of the
  ternary shipped green.
- **A fix can introduce the defect.** P12's threshold decoupling removed an accidental
  minimum-sample floor nobody knew was load-bearing, and a green suite, a clean build and
  mutation testing all passed over it. **When you remove an implicit invariant, ask what was
  relying on it.**
- **Confident research is still research.** A review concluded "swagger-ui is not what is failing
  your scan" — it was the only thing failing, once louder findings were cleared. What caught it was
  re-running the scan rather than declaring victory.
- **Say what you could not verify.** Every handover in this series that named its gaps has had one
  of those gaps turn out to matter.
