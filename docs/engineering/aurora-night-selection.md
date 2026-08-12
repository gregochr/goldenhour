# The aurora preview names the wrong night in the small hours

**Status: open, not fixed.** Found 2026-08-12 while auditing the remaining non-UK date anchors after
§8a–§8c of `intraday-settled-refresh-plan.md`. Written up separately because the obvious fix — move
the anchor to `Europe/London` like everything else — **makes it worse**, and that is the whole point
of this note.

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

Measured rather than reasoned, at 00:30 UK on both sides of the year:

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

## The shape of a real fix

The question the code should ask is not *"what is today's date"* but *"which dark window are we in,
or heading into next"*. Roughly:

- compute the window for `date - 1` and for `date`;
- if `now` is inside the earlier one, that is Tonight and the preview starts there;
- otherwise Tonight is the window for `date`.

That also settles the labels: `buildDateLabel`'s `"Tonight"` / `"Tomorrow"` become properties of
which window was selected, not of a loop index. And it removes the last reason for this class to
care what calendar its date is on — an instant-based selection needs no anchor.

`AuroraOrchestrator` already has the right shape to copy: it carries a `TonightWindow` of real
instants alongside the task, and that window — not the task's date — is what its evaluation is
about.

## What was done instead, and why

Nothing, deliberately. The one-line anchor change was considered and rejected on the evidence above.
Two adjacent things *were* changed on 2026-08-12, and neither touches this:

- `AuroraOrchestrator` and `ScheduledBatchEvaluationService` both moved their
  `EvaluationTask.Aurora` date off a bare `LocalDate.now()` (JVM default zone — nothing pins `TZ` in
  the Dockerfile or compose, so it was UTC only by Alpine's default) onto
  `ForecastHorizon.today(clock)`. Safe because that date is a **label**: it reaches only
  `CustomIdFactory.forAurora`, and `AuroraResultHandler` never reads it back. Both carry a comment
  saying so, because it is exactly the field someone would later mistake for a night selector.
- `AlmanacService` moved its feed anchor to the UK date, which *is* a plain calendar question.

## Do not

- Do not "finish the job" by pointing `AuroraForecastRunService` at `ForecastHorizon`. It is the one
  place in this codebase where the UK civil date is the wrong answer.
- Do not derive a night from `EvaluationTask.Aurora.date()`. Use the `TonightWindow`.
