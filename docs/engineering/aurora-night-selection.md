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

**Open — the map does not default to the night you just ran.** `App.jsx` picks the first strip date
`>= today`, and `MapView.jsx` shows the aurora viewline only when the selected date *is* today. Run
"Tonight" at 02:00 and the map opens on D with no results, and clicking back to D−1 to find them
suppresses the viewline. Not fixed here: the frontend has no source of truth for "the current
night" other than the preview payload's `nights[0].date`, and this project requires UI changes to be
browser-verified before landing. Its own PR.

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
