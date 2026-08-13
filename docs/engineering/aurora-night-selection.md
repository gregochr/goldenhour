# The aurora preview names the wrong night in the small hours

**Status: FIXED 2026-08-12**, in its own change, immediately after this note was written. Found while
auditing the remaining non-UK date anchors after §8a–§8c of `intraday-settled-refresh-plan.md`.

Kept in full rather than deleted, because the reasoning is the point: the obvious fix — move the
anchor to `Europe/London` like everything else — **makes it worse**, and the note exists so nobody
"finishes the job" later. What follows describes the defect in the present tense as it stood; the
fix is recorded at the end.

## What happens

`AuroraForecastRunService` builds its three-night preview from a calendar date:

```java
LocalDate today = LocalDate.now(ZoneId.of("UTC"));
for (int i = 0; i < PREVIEW_NIGHTS; i++) {
    LocalDate date = today.plusDays(i);
    TonightWindow window = computeWindowForDate(date);
    ...
    String label = buildDateLabel(date, i);   // "Tonight — Tue 11 Aug"
```

and a night is *not* a calendar day — `computeWindowForDate` says so itself:

```java
LocalDateTime dusk = solarCalculator.civilDusk(DURHAM_LAT, DURHAM_LON, date, utc)...
LocalDateTime dawn = solarCalculator.civilDawn(DURHAM_LAT, DURHAM_LON, date.plusDays(1), utc)...
```

A night labelled with date *D* runs from D's dusk to **D+1's** dawn. So between midnight and dawn
you are standing inside the night labelled *yesterday*, while the code offers you the one labelled
today — which has not started. The label says "Tonight".

## Why moving the anchor to Europe/London is the wrong fix

At 00:30 UK on both sides of the year. The dusk/dawn clock times below are **approximate** — they
were fed in by hand, not computed from `SolarCalculator`, and a review recomputing them properly at
Durham got 21:06→03:19 UTC in August and 17:24→07:05 in January. What is load-bearing is the last
column, and every verdict in it survives the correction with hours to spare:

| when | anchor | window it picks for "Tonight" | is the user inside it? |
|---|---|---|---|
| Aug (BST), 00:30 UK | UTC (current) | 11 Aug 21:30 → 12 Aug 02:30 | **yes** |
| Aug (BST), 00:30 UK | Europe/London | 12 Aug 21:30 → 13 Aug 02:30 | no — 20 h away |
| Jan (GMT), 00:30 UK | UTC (current) | 12 Jan 17:00 → 13 Jan 07:00 | no — 16½ h away |
| Jan (GMT), 00:30 UK | Europe/London | 12 Jan 17:00 → 13 Jan 07:00 | no — identical |

Two things follow, and they point in opposite directions:

1. **In BST the UTC anchor is accidentally right.** UTC's date does not roll until 01:00 UK, which
   happens to keep "tonight" pointing at the night in progress for exactly the hour after UK
   midnight. Converting to `Europe/London` would move the label onto a window twenty hours away and
   call it Tonight — a regression, introduced by a change that looks like a tidy-up.
2. **In GMT both anchors are wrong, so this is not a timezone bug at all.** From midnight until
   dawn — up to seven hours a night in midwinter — the preview names a night that has not begun,
   while the aurora the user might actually be able to see is in the window that started at dusk
   yesterday. No choice of zone fixes this, because the defect is that a `LocalDate` cannot name a
   thing that straddles midnight.

## ⚠️ There are TWO anchors in this class, and they must move together

`LocalDate.now(ZoneId.of("UTC"))` appears **twice**: once in the preview loop, and again in
`runForecasts`, where it does something quite different from labelling:

```java
// Weather triage — accurate for tonight; future dates use optimistic defaults
WeatherTriageService.TriageResult triage = date.equals(today)
        ? weatherTriage.triage(candidates)
        : buildFutureNightTriage(candidates);
```

and `buildFutureNightTriage` rejects nobody and fabricates a flat 50% cloud for every candidate:

```java
Map<LocationEntity, Integer> cloudByLocation = candidates.stream()
        .collect(Collectors.toMap(loc -> loc, loc -> 50));
```

