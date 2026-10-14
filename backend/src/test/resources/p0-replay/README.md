# P0 prompt replay inputs

Inputs for `PromptReplayHarnessTest`, the P0 step of `docs/engineering/far-cloud-lid-plan.md`. Each file
is one batch sky user message; the suffix picks the system prompt (`.inland.txt` → `PromptBuilder`,
`.coastal.txt` → `CoastalPromptBuilder`).

| File | Source |
|---|---|
| `angel-1013-1422.inland.txt` | `forecast_evaluation_prompt` for evaluation 101110: Angel of the North, 13 Oct 2026 sunset, the 14:22 UTC intraday run that rated 4★ (owner recorded 2★) |
| `angel-1013-0106.inland.txt` | Evaluation 99992: the same sunset, 01:06 UTC nightly run, rated 3★ |
| `angel-1422-var-low29.inland.txt` | 101110 with the cone low raised from 7 to 29% (cone line and the trend's `event=` slot); everything else verbatim |
| `angel-1422-var-high0.inland.txt` | 101110 with the cone high cut from 91 to 0% (same two places) |
| `angel-1422-var-low29-high0.inland.txt` | Both edits |
| `low-hauxley-1004.coastal.txt` | A copy of `prompt-regression/low-hauxley-2026-10-04-sunrise.txt` (evaluation 86677, the stored production prompt for a sky that was worth going): the control |

The two Angel prompts were exported with `psql -At` on 13 Oct 2026. They were pasted through a terminal
pager that wrapped long lines, so those wraps were rejoined by hand; whitespace inside a line may
therefore differ from the stored row, but no character within a line was changed.

The variants keep the 14:22 prompt's `[BUILDING]` label: the trend's earliest-to-peak rise (46% → 76%)
sets it, and that does not depend on the event slot.

## Running it

```bash
cd backend
ANTHROPIC_API_KEY=... REPLAY_PROMPTS_DIR=src/test/resources/p0-replay \
  ./mvnw test -Pprompt-regression -Dtest=PromptReplayHarnessTest
```

Optional inputs (environment variable or `-D` system property):

| Input | Effect |
|---|---|
| `REPLAY_MODELS`, `REPLAY_N`, `REPLAY_THREADS` | models, runs per prompt and model, concurrency |
| `REPLAY_SYSTEM_PROMPT_FILE` | the file's contents REPLACE the system prompt of every request (Part D's R1 arm). Unset, the production prompt is sent unchanged. The file is the *inland* prompt; a coastal request gets the coastal suffix appended unless the file already ends with it. The prompt used is recorded in `runs.csv` (`system_prompt` column: `production`, or `<file>@sha256:<12 hex>`) and in `summary.md` |
| `REPLAY_DUMP_SYSTEM_PROMPTS_DIR` | with `-Dtest=PromptReplayHarnessTest#dumpSystemPrompts`, writes `system-inland.txt` and `system-coastal.txt` (no API key needed) |

Prompt files may be named with an evaluation id, `101110.inland.txt` / `101110.coastal.txt`; the label in the
outputs is the part before the suffix.

Defaults: models `SONNET_55,SONNET,HAIKU`, N = 20, 4 concurrent calls. Six prompts × three models × 20
runs is 360 calls. The results land in `target/p0-replay/summary.md` and `runs.csv`.

In a cloud session with JitPack blocked and only JDK 21 installed (as on 13 Oct 2026), the build
needs two workarounds, both local to the container:

- install the vendored `solar-utils` jar under the coordinates the POM asks for:
  `./mvnw install:install-file -Dfile=.m2/repository/com/gregochr/solar-utils/2.1.0/solar-utils-2.1.0.jar -DgroupId=com.github.gregochr -DartifactId=solar-utils -Dversion=2.1.0 -Dpackaging=jar`;
- compile at release 21 (`-Djava.version=21 -Dmaven.compiler.release=21`), with
  `entity/EvaluationModelTest.java` moved aside for the run (it uses the Java 24 `java.lang.classfile` API)
  and restored afterwards.

## Part D recipe (`docs/engineering/far-cloud-p1-plan.md`)

Stored prompts are pruned after 30 days: export them first (`scripts/far-cloud-p1/export_prompts.sql`).

```bash
# 1. Build the set: latest prompted go per slot + an equal seeded sample of 3-star rows;
#    training cases go to <out>/training. Prints the count and a cost estimate (read it, approve it).
python3 scripts/far-cloud-p1/replay_set.py select --prompts p1_prompts.csv --forecasts p1_forecasts.csv \
  --out /tmp/p1-replay --runs 3

# 2. Build the R1 system prompt (read scripts/far-cloud-p1/r1_system_prompt_patch.md first).
cd backend
./mvnw test -Pprompt-regression -Dtest=PromptReplayHarnessTest#dumpSystemPrompts -DREPLAY_DUMP_SYSTEM_PROMPTS_DIR=/tmp/sp
cd .. && python3 scripts/far-cloud-p1/replay_set.py build-r1 --system-prompt /tmp/sp/system-inland.txt \
  --patch scripts/far-cloud-p1/r1_system_prompt_patch.md --out /tmp/sp/system-r1.txt

# 3. Arms 1-3: production prompt on SONNET_55, SONNET, HAIKU. Arm 4: R1 prompt on SONNET_55.
cd backend
ANTHROPIC_API_KEY=... REPLAY_PROMPTS_DIR=/tmp/p1-replay REPLAY_N=3 \
  ./mvnw test -Pprompt-regression -Dtest=PromptReplayHarnessTest#replayStoredPrompts
cp target/p0-replay/runs.csv /tmp/arms123.csv   # the outputs are overwritten by each run
ANTHROPIC_API_KEY=... REPLAY_PROMPTS_DIR=/tmp/p1-replay REPLAY_N=3 REPLAY_MODELS=SONNET_55 \
  REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt \
  ./mvnw test -Pprompt-regression -Dtest=PromptReplayHarnessTest#replayStoredPrompts
```

Hard stops: Low Hauxley (`low-hauxley-1004.coastal.txt`, replayed from this folder) must stay 4 on arms 1, 2
and 4; the prompt-regression suite and `PromptGoldenMasterTest` must not regress on any arm proposed for
production. Which builder a stored prompt used is approximated from location metadata
(`replay_set.py`'s `is_coastal_prompt`; production keys it on `AtmosphericData.tide() != null`).
