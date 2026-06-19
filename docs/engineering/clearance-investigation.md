# Clearance — Step 0 investigation (read-only)

**Question reframed:** not "how do we build a clearance detector" but —

> Does the standard per-location sky evaluation prompt already *see* and
> *reward* cloud clearing into the solar event?

**Verdict in one line:** The clearance **end-state** is already rewarded by the
point-in-time rules (a cleared solar horizon + residual mid/high canvas scores
4–5 today). The clearance **trajectory** — "it was overcast three hours ago and
is breaking up right into the event" — is **not** rewarded: the trend data is
already in the prompt, but it is framed exclusively as a *risk* signal and the
model is never told to reward a clearing trajectory. So we are in the **NO
branch on the trajectory, YES on the end-state** — and in *neither* case is a
hot-topic detector the answer.

---

## Section 1 — what the per-location evaluation prompt actually sees re: cloud

Traced path (the `cached_evaluation` / batch rating path, not briefing/best-bet):

- Prompt assembly: [`PromptBuilder.java`](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java)
  — `SYSTEM_PROMPT` (the rubric) + `buildUserMessage(...)` (the data).
- Data assembly: [`ForecastDataAugmentor.java`](backend/src/main/java/com/gregochr/goldenhour/service/ForecastDataAugmentor.java)
  `augmentWithCloudApproach(...)`.
- Trend extraction: [`OpenMeteoService.extractSolarTrend`](backend/src/main/java/com/gregochr/goldenhour/service/OpenMeteoService.java:1090).
- Trend model: [`SolarCloudTrend.java`](backend/src/main/java/com/gregochr/goldenhour/model/SolarCloudTrend.java),
  wrapped by [`CloudApproachData.java`](backend/src/main/java/com/gregochr/goldenhour/model/CloudApproachData.java).

### 1. What cloud data the prompt receives

Both point-in-time **and** trajectory, but asymmetrically:

- **Point-in-time** at the event hour: observer-point Low/Mid/High, plus the
  far-more-trusted **directional** Low/Mid/High at the 113 km solar horizon and
  113 km antisolar horizon, plus a 226 km far-solar low-cloud reading for
  strip-vs-blanket. (`buildUserMessage`, [PromptBuilder.java:378-401](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:378))
- **Trajectory**: a `Solar horizon low cloud trend (113km):` line listing
  `T-3h … event` low-cloud values, printed whenever `CloudApproachData` is
  present and its slot list is non-empty ([PromptBuilder.java:407-426](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:407)).

**So the raw clearing numbers DO physically appear in the prompt.** A clearance
(e.g. `T-3h=95% T-2h=80% T-1h=45% event=15%`) prints those four numbers.

### 2. Is the trend data fed to the prompt? — YES, already, on every evaluation

This is the most important factual finding and it overturns the task's
working assumption:

- `augmentWithCloudApproach(...)` is called **unconditionally** in **both**
  pipeline paths — the sync path ([ForecastService.java:190](backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java:190))
  and the **cache-aware batch path** ([ForecastService.java:341](backend/src/main/java/com/gregochr/goldenhour/service/ForecastService.java:341),
  the one that feeds `cached_evaluation`).
- The trend (`extractSolarTrend`) is built from T-3h→event regardless of
  direction — it is **not** gated on "building". A clearing series is captured
  identically to a building one.
- The slot numbers are printed regardless of `isBuilding()`.

So the trend data is **not** computed-for-triage-and-thrown-away. It is in the
per-location evaluation prompt today. **No new threading is needed to put the
trajectory in front of the model — it is already there.**

### 3. Does the prompt *instruct* the model on clearing? — Essentially NO

This is where the gap lives. The trend numbers are present, but the only
instruction attached to them is for the *opposite* direction:

- The block header is literally **`CLOUD APPROACH RISK:`** — pure negative
  framing.
- The only label the code emits is **`[BUILDING]`** (low cloud rising ≥20 pp,
  [`SolarCloudTrend.isBuilding()`](backend/src/main/java/com/gregochr/goldenhour/model/SolarCloudTrend.java:45)),
  and the only system-prompt instruction about the trend says *penalise*
  fiery_sky by 15–30 ([PromptBuilder.java:193-219](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:193)).
