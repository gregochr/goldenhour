# Plan: bound a Ready pick question's `rank_spots` to its offer's windows

## Problem (production, job run 2357, Sun 1 Nov 2026 03:12)

Three Ready precompute questions were discarded:

```
[ASK] Ready 2/BEST_WEEKEND discarded: pick 1 is not relevant to BEST_WEEKEND (2026-11-02_sunset)
[ASK] Ready 3/BEST_WEEKEND discarded: pick 1 is not relevant to BEST_WEEKEND (2026-11-02_sunset)
[ASK] Ready 4/BEST_WEEKEND discarded: pick 1 is not relevant to BEST_WEEKEND (2026-11-03_sunrise)
```

On a Sunday the weekend offer covers only Sunday's windows. The prompt says "Every pick must be on one of
these windows" (`AskPromptBuilder`), but `AskTools.rankSpots` ranks across **every** window when the model
passes no `windowIds`, so Monday and Tuesday candidates (where the BEST BET likely sat) were put in front of
the model and it led with one. `ReadyRelevance.violation` then correctly refused the answer, so those regions
have no `BEST_WEEKEND` answer, and `BEST_SOON` is not offered either (it yields to `BEST_WEEKEND`'s offer).

## Fix

Make the tool enforce what the prompt asks, using the **same** set `ReadyRelevance.admitsPick` admits picks
from: `offer.windowIds()`.

1. **`AskTools`**: add an optional window bound (`List<String>`, null = unbounded) through the package-private
   constructor; the public 5-argument constructor stays unbounded. In `rankSpots`:
   - no `windowIds` passed and bounded: rank only the bound windows, in snapshot order;
   - `windowIds` passed and bounded: the unknown-id check runs first and keeps its message; the known ids
     are then **silently intersected** with the bound. Only when nothing is left does it return
     `fail("rank_spots", …)` naming the allowed ids ("None of those windows has a place this question can
     pick. Rank within: a, b."). Failing on any out-of-bound id would be wrong: the prompt names
     `offer.coveredWindowIds()`, a superset of `windowIds()` (every solar window for `COASTAL_HIGH`; an
     all-blue weekend window for `BEST_WEEKEND`), so a model passing the ids it was given would be refused;
   - unbounded (every typed conversation): behaviour unchanged.
   - The held-back note is computed over the bounded windows (it already reads `windows`).
2. **`AskConversation.open`**: the bound is `basis.offer().windowIds()` when `opts.readyBasis()` is non-null,
   `basis.question().picks()` and not `basis.question().nightBound()`; otherwise null. Night (`NIGHT_SKY`) and
   events questions stay unbounded: their offers' `windowIds` are a night id or empty, which would empty
   `rank_spots`.
3. **No prompt text change** (prompt-regression assertions are owner-only; the covered-windows line stays).
4. **Not bounded**: `list_windows`, `look_up_place` and the area tools add no pick evidence.

## Tests

- `AskToolsTest`: bounded with no `windowIds` returns only the bound windows' spots; bounded with a mix of
  in- and out-of-bound ids ranks only the in-bound ones, with no error; bounded with only out-of-bound ids
  returns the error naming the allowed ids; an unknown id still gets the unknown-id error; unbounded is
  unchanged (existing tests stay green).
- A `COASTAL_HIGH` case: passing every covered window id ranks only the high-water windows and does not
  error (the reviewer's regression).
- `AskConversationTest`: a Ready `BEST_WEEKEND` basis bounds the tools to `offer.windowIds()`; a `NIGHT_SKY`
  basis and a typed conversation are unbounded. Prove it through `rank_spots` output where practical.
- Mutation check: removing the bound in `rankSpots` fails the bounded tests.

## Docs and changelog

- `docs/engineering/reference/ask-photocast.md`: a line where `rank_spots` or the Ready precompute is described.
- `changelog.d/20261101-ask-ready-rank-spots-bound.md`, opening `### Fixed — …`.

## Gates

The backend build ladder: compile, the touched test classes, checkstyle, then the full `clean verify` with the
integration exclusion.
