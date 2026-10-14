# R1 variant: a distant solar-side lid carries a 4 only with near canvas

Part D arm 4 of `docs/engineering/far-cloud-p1-plan.md` (rule R1, section 6). **Draft for the owner to
read before any spend. Nothing here is applied to `src/main`.**

## What changes, and what does not

Today the system prompt (`PromptBuilder.SYSTEM_PROMPT`, `PromptBuilder.java:119-127`) treats solar-side
mid/high cloud at the 113 km horizon as a sure canvas: the IDEAL clause asks only for "high cloud
present on either horizon", and the thick-mid clause says "the solar-side mid/high IS the canvas".
That is what let the Angel of the North 2026-10-13 sunset (cone low 7, mid 24, high 91; overhead 0/0/0;
antisolar 0/0/0) earn 4 on Sonnet 5.5.

R1 requires **near canvas** before a distant solar-side lid can carry a 4:
- observer-point (overhead) mid or high cloud >= 20%, **or**
- antisolar horizon mid or high cloud >= 20%.

With no near canvas, the lid alone caps rating at 3 and fiery_sky at 55.

**Owner choice, recorded:** the fiery_sky cap of 55 is not derived from data. It is the top of the band
the prompt gives a "maybe". Change the number in replacements 1 and 2 if you want a different cap; the
rating cap of 3 is the rule itself.

Unchanged, on purpose: the BLOCKED ceiling (solar low > 60%), the 40-60% partial-block rule, THIN STRIP
and FAR CORRIDOR, the rating-5 conditions, the combined-signal cap and everything else. Note the THIN
STRIP clause still says "When mid/high cloud canvas is present (solar or antisolar), RATE 3-4"; I left
it alone because it already caps at 4 only under a >= 50% low-cloud strip, but it is the next place the
same idea could apply.

**This contradicts the current text "the solar-side mid/high IS the canvas", by design.** Low Hauxley
(cone 0/10/100, overhead mid/high 100, antisolar 100/100/100) has near canvas on both counts, so it must
stay 4; that is a hard stop in the plan (section 2, Part D).

Replacements 4 to 6 exist because the first three alone left the prompt contradicting itself: the
sentence "When solar low cloud <20%, diffuse/soft/muted warm tones across this canvas are STILL rate 4
... do NOT drop it to 3" survived after "With NO near canvas ... cap rating at 3", the general criterion
"mid/high cloud above clear horizon = ideal canvas" still called any lid a canvas, and the
DIRECTIONAL CLOUD DATA line still said the solar-horizon readings are the more reliable guide to
"canvas availability". Each now says near canvas is required or how it is judged.

Both clauses are written against the labels the user message actually uses: the `Cloud: Low x%, Mid y%,
High z%` line (observer point) and `Antisolar horizon (away from sun): ...`.

## The six replacements (verbatim; each OLD must occur exactly once in the production prompt)

The text below is the runtime string (Java `—` is an em dash, `≥` is not used in the old text).
`replay_set.py build-r1` reads these fenced blocks, so keep the fence labels.

### 1. IDEAL clause

```old 1
- IDEAL scenario: solar horizon low cloud <20% AND mid cloud <50%, with high cloud present on either horizon as canvas. Score fiery_sky 70-90, rating 4-5.
```

```new 1
- IDEAL scenario: solar horizon low cloud <20% AND mid cloud <50%, with high cloud present on either horizon as canvas AND NEAR CANVAS: observer-point (overhead) mid or high cloud ≥20%, or antisolar horizon mid or high cloud ≥20%. Score fiery_sky 70-90, rating 4-5. Mid/high cloud on the solar horizon alone is a distant lid 113 km away, not near canvas: it can be lit only through a gap toward the sun, and there is nothing to catch the light overhead or behind you. With NO near canvas, cap rating at 3 and fiery_sky at 55, however much solar-side mid/high cloud there is. Judge near canvas from the observer-point Cloud line and the antisolar horizon line only; if the antisolar line is not provided, use the observer-point line alone.
```

### 2. Thick-mid rate-4 clause (first part)

```old 2
RATE 4 (not 3, not 5) — the thick mid cloud limits colour VARIETY but is a canvas, NOT a blocker. This holds even when the antisolar side is bare: the solar-side mid/high IS the canvas. Only solar LOW cloud blocks light — never treat thick solar mid/high cloud as 'blocked' or 'no canvas'.
```

```new 2
RATE 4 (not 3, not 5) when there is NEAR CANVAS (observer-point mid or high cloud ≥20%, or antisolar horizon mid or high cloud ≥20%) to catch the light — the thick mid cloud limits colour VARIETY but is not a blocker. With NO near canvas the solar-side mid/high cloud is only a distant lid: cap rating at 3 and fiery_sky at 55. Only solar LOW cloud blocks light — never treat thick solar mid/high cloud as 'blocked'.
```

### 3. Thick-mid rate-4 floor, scope sentence

```old 3
This rate-4 floor applies ONLY with clear light penetration (solar low <20%);
```

```new 3
This rate-4 floor applies ONLY with clear light penetration (solar low <20%) AND near canvas;
```

### 4. Diffuse-tones sentence, scoped to near canvas

```old 4
When solar low cloud <20%, diffuse/soft/muted warm tones across this canvas are STILL rate 4 (worth the trip) — uniformity and the absence of vivid reds reduce fiery_sky and cap the rating at 4, but do NOT drop it to 3.
```

```new 4
When solar low cloud <20% AND near canvas is present, diffuse/soft/muted warm tones across this canvas are STILL rate 4 (worth the trip) — uniformity and the absence of vivid reds reduce fiery_sky and cap the rating at 4, but do NOT drop it to 3. This applies only when near canvas is present; with no near canvas the cap at 3 above governs.
```

### 5. Key criteria line

```old 5
mid/high cloud above clear horizon = ideal canvas for fiery sky;
```

```new 5
mid/high cloud above a clear horizon, overhead or behind you (near canvas), = ideal canvas for fiery sky, while mid/high cloud only on the distant solar horizon is not;
```

### 6. Directional-data reliability note

```old 6
These are MORE RELIABLE than the observer-point cloud layers for assessing light penetration and canvas availability. Key rules:
```

```new 6
These are MORE RELIABLE than the observer-point cloud layers for assessing light penetration and canvas availability, except that NEAR canvas (see the IDEAL scenario) is judged from the observer-point mid/high cloud and the antisolar mid/high cloud only. Key rules:
```

## How the owner builds the override file

1. Dump the production prompts (no API key needed; tagged `prompt-regression`, so use the profile):

   ```bash
   cd backend
   ./mvnw test -Pprompt-regression -Dtest=PromptReplayHarnessTest#dumpSystemPrompts \
     -DREPLAY_DUMP_SYSTEM_PROMPTS_DIR=/tmp/sp
   ```

   This writes `/tmp/sp/system-inland.txt` and `system-coastal.txt`. If the build has trouble on
   JDK 21, see `backend/src/test/resources/p0-replay/README.md`.

2. Apply the patch (fails loudly, exit 1, if any OLD text is missing or occurs more than once):

   ```bash
   python3 scripts/far-cloud-p1/replay_set.py build-r1 \
     --system-prompt /tmp/sp/system-inland.txt \
     --patch scripts/far-cloud-p1/r1_system_prompt_patch.md \
     --out /tmp/sp/system-r1.txt
   ```

3. Replay with it: `REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt`. The file is the inland prompt;
   for a coastal prompt the harness appends the coastal suffix itself, so one file serves both.
