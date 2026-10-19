# Tide-run cards — the high water at sunrise AND at sunset (plan)

Status: **PLAN, reviewed** (2026-10-19; one adversarial backend/fidelity lens, §7). Owner ask, on the
23–28 Nov king-run card, which reads only `tide HW 16:13 · 26m after sunset on the 26th`: *"I should be shown
both high waters in the day? Esp as a lot of my locations are east facing and lend themselves to sunrise."*
Owner decisions (2026-10-19): build it ("pls"), and **the rows name HIGH water only** (asked explicitly,
because the old either-type rule would answer his sunrise question with a low water). One build phase (W1,
backend only), one PR, a Sonnet subagent; merged on green CI + a clean Codex review.

## 1. Today

`TideAlmanacSource.pickAligned` takes ONE run day whose `TideRunDay.alignmentPhrase` is non-null (the peak
day if it aligns, else the first that does). That phrase is the single extremum (HW **or LW**) nearest
EITHER event, "aligned" within `TideRunBuilder.ALIGNED_WINDOW_MINUTES` (60). `ComingUpAssembler.tideFacts`
prints it (`tide HW 16:13 · 26m after sunset`, plus ` on the 26th` on a merged card) or `no water lands in
the light on this run`. A run whose aligned water is at sunset says nothing about sunrise.

## 2. What the reader gets

On every tide-run Coming up card (merged or not) the single `tide …` row becomes TWO rows, sunrise first,
each about HIGH water only, each always dated:

| state | row |
|---|---|
| aligned | `sunrise · ` **`HW 07:40, 10m before`** ` · Wed 28` |
| within 5 min | `sunrise · ` **`HW 07:42, at sunrise`** ` · Wed 28` |
| none within the hour | `sunrise · no high water within the hour · nearest HW 05:30, 2h20 before, Wed 28` |
| ditto, run only partly measured | `sunrise · no high water within the hour on the days measured · nearest …` |
| no measured day has a high water that day | row omitted (never synthesised) |

The event word is the row's own label, so the clock clause never repeats it except "at sunrise/sunset". The
old `noAlignment` row ("no water lands in the light…") goes. `partialCoverage`, the range row and the region
row are unchanged. A low water at sunrise is no longer named on the card (owner's decision); the Map tab's
per-location tide fit still covers the reader's own spots' wanted water.

## 3. Definitions (server-owned)

- **Port / days**: the run's representative and its per-day extremes in the Europe/London civil day — the
  same `dayTides` data `alignmentPhrase` reads; derivable days only.
- **Per day, per event** — new, in `TideRunBuilder`, with NEW per-event helpers. ⚠️ **Never reuse
  `signedGap`, `solarWord`, `withinWindow`, `nearestSolar` or `alignmentPhrase` for this**: all measure to
  the NEARER of the two events, so a midday water nearest to sunset but nearer sunrise would print the wrong
  word and gap. Add `eventGap(minutes, eventMinutes)` (signed, no midnight wrap) and a phrase formatter over
  `TideWording`'s duration/clock helpers. The value is the HIGH water (type `HIGH`) with the smallest
  `|eventGap|` on that day (ties → the earlier water); `aligned` = `|gap| <= ALIGNED_WINDOW_MINUTES`.
  "Nearest" means nearest within that London day — a summer 04:30 sunrise's true nearest HW may be the
  previous evening's; accepted and stated in the javadoc.
- **Per run, per event** — in `TideAlmanacSource`: if the true peak day (`peak()` true; never on a one-day
  run) is aligned for that event, take it; otherwise the day with the smallest `|gap|`; ties → the earliest
  day. (Peak-first keeps the existing "the peak day if it aligns" rationale and stops the row disagreeing
  with the coincidence line, below.)
- **Where it rides**: NOT on `TideRunDay` (it is `HotTopic.tideRun`, persisted in `daily_briefing_cache`
  and compared by value per serve; growing it risks the "Hot topics are recomputed LIVE" trap and touches six
  constructor sites). Instead `TideRunBuilder` returns the per-day event waters as a SEPARATE output from the
  SAME extremes fetch and geometry pass `build` already makes (e.g. a `build…` variant returning the days map
  plus `Map<LocalDate, EventWaters>`, with `build` delegating so its callers are unchanged). Only
  `TideAlmanacSource` reads it.
- **Meta** (absent keys, never empty, via `metaOf`): `sunriseHw` (clock `HH:mm`), `sunriseHwGap` (signed
  minutes), `sunriseHwDate` (ISO), `sunriseHwAligned` (`"true"` or absent), the four `sunsetHw…` twins, and
  `eventWaterPartial` (`"true"` when any run date was not derivable). The assembler formats the text, so
  wording lives in one place.

## 4. Consumers that move with it

- **Coincidence line HW clock** (`ComingUpAssembler` ~977/1011, `alignmentClock`): today it prints the
  `alignment` clock when `alignmentDate == truePeak`. Move it onto the new keys: the HW clock of an ALIGNED
  event row dated on the true peak (sunset row first, then sunrise), else none. Same card, one answer.
- **`phaseOf` / `ComingUpTide.phase`** (~1243; the sparkline's HW/LW marker, `ComingUpTideSparkline.jsx`):
  the rows now name high water only, so `phase` is `"HW"`; rewrite the javadoc ("the card names high water").
- **`alignment` / `alignmentDate` / `noAlignment`**: after the two moves above nothing reads them (the
  review confirmed only `TideAlmanacSource` and `ComingUpAssembler` touch them; Ask, the frontend and the hot
  topics do not). Stop writing them and delete `pickAligned` and its tests, or keep writing and strip them
  from served meta — W1 decides, and the meta-strip comment (~425–429) and `coming-up-plan.md` line ~35's
  passthrough list must say the truth either way.
- **The merged-card ` on the <ordinal>` rule** (supermoon-tide-run-plan R8 and its §5 S1 notes; S1's
  `tideFacts(event, merged)`): superseded — both rows always carry their date. Update those plan lines and
  coming-up-plan.md §11; drop the `merged` parameter if nothing else needs it.
- **Cross-surface, accepted**: the Plan tab's king/spring pill (`alignedEvent`, either-type, nearer-event
  rule) can name a different water and day from the card's rows (e.g. Plan: low water at sunrise; card: high
  water at sunset). Different questions on different surfaces; recorded in coming-up-plan.md §11.

