# P1 runbook (owner)

P1 measures wasted trips, the canvas rule and far cloud decks on the forecast history we already hold.
Plan: [docs/engineering/far-cloud-p1-plan.md](../../docs/engineering/far-cloud-p1-plan.md). Run everything
from this directory unless a command says otherwise. Outputs land in `out/` (git-ignored, as are `*.csv`
and `cache/`).

## 0. Prerequisites

- Python 3, standard library only. Nothing to install.
- JDK 25 and `ANTHROPIC_API_KEY` in your shell, for the Part D Maven replay only. Never paste the key into a
  file or command line; export it in the shell that runs Maven.
- Network to open-meteo.com for `probe.py`, `case.py` and `size.py` (the API has a daily budget; the scripts
  count it and resume from `cache/`). `premise.py` and `replay_set.py` need no network.
- SSH access to the production host for the exports. `<host>` below is that machine, e.g. `gregochr@<host>`.

## 1. Exports (read-only SQL; run once)

Stored prompts are pruned after 30 days, so **export prompts first**. From the Mac (the `.sql` is read
locally, the CSV lands locally):

```
cd scripts/far-cloud-p1
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' < export_prompts.sql  > p1_prompts.csv
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' < export_forecasts.sql > p1_forecasts.csv
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' < export_summary.sql   > p1_summary.csv
```

(On the host itself, drop the `ssh ...` wrapper and run the `docker exec ...` part with `< file.sql > file.csv`.)

Check them:

- `p1_prompts.csv` must be non-empty: `head -c 400 p1_prompts.csv` shows the header
  `evaluation_id,location_id,loc_name,...,user_message`. Line counts mislead (messages contain newlines).
- `head -1 p1_forecasts.csv` starts `evaluation_id,location_id,target_date,...` and **ends `loc_tide_types`**.
  Without that column `replay_set.py select` refuses to run: re-export.
- `p1_summary.csv` is small; read it whole.
- A psql error stops the export (`ON_ERROR_STOP=1`); an empty CSV means it failed. Do not commit the CSVs.

## 2. Part B: premise check (no network)

```
python3 premise.py p1_forecasts.csv --out out/
```

Writes `out/part_b.md`, `out/slots.csv` and `out/region_events.csv`.

## 3. Part D: replay (spends real money; read the estimate first)

```
# a. build the set. Prints counts and a COST RANGE; writes /tmp/p1-replay/*.txt, training/, manifest.csv
python3 replay_set.py select --prompts p1_prompts.csv --forecasts p1_forecasts.csv --out /tmp/p1-replay --runs 3

# b. build the R1 system prompt from the production one
(cd ../../backend && ./mvnw test -Pprompt-regression -Dtest=PromptReplayHarnessTest#dumpSystemPrompts -DREPLAY_DUMP_SYSTEM_PROMPTS_DIR=/tmp/sp)
python3 replay_set.py build-r1 --system-prompt /tmp/sp/system-inland.txt --patch r1_system_prompt_patch.md --out /tmp/sp/system-r1.txt
```

`dumpSystemPrompts` prints which files it wrote (or "WROTE NOTHING" if the directory is unset). `build-r1`
exits 1 if any patch text is missing or not unique.

**Stop and read before spending:** `r1_system_prompt_patch.md` (six replacements; the fiery_sky cap of 55 is
your choice) and the cost range from step a. The range is the assumed 3,500 input + 400 output tokens per
call times 1 to 1.5; thinking tokens bill as output and are not in the assumption, and prompt caching is
ignored. Approve before step c.