- There is **no `[CLEARING]` label, no `isClearing()`, and no instruction** that
  a downward low-cloud trajectory into the event is a positive.
- The only positive clearing reference anywhere is the generic, un-anchored
  fragment `"post-rain clearing often vivid"` ([PromptBuilder.java:51](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:51)).
  It is not tied to the trend block and gives the model no rule for reading the
  T-3h→event series as a clearance.

A second, quieter limitation: the trend carries **low cloud only**. The "canvas
remains" half of a dramatic clearance (mid/high persisting while the low blocker
lifts) is *not* in the trajectory — only the point-in-time directional mid/high
at the event hour speaks to the canvas. Persistence mirrors this: V51 stores
`solar_trend_earliest_low_cloud`, `solar_trend_event_low_cloud`,
`solar_trend_building` — all low-cloud / building-centric.

### 4. Is "cloud is the canvas" in the prompt? — YES, strongly

The prompt thoroughly encodes that clear sky is a liability and structured cloud
is the asset: `clear sky = 20-40` for fiery_sky ([PromptBuilder.java:184](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:184));
the IDEAL is clear solar horizon + mid/high canvas at 70–90 / rating 4–5
([:78-79](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:78));
and the **CLEAR SKY CAP** caps a cloudless sky at ≤3 because "there is no subject
in the sky" ([:110-113](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:110)).
The clearing-into-structured-cloud signal is therefore a *natural extension* of
the existing rubric, not a foreign concept — which is exactly why a clearance
that has **completed** by event time already scores well (see §2/§4 below).

---

## Section 2 — the fork resolved

**We are in the NO branch on the trajectory, with a YES on the end-state.**

- **End-state (YES, adequate).** If a clearance has finished by the event hour,
  the point-in-time directional rules see "solar horizon low cloud <20% + mid/high
  canvas present" and reward it at rating 4–5, fiery_sky 70–90. The *outcome* of
  a dramatic clearance is already scored as the strong sky it is. A snapshot
  cannot tell "always was clear" from "just cleared" — and for the end-state that
  does not matter, because both are genuinely good skies and both already score
  high.

- **Trajectory (NO).** The "overcast → breaking up right at the event" narrative
  — the part a photographer calls a *dramatic clearance*, carrying confidence and
  urgency the static snapshot lacks — is **not** rewarded. The data is present
  but lives under a `RISK` header with a `[BUILDING]`-only instruction set. A
  clearance still in progress at event time (low cloud dropping but not yet <20%)
  can even be *under*-scored, because the model sees elevated event-hour low cloud
  and has no rule telling it the trajectory is favourable.

So the honest characterisation is **present-but-weak**: the rating already
captures the clearance outcome; what is missing is recognition (and reward) of
the *clearing trajectory as such*. The fix — if Chris wants one — is a **prompt
reframe in the core evaluation**, not a detector and not new data.

---

## Section 3 — if NO branch: the shape of the prompt fix (scope only)

1. **Where it lands.** Entirely inside
   [`PromptBuilder.java`](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java):
   a `SYSTEM_PROMPT` instruction + a label in `buildUserMessage`'s existing trend
   block. Optionally an `isClearing()` companion to `isBuilding()` on
   `SolarCloudTrend`.