## 5. W1 — backend (one PR)

- `TideRunBuilder` per-day high water per event (§3) with its own per-event helpers; `TideAlmanacSource`
  per-run pick and the nine meta keys; `ComingUpAssembler.tideFacts` the two rows (§2) as segments
  (`base` label, `accent` clock clause when aligned / `base` when not, `base` date) with NBSPs introduced into
  facts (`HW 07:40`, `10m`, `2h20`, `Wed 28`) — `tideFacts` has no `nb()` today, add it; the §4 moves.
- **Tests that cannot pass on the old code**: (a) a day where the sunset row's HW lies NEARER sunrise
  (asserts the word "before/after" and the gap against sunset); (b) a day whose nearest-to-either extreme is a
  LOW water at sunrise and whose HW is hours off — the sunrise row names the HW miss, never the LW; (c) peak
  day sunset aligned at 26m and another day at 23m — the row picks the peak day AND the coincidence clock
  agrees; (d) a tie where peak and earliest differ, and one where the peak is not aligned; (e) partial run →
  "on the days measured"; (f) a day with no HW for that event skipped, all days → row omitted; (g) "at
  sunrise" within 5 minutes; (h) one-day run; (i) merged and unmerged cards both show both dated rows; the
  `noAlignment` row gone; (j) the segment text contains U+00A0; (k) `phase` is HW. `TideSurfaceAgreementTest`
  and `ComingUpDayRangesAgreementTest` stay green. AlmanacControllerTest through the web mapper for the new
  facts shape.
- Docs: coming-up-plan.md §11 entry (incl. the owner's HW-only decision and the cross-surface note), §13 meta
  note; supermoon-tide-run-plan R8 superseded; changelog file. No frontend change (facts are served
  segments). Before merge, the measured browser lens checks the two rows wrap cleanly at 320/390.

## 6. Decided

- High water only (owner, 2026-10-19). Window 60 minutes (`ALIGNED_WINDOW_MINUTES`). One port (the run's
  representative, named in the range row).

## 7. Review record (2026-10-19)

The lens found: the either-type rule would answer the owner's high-water question with a low water (taken to
him: HW only); "reuse the existing helper" was wrong (nearer-event helpers); smallest-gap could disagree with
the coincidence clock (peak-first pick, clock moved onto the new keys); don't grow `TideRunDay` (separate
builder output); "this run" overclaims on partial runs; `phaseOf` loses its meaning; midnight edge; missed
comments/docs; shorter wording; NBSPs are new to facts; vacuous-test traps.
