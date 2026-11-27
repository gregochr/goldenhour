# Plan: a good sky is needed always (the tide's effect on the star)

**Status: APPROVED 2026-11-27. One PR.** The owner, on a Low Hauxley card that showed 4★ from a 3★ sky:
"the tide isn't lipstick on a pig … I think a good sky is needed always."

## 1. Today

`RatingCombiner` averages the sky score with `TideVisitor`'s score and rounds half up. Tide scores:
- 5: king or spring tide, aligned with the light;
- 4: regular tide, aligned;
- 3: aligned only within the widened window;
- 1: misaligned.

Since #1516 (2026-10-30) the tide may lift the star at most one star above the sky. The penalty
side is uncapped. So:
- 3★ sky + an ordinary aligned tide → 4★;
- 5★ sky + a misaligned tide → 3★.

## 2. The rule

Only for the sky + tide peer pair, with a valid 1–5 sky score; nothing else changes. Bluebell peers,
sky-only, tide + bluebell and the recorded components are all untouched.

- **Lift**: the star may rise above the sky only when the sky is already **4★** and the tide scores
  **5** (spring or king tide timed to the light). Then 4★ → 5★. Any other tide never raises the
  star.
- **Drag**: the tide may lower the star by **at most one** below the sky (floor 1).
- Otherwise the half-up average stands, clamped to `[max(1, sky − 1), upper]`, where `upper` is
  `sky + 1` for the spring/king-on-4★ case and `sky` otherwise.

| Sky | Tide | Today | New |
|---|---|---|---|
| 3 | 4 | 4 | **3** |
| 3 | 5 | 4 | **3** |
| 2 | 5 | 3 | **2** |
| 4 | 5 | 5 | 5 |
| 4 | 4 | 4 | 4 |
| 4 | 1 | 3 | 3 |
| 5 | 1 | 3 | **4** |
| 5 | 3 | 4 | 4 |
| 3 | 1 | 2 | 2 |
| 1 | 5 | 2 | **1** |

The tide still speaks in the tide box ("Tide lands on the light"), and the box's "sky N★" note still
appears whenever the card star and the sky star differ.

## 3. Code

- `RatingCombiner.capTideLift` becomes the clamp above. Name the thresholds as constants (good-sky
  floor 4, spring/king tide score 5, max drag 1). Update the class Javadoc's "One-star tide lift cap"
  section.
- No prompt, migration or frontend change.

## 4. Tests

- `RatingCombinerTest`: replace the `tideLift_cappedAtOneStar` table with the §2 table plus the edges:
  - 5+5 → 5;
  - 2+1 → 1;
  - 1+1 → 1;
  - 4+3 → 4.
- Existing peer-set tests unchanged.
- `CachePayloadGoldenMasterTest` coastal-aligned (sky 3 + spring 5): it now lands on 3. Update the
  comment and regenerate its golden. `skyRating` stays 3.
- `ForecastResultHandlerTest:1327` (sky 4 + spring → 5): unchanged.
- `NeighbourOutlierGuardTest:202` uses a served 4 over a sky 2 as fixture data. Check whether its
  premise still matters, and reword the comment if needed.

## 5. Docs

- `docs/engineering/reference/ratings-stores.md` l.24 (the tide-lift sentence) states the new rule.
- Changelog entry.