2. **Is the data reachable at prompt-build time? — Yes, already in scope.**
   The trend is on `AtmosphericData.cloudApproach().solarTrend()` and is already
   being printed. No threading like a new augmentor input is required. (Contrast
   the task's hypothesis that it might need surfacing — it does not.) The one
   genuine data gap is that the trend is **low-cloud only**; expressing "canvas
   remains" as a *trajectory* would need extending `SolarCloudTrend` to carry
   mid/high, but the point-in-time directional mid/high already covers the canvas
   adequately for a first cut.

3. **The photographic definition to encode (for the design conversation, not to
   pick here):**
   - Overcast → **broken-with-residual**, NOT overcast → clear.
   - Low cloud (the blocker) **drops** into the event *while* mid/high (the
     canvas) **persists**.
   - **Bounded against clearing to bald blue:** a trajectory that ends at
     low <5% *and* mid/high <5% is the CLEAR SKY CAP case (≤3), not a clearance.
   - **The naive signal — "cloud cover dropped" — is WRONG** by the app's own
     "cloud is the canvas" principle. Cloud dropping to nothing is a *liability*,
     not a clearance. The reward attaches only to "blocker clears, canvas stays".
   - Treat a confirmed clearing trajectory as adding *confidence/urgency* to a
     high score (mirror the `[BUILDING]` penalty's logic in reverse), not as a
     free rating boost on top of the point-in-time rules — to avoid double-counting
     the same clear-horizon-plus-canvas the static rules already reward.

4. **Right place in the prompt architecture.** The base `PromptBuilder` carries
   all sky-scoring rules; [`CoastalPromptBuilder`](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/CoastalPromptBuilder.java)
   `extends` it and only *appends* a surge section, so the clearance reasoning is
   inherited by coastal automatically. [`BluebellPromptBuilder`](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/BluebellPromptBuilder.java)
   is standalone and shares none of the sky rubric — correctly, since bluebell
   scoring does not care about sky clearance. **So the change belongs in the base
   `PromptBuilder` and nowhere else.**

---

## Section 4 — the redundancy argument, confirmed (with the edge)

**Confirmed: a clearance hot-topic would double-count.** A location undergoing a
dramatic clearance that *is* Claude-scored already gets:
- a **high rating** (4–5) once the horizon clears with canvas present (the
  point-in-time rules, §2), and
- **prose** describing it — the prompt explicitly invites pre-frontal /
  clearing language and headlines like *"Pre-frontal fire — mid cloud catches
  colour"* ([PromptBuilder.java:226](backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java:226)),
  plus the generic "post-rain clearing often vivid" nudge.

A "Dramatic clearance!" pill on top of an already-4–5★ card with clearance prose
is the same signal twice.

**The unscored-location edge does not justify a detector.** A clearance at a
location *not* Claude-scored this cycle (triaged out, or beyond the scoring
horizon — Gate 4 never scores T+4+) would get neither a rating nor a banner. But:
- A clearance is an inherently **short-fuse, near-term** phenomenon — its whole
  evidence is the T-3h→event trajectory. It matters most at **T+0/T+1**, which
  are *always* scored (NEAR=Sonnet). The unscored horizon (T+4+) barely overlaps
  with where clearances are forecastable at all.
- Where the overlap *does* exist (a marginal T+2/T+3 location triaged out), the
  consistent fix is to let the clearing reasoning improve **triage/evaluation
  eligibility**, not to bolt on a parallel banner that re-derives a quality
  judgement the rating system owns.

So the edge is real but thin, and better served by the core evaluation than by a
detector.

---

## What surprised me most

That the trajectory data is **already in the prompt on every evaluation,
including the batch/cached path** — and has been since V51. The task framed this
Step 0 around "is the trend surfaced to the evaluator, or computed-and-discarded
for triage?" The answer is: fully surfaced. The gap is not *data plumbing*, it is
**framing** — the exact same `T-3h … event` low-cloud series that would prove a
clearance is sitting under a header named `CLOUD APPROACH RISK` with a
penalise-only instruction set. The model is shown the evidence of a beautiful
clearance and told, in effect, only how to read it as a threat.

The second surprise: the point-in-time rules already reward the clearance
*outcome* so well that the "is clearance handled?" question genuinely splits —
the **result** is handled, the **trajectory/drama** is not. That split is what
makes a hot-topic banner clearly redundant for the result while leaving a small,
real, in-the-rating-prompt improvement for the trajectory.

---

## Decision for Chris

**Drop clearance as a hot-topic detector.** It double-counts the rating for any
clearance that is actually scored, and the unscored-location edge is thin and
better served by the core evaluation.

**If you want to do anything, do the small one:** a prompt reframe in the base
`PromptBuilder` so the *already-present* low-cloud trajectory is read as a
positive when it clears the blocker into the event while the canvas survives —
adding confidence/urgency to the score and the prose, not a second rating lever.

**The photographic signal to encode (whichever route):**
overcast → broken-with-**residual** (NOT → bald blue); **low cloud drops while
mid/high persists**; bounded by the CLEAR-SKY-CAP so clearing-to-clear is *not*
rewarded. The naive "cloud cover fell" reading is wrong by the app's own
canvas-is-the-asset principle — that tension is the whole subtlety.

**Out of scope here:** the exact prompt wording — that is the design
conversation, to be had against this finding.