```
# c. one run per arm, from backend/. REPLAY_N=3. Copy runs.csv away after each: the next run overwrites it.
cd ../../backend
export ANTHROPIC_API_KEY=...        # in your shell only
T="-Pprompt-regression -Dtest=PromptReplayHarnessTest#replayStoredPrompts"

# arms 1-3: production prompt on SONNET_55, SONNET, HAIKU
mkdir -p /tmp/p1-arms/arm1 /tmp/p1-arms/arm4 /tmp/p1-arms/training /tmp/p1-arms/hauxley
REPLAY_PROMPTS_DIR=/tmp/p1-replay REPLAY_N=3 ./mvnw test $T
cp target/p0-replay/runs.csv /tmp/p1-arms/arm1/runs.csv

# arm 4: R1 prompt on SONNET_55 (the file must be the INLAND prompt; a coastal one is rejected)
REPLAY_PROMPTS_DIR=/tmp/p1-replay REPLAY_N=3 REPLAY_MODELS=SONNET_55 REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt ./mvnw test $T
cp target/p0-replay/runs.csv /tmp/p1-arms/arm4/runs.csv

# training cases (Angel 14:22 run 101110, Low Hauxley 86677 if still stored) on all three models, production prompt
REPLAY_PROMPTS_DIR=/tmp/p1-replay/training REPLAY_N=3 ./mvnw test $T
cp target/p0-replay/runs.csv /tmp/p1-arms/training/runs.csv

# the pinned Low Hauxley prompt (hard stop: must stay 4 on arms 1, 2 and 4): production, then R1
mkdir -p /tmp/p1-hauxley && cp src/test/resources/p0-replay/low-hauxley-1004.coastal.txt /tmp/p1-hauxley/
REPLAY_PROMPTS_DIR=/tmp/p1-hauxley REPLAY_N=3 ./mvnw test $T
cp target/p0-replay/runs.csv /tmp/p1-arms/hauxley/runs.csv
REPLAY_PROMPTS_DIR=/tmp/p1-hauxley REPLAY_N=3 REPLAY_MODELS=SONNET_55 REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt ./mvnw test $T
cp target/p0-replay/runs.csv /tmp/p1-arms/hauxley/runs-r1.csv
cd ../scripts/far-cloud-p1
```

The harness's `system_prompt` column in `runs.csv` keeps the production and R1 arms apart even in one
comparison. Then read the results:

```
python3 replay_set.py compare --manifest /tmp/p1-replay/manifest.csv \
  --runs /tmp/p1-arms/arm1/runs.csv,arm1 /tmp/p1-arms/arm4/runs.csv,arm4 \
         /tmp/p1-arms/training/runs.csv,training /tmp/p1-arms/hauxley/runs.csv,hauxley \
         /tmp/p1-arms/hauxley/runs-r1.csv,hauxley-r1 \
  --slots out/slots.csv --out out/part_d.md
```

`out/part_d.md`, per arm and model: gos (stored sky_rating >= 4, else rating) whose replay median falls
below 4; 3-star rows whose median rises to 4 or more; the per-location list; the premise class (Part B) of
the gos; the training rows and the pinned Low Hauxley prompt individually, with its hard-stop verdict.

Notes: the prompts replayed are the latest run before the event per slot (same rule as `size.py` and
`premise.py`); a latest run that is triaged, unrated or has no stored prompt is not a go. Coastal vs inland
is `loc_tide_types` non-empty (or a STORM SURGE block); residual: a coastal place with no stored tide
extremes used the inland prompt in production but is replayed coastal here. `--coastal-ids <file>` forces
evaluation ids to coastal.

## 4. Probe the API assumptions (must PASS before `case.py` / `size.py`)

```
python3 probe.py
```

Exits non-zero on any FAIL. If it stops at the hourly cap it sleeps to the next hour; add `--no-wait` to stop
instead (a re-run resumes from `cache/`).

## 5. Part A: the Angel's sun line

```
python3 case.py --row-csv p1_forecasts.csv --row-id 101110
```

Writes `out/case_2026-10-13.csv` and `.md`. Then do the **manual Meteosat step** the `.md` ends with: open
EUMETSAT's viewer for that evening and look along the printed bearing. That observation is the plan's only
non-model evidence. (`--dry-run` shows the call count without fetching.)

## 6. Part C: how common are far decks

```
python3 size.py p1_forecasts.csv --plan              # projected calls and days, no network
python3 size.py p1_forecasts.csv --pilot 100 --yes   # pilot: writes out/pilot.md and out/pilot.json
python3 size.py p1_forecasts.csv --yes               # full run: writes out/part_c.md
```

Without `--yes` a live run refuses and prints the exact command. The budget is per day: when it stops
(exit 2), re-run the same command later and it resumes from `cache/`. Read `out/pilot.md` before the full run.

## 7. Send back to Claude

`out/part_b.md`, `out/part_d.md`, `out/case_2026-10-13.md` (plus what you saw on Meteosat), `out/pilot.md`,
`out/part_c.md`, and `p1_summary.csv`. Not the prompt or forecast CSVs, and never the API key.