So `today` is not only a label here — it is the switch between *real weather triage* and
*optimistic defaults*.

## The shape of a real fix

The question the code should ask is not *"what is today's date"* but *"which dark window are we in,
or heading into next"*. Roughly:

- compute the window for `date - 1` and for `date`;
- if `now` is inside the earlier one, that is Tonight and the preview starts there;
- otherwise Tonight is the window for `date`.

**Do not implement that without fixing the triage switch in the same change.** Relabelling the
in-progress night as `date - 1` makes `date.equals(today)` **false** for the very night the user
just asked to run, so the run silently swaps live cloud triage for a fabricated 50% — sending every
Bortle-eligible location to Claude on a made-up figure, at full cost and with worse answers. The
switch has to become the same is-this-the-window-in-progress test as the selection, not a date
equality. This is the trap in the obvious fix, and it is why a one-line anchor change here is
particularly dangerous: the class reads as though its date is cosmetic, and in one of two places it
is not.

Fixing the selection also settles the labels: `buildDateLabel`'s `"Tonight"` / `"Tomorrow"` become
properties of which window was selected, not of a loop index. And it removes the last reason for
this class to care what calendar its date is on — an instant-based selection needs no anchor.

## It is already implemented, one class away

Do not write the above from scratch. `AuroraPollingJob.calculateTonightWindow()` is exactly it:

```java
LocalDateTime nauticalDawnToday = solarCalculator.civilDawn(...today...).minusMinutes(BUFFER);
if (now.isBefore(nauticalDawnToday)) {
    // We are in the current overnight dark period (after yesterday's dusk, before dawn)
    ... civilDusk(today.minusDays(1)) → nauticalDawnToday
}
// Daytime or evening: tonight starts at today's dusk and ends at tomorrow's dawn
```

Note what it does with its own `LocalDate.now(utc)`: nothing decisive. The date is scaffolding for
two solar calculations, and the `now.isBefore(dawn)` **instant** test makes the actual choice — which
is why that method is correct on either calendar and this one is not. That is the pattern to copy,
and it is where the `TonightWindow` that `AuroraOrchestrator` merely carries comes from.

So the work is to make `AuroraForecastRunService` consume a window the way the orchestrator does,
rather than deriving nights from a date of its own — plus the triage switch above.

## What was actually done

`AuroraForecastRunService.currentNightDate()` — the rule above, copied from `AuroraPollingJob`
rather than reinvented — with a `Clock` injected so it can be pinned. Both anchors moved onto it in
the same change: the preview loop and the triage switch.

Three things worth knowing:

