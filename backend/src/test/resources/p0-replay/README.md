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

Defaults: models `SONNET_55,SONNET,HAIKU`, N = 20, 4 concurrent calls. Six prompts × three models × 20
runs is 360 calls. The results land in `target/p0-replay/summary.md` and `runs.csv`.

In a cloud session with JitPack blocked and only JDK 21 installed (as on 13 Oct 2026), the build
needs two workarounds, both local to the container:

- install the vendored `solar-utils` jar under the coordinates the POM asks for:
  `./mvnw install:install-file -Dfile=.m2/repository/com/gregochr/solar-utils/2.1.0/solar-utils-2.1.0.jar -DgroupId=com.github.gregochr -DartifactId=solar-utils -Dversion=2.1.0 -Dpackaging=jar`;
- compile at release 21 (`-Djava.version=21 -Dmaven.compiler.release=21`), with
  `entity/EvaluationModelTest.java` moved aside for the run (it uses the Java 24 `java.lang.classfile` API)
  and restored afterwards.