- **The class's test fixture was hiding this.** `setUp` stubbed `civilDusk`/`civilDawn` to return
  one fixed pair *for every date asked*. Nothing read a per-date answer, so it was invisible — and
  it is precisely what let a date-confused selection look fine. It now answers per date, which
  broke four existing fixtures that had been built against the fiction ("the solar stub returns
  today's times for any date", as two of them said in comments). They were rebuilt against the
  night they actually ask about.
- **`buildSimulatedKpForecast` had to move onto the clock too.** It generated its windows from the
  *system* clock, so under a pinned clock they could never overlap the nights the selection
  returns. Two different "now"s cannot be made to agree.
- **The triage test needed to name which night was measured, not count calls.** Triage runs exactly
  once either way — just for the wrong night — so a call count survives the revert. It now pairs
  each `interpret` call's window with its cloud figure: 20% (measured) must land on the night in
  progress, 50% (fabricated) on the one after.

## What selecting the in-progress night made reachable

Naming a window by its *end* rather than its start opens cases that could not happen before, when
night 0 always started in the future. One was a regression and is fixed here; the rest are logged.

**Fixed — a closed window is never run.** A modal opened at 02:00 offers D−1 as "Tonight" and
pre-ticks it. Click Run at 08:00 and D−1 is no longer current, so it would take the *future-night*
branch: fabricated flat 50% cloud, a full Claude call, and the result handed to
`replaceNightResults`, which deletes before it inserts. **Last night's measured results would be
replaced by assumed ones.** `runForecast` now skips any night whose dawn is already past, writing
nothing at all — clearing it would destroy the same rows by another route.

**Fixed 2026-08-13, in its own PR — the map now defaults to the night you just ran.** What follows
is what was done and, as importantly, what was deliberately left alone.

**The frontend does not derive the night; it is told.** `AuroraStatusResponse` gained a
`currentNightDate` component, populated in `AuroraController.getStatus()` from
`AuroraForecastRunService.currentNightDate()` — the same method, widened from package-private to
public and otherwise untouched. `GET /api/aurora/status` was chosen over the three alternatives
because `AuroraStatusProvider` already fetches it app-wide, so both `App.jsx` and `MapView.jsx` read
the night at the cost of **zero new requests**. The rejected options, and why:

- *Infer it client-side from `/forecast/results/available-dates`* (D−1 has results, D does not) —
  cheap and needs no backend change, but it is an inference *about* the rule rather than the rule,
  and it is simply wrong when both dates have results, which is what running Tonight and Tomorrow
  together produces.
- *Fetch `/api/aurora/forecast/preview` for `nights[0].date`* — exactly the right answer from a
  run-modal-shaped endpoint, at the cost of a request on every map load to read one field.
- *Re-derive dusk/dawn in the browser* — rejected on the grounds this whole note argues: the rule
  should have one home.

**The scope is aurora mode only, and that was a product decision, not an implementation limit.** The
colour map keeps its calendar default, because at 02:00 a landscape photographer wants the coming
sunrise, not last night's sunset. Three paths were changed and one deliberately was not:

- `MapView` requests the aurora night when aurora mode is entered, via a new optional `onSelectDate`
  prop — the same setter `DateStrip` already drives, so the strip follows the jump instead of
  disagreeing with the map. Three guards, each of which is the difference between a default and a
  component that fights its reader: **once per entry** (the latch resets only on leaving, so the
  strip is the reader's afterwards, including a deliberate move to an unscored night); **only when
  the night has results** (otherwise it swaps one empty day for another); **only when the current
  date has none** (someone already on a scored night came for it).
- The viewline gate compares against the night rather than the calendar date. It is a *gate*, not a
  removal: at 02:00 the window named by today's date is still ~19 hours away, and a nowcast does not
  belong on it.
- `handleAuroraViewOnMap` targets the night.
- The **map overlay** gets no `onSelectDate`, deliberately. It reads `mapOverlay.date`, so a
  requested date could not reach it and would only move the Plan tab behind it. The aurora path into
  the overlay already opens on the right night.

**A second, independent defect in the same code was fixed with it**, because it lands in exactly the
hour this work is about. The frontend derived "today" on **two calendars**: UTC
(`toISOString().slice(0, 10)`) in `App.jsx` and `DateStrip.jsx`, browser-local
(`toLocaleDateString('en-CA')`) in `MapView` and `computeAutoSelection`. Measured, not assumed: at
`2026-08-13T23:30:00Z` in `Europe/London` those give `2026-08-13` and `2026-08-14`. So for the hour
after UK midnight under BST the strip labelled yesterday's chip "Today" and dimmed the real one as
past, while the rest of the map had moved on; in GMT they agree, which is why it survived. Both are
now `utils/mapDates.js`, on the **local** basis — it matches the backend's `Europe/London` civil
date, which is what every forecast date on the wire is keyed to. UTC agreed with it only for half
the year.

**Degrade path.** `resolveAuroraNight` falls back to the local calendar date when the field is
absent — a LITE user (status is null), a failed fetch, or a browser on a cached bundle against an
older backend. That fallback *is* the old behaviour, so the degrade is "no worse than before" rather
than a guess, and it is a named test.

**What was checked and left alone.** `useForecasts.js` (a 7-day backward outcomes window, where a
one-day edge is immaterial and the question is a different one), `JobRunsMetricsView`,
`MetricsSummary` and `windowFirstAway.js` all still derive a date of their own. They are other
surfaces; none is on the map's date path. Not swept in, so as not to hide this change inside a
codemod.

**What was seen in a browser, and what was not.** Stated plainly because a local H2 database has no
evaluation run and the interesting states need a fixture. Against the local backend on 8083 with
seeded locations, forecast rows over T−2…T+5, and aurora results, all of this was *observed*:
entering aurora mode moved the date from a day with no results to the night in progress; a second
aurora date was seeded so that **two** dates had results, and it still landed on the night rather
than on either date-with-results — which is the discriminating case against the client-side
inference option, and the reason that option was rejected; the latch held when the date was then
moved by hand; leaving aurora mode did not snap the date back; re-entering jumped again; the viewline
drew on the night (two polylines) and not on another night (zero) under a simulated MODERATE alert;
the date strip marked the right chip; and the network trail showed **two** results requests rather
than a loop, with no console errors at any point.

**Not** seen in a browser, and neither is claimed: the small-hours case itself. `currentNightDate`
is derived from the server's real clock, so a run at 02:00 could not be staged — what was exercised
is the same comparison (selected date vs. whatever the backend names) with the two deliberately
different. And the **window-first (v2) arm**: its shell needs briefing data the local database does
not have, so `WindowFirstMapPane`'s one-line wiring is covered by a prop-identity test in
`WindowFirstMapPane.test.jsx` and nothing more. The jump was browser-verified on the v1 Map tab only.

**What the adversarial review found, and it was not cosmetic.** Three focused read-only reviewers ran
against the diff. Two defects survived verification, and the first was on the *common* path — the
browser session had missed it by only ever testing the lucky case.

1. **The latch was armed only when the jump fired**, so a *blocked* first evaluation left the effect
   live. Entering aurora mode while already on the aurora night — which is the ordinary daytime
   state, because the map's default date and the current night are both today — returned through a
   guard without arming, and the reader's very **next** date-strip click then satisfied the effect
   and was swallowed. First-click-eaten, silent, no error. Reproduced in a browser before being
   fixed. The latch now arms as soon as the night is known to have results, whether or not it
   moves anything — "we have looked, once", not "we have jumped". It is deliberately *not* armed
   above the `auroraAvailableDates` guard, because that list arrives async and starts empty: arming
   there would spend the one look before there was anything to look at. The regression test fails
   against the original placement.
2. **The v1 aurora banner set the handoff but never the date.** Survivable while the viewline was
   gated on the calendar date, because the map's default and the gate agreed; moving the gate to the
   night broke it. The case that fails is the one the banner exists for — a live NOAA alert at 02:00
   with **no stored run** for that night. `MapView`'s jump cannot compensate, because it is gated on
   stored aurora *results* and a live alert does not imply one: the two conditions are genuinely
   independent. The banner would land on the map with the viewline missing, for up to seven hours a
   night in midwinter. The v1 branch now sets the date like the v2 branch already did.

A third charge — that the overlay's "no `onSelectDate`" comment named the wrong mechanism — was
upheld as a comment defect rather than a behavioural one, and the comment was corrected. The
omission *is* the mechanism (`MapView` asks for nothing without a handler); the stated reason (that
the overlay reads its own date) is not load-bearing, because that date falls through to
`effectiveDate` whenever the trigger carried none. Left uncorrected it would have invited someone to
add a handler there on false grounds.

**The date basis is now `Europe/London`, done as its own change immediately after.** The review had
established that the browser's zone equals the backend's civil date only while the device is in the
UK, and that `DailyBriefing` and `WindowFirstBriefingContext` already resolve the backend's own
"today"/"tomorrow" tokens on `Europe/London` before handing the result to `setSelectedDate` — so a
UK-basis date was being judged against a browser-basis one on the same path.

⚠️ **Those two are only half right, and an earlier draft of this note wrongly held them up as the
model.** Their `londonDate(offset)` steps the *browser's* calendar and only then formats in London,
so the offset mixes two calendars. `todayStr` (offset 0) is correct; `tomorrowStr` is not. Measured:
at `2026-10-24T23:30:00Z` it yields `2026-10-25` for **both** today and tomorrow — so no Plan card
is labelled "Tomorrow", and "Best Bet — tomorrow's sunset" resolves to today's date and opens the
map on the wrong day — and at `2026-03-28T23:30:00Z` it skips the 28th straight to the 30th. Under
`Europe/London` both are correct, which is why nothing catches it. Pre-existing, confined to the DST
transitions, in different components, and **tracked separately** rather than swept into this change.
`ukDateStrOffset` is the form that steps the UK date itself.

**Five derivations moved, not the three first estimated.** `mapDates`' helpers (renamed
`ukDateStr` / `ukDateStrOffset`, plus a new `ukDayOffset`), `computeAutoSelection` and
`getNextEventType` were the known set. Two more turned up only by following the call graph, and each
would have re-split the basis on its own:

- **`conversions.formatDateLabel`** derived "Today"/"Tomorrow" from browser-local calendar fields.
  `DateStrip` marks *which* chip is today with `ukDateStr` and then calls this for every other chip,
  so on two bases **two chips could both read "Today"**.
- **`HotTopicStrip.leadDayWord`**, whose own comment says it "mirrors the relative-day logic in
  `formatDateLabel`" — a claim that is only true if both answer to the same calendar. A topic card
  reading "Tomorrow" beside a strip chip reading "Today" for the same date is what the second basis
  bought.

The shared arithmetic is `ukDayOffset`, and both now call it.

**Anchored at UTC noon, deliberately.** Every date-string step and difference goes through a
`Date.UTC(y, m, d, 12)` anchor rather than midnight: a midnight anchor can be shunted onto the
adjacent day by any offset up to ±12 h, and adding 24 h of milliseconds is not a day on the two
dates a year that are 23 or 25 hours long. Both are tested.

**Left alone, and checked rather than assumed:** `AuroraBanner` and `NlcSightingBanner` — relative
*elapsed-time* wording on a timestamp, a different question from "what day is it", and internally
consistent because the clock time they print beside it is browser-local too; and
`JobRunsMetricsView` / `MetricsSummary`'s display filters, which are admin-only and reach no map
date. ⚠️ That last clause does **not** cover `JobRunsMetricsView.computeSlots`, which derives slot
dates via `toISOString()` and puts them **on the wire** in the `excluded` array of a run request,
against a backend keyed to `Europe/London` — so for one hour a day under BST an admin de-selecting
"today" excludes UK-yesterday and the intended slot runs anyway. Pre-existing, admin-only, tracked
separately.

**Coverage gap closed** (was: `HotTopicStrip.leadDayWord` is one of the five moved sites, but
`HotTopicStrip.test.jsx` pins no timezone and freezes at midday, where every zone within ±12 h
agrees — so its Today/Tomorrow assertions pass on either basis, and `mapDatesAbroad.test.js` never
renders the component). `instantsAbroad.test.jsx` now carries a **the hot-topic day word abroad**
block: `America/New_York`, frozen at `2026-08-14T01:00:00Z` (02:00 BST on the 14th UK, 21:00 on the
13th there), asserting all three branches of the day word against the **UK** day — the 14th reads
"Today", the 15th "Tomorrow", the 13th "Thu" and explicitly not "Today".

Two things about it are deliberate rather than incidental. **The zone, not a cleverly chosen UTC
instant.** A UTC pin does separate the two bases — for the one hour a day between 23:00 and 00:00
UTC, and only during BST. New York separates them for the five hours after UK midnight, all year.
The wider band is the point: a fixture inside a one-hour window is one edit away from being outside
it. And **the block re-asserts the zone disagreement at its own instant** rather than leaning on the
file's opening `the zone fixture itself` check, which uses a different one — a day word is only
discriminating inside the divergent band, so the guard has to be measured where the assertions are.

Verified by reverting `:139` to `Date.UTC(now.getFullYear(), now.getMonth(), now.getDate())`: all
three fail, and each fails *differently* — the 14th degrades to "Tomorrow", the 15th past the
relative words entirely to "Sat", the 13th up to "Today". `HotTopicStrip.test.jsx` stayed green
through the same revert, which is the original gap stated as a measurement rather than an inference.

⚠️ **`mapDatesAbroad.test.js` pins `America/New_York` and must stay that way.** A UK-pinned test
cannot tell "the UK calendar" from "the browser's calendar" — under `Europe/London` they are the
same string, so every assertion passes either way and proves nothing about which one the code asked
for. Only a non-UK zone separates them. That file opens by asserting the two calendars genuinely
disagree at its fixture instant, so a TZ pin that stopped taking effect fails loudly instead of
quietly turning the file into a duplicate of the UK-pinned one. Do not "harmonise" it.

The full suite was run under `UTC`, `Europe/London`, `America/New_York` and `Australia/Sydney`. The
first two are clean. The other two each surface **one pre-existing** failure —
`UserManagementView.test.jsx` on New York and `HealthIndicator.test.jsx` on Sydney — both confirmed
present on a clean `origin/main` checkout under the same zone, and neither on the map's date path.

⚠️ **The tests pin the timezone as well as the clock, and both are load-bearing.** Nothing in this
repo pins `TZ`: the dev Mac is `Europe/London` and GitHub's runners are UTC, so an unpinned date test
is two different tests, and the assertions here turn on a disagreement that exists *only* under BST —
on a UTC runner they would pass while proving nothing. Each test file sets
`process.env.TZ = 'Europe/London'`; vitest gives every file its own process, so this is isolated, and
it was verified to survive `TZ=UTC` in the environment. Do not "tidy" it into `setup.js`: a global
pin would silently change every other date test in the suite.

**Open — `buildKpSummary` can report elapsed hours as forthcoming.** It formats the peak overlapping
Kp block without comparing it to now, so at 02:00 the modal can read "Kp 6 expected 00:00–03:00"
when two of those hours have gone. Cosmetic, and newly reachable for the same reason.

**Open, and pre-existing:** `WeatherTriageService` reads the next `TRIAGE_LOOKAHEAD_HOURS` (6) from
now, so from mid-afternoon the current night is still hours beyond the lookahead and "real" triage
measures cloud outside the window it judges. This fix makes the small-hours case correct — the
lookahead now lands inside the running window — but not the daytime one. A triage-window question,
not a night-selection one. Note it is arithmetic rather than a tested property: that class takes no
`Clock` and is mocked in these tests.

**Open, and pre-existing:** the two night rules agree on four independently declared constants —
`DURHAM_LAT`, `DURHAM_LON`, `NAUTICAL_BUFFER_MINUTES` here and their twins in `AuroraPollingJob`
(the latitude pair has two further copies in `ClaudeAuroraInterpreter` and
`BriefingAuroraSummaryBuilder`). Change 35 to 30 in one and nothing goes red.

## What was done in the preceding change, and why not this

Nothing, deliberately. The one-line anchor change was considered and rejected on the evidence above.
Two adjacent things *were* changed on 2026-08-12, and neither touches this:

- `AuroraOrchestrator` and `ScheduledBatchEvaluationService` both moved their
  `EvaluationTask.Aurora` date off a bare `LocalDate.now()` (JVM default zone — nothing pins `TZ` in
  the Dockerfile or compose, so it was UTC only by Alpine's default) onto
  `ForecastHorizon.today(clock)`. Safe because nothing *interprets* that date — its only readers are
  two strings: `taskKey()` (`"au/LEVEL/date"`), which reaches log lines, and `CustomIdFactory.forAurora`
  on the batch path, whose parsed date the result processor discards, keeping only the alert level.
  Both sites carry a comment saying so, because it is exactly the field someone would later mistake
  for a night selector. (An earlier draft of this bullet said the date "reaches only
  `CustomIdFactory.forAurora`, and `AuroraResultHandler` never reads it back" — wrong on both
  halves. The code comments were corrected in the same PR; this line was missed and is corrected
  here.)
- `AlmanacService` moved its feed anchor to the UK date, which *is* a plain calendar question.

## Do not

- Do not "finish the job" by pointing `AuroraForecastRunService` at `ForecastHorizon`, or at any
  other calendar. It is the one place in this codebase where the UK civil date is the wrong answer,
  and the fix was to stop asking a calendar at all — `currentNightDate()` decides on an instant.
- Do not derive a night from `EvaluationTask.Aurora.date()`. Use the `TonightWindow`.
- Do not restore a flat `civilDusk`/`civilDawn` stub in the tests. Answering per date is what makes
  night selection observable; one fixed pair for every date is what hid this defect.
