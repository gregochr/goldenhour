# P1 runbook (owner)

P1 measures wasted trips, the canvas rule and far cloud decks on the forecast history we already hold.
Plan: [docs/engineering/far-cloud-p1-plan.md](../../docs/engineering/far-cloud-p1-plan.md). Run everything
from this directory unless a command says otherwise. Outputs land in `out/` (git-ignored, as are `*.csv`
and `cache/`).

## 0. Prerequisites

- Python 3, standard library only. Nothing to install.
- JDK 25 and `ANTHROPIC_API_KEY` in your shell, for the Part D Maven replay only. On a Mac with only JDK 23, add
  `-Djava.version=23 -Dmaven.compiler.enablePreview=true -DargLine=--enable-preview` to every `./mvnw` command here
  (the test sources use the class-file API, which JDK 23 treats as preview; without it test compilation fails). Never paste the key into a
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
T=(-Pprompt-regression -Dtest=PromptReplayHarnessTest#replayStoredPrompts)   # an array: zsh does not word-split

# arms 1-3: production prompt on SONNET_55, SONNET, HAIKU
mkdir -p /tmp/p1-arms/arm1 /tmp/p1-arms/arm4 /tmp/p1-arms/training /tmp/p1-arms/hauxley
REPLAY_PROMPTS_DIR=/tmp/p1-replay REPLAY_N=3 ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/arm1/runs.csv

# arm 4: R1 prompt on SONNET_55 (the file must be the INLAND prompt; a coastal one is rejected)
REPLAY_PROMPTS_DIR=/tmp/p1-replay REPLAY_N=3 REPLAY_MODELS=SONNET_55 REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/arm4/runs.csv

# training cases (Angel 14:22 run 101110, Low Hauxley 86677 if still stored) on all three models, production prompt
REPLAY_PROMPTS_DIR=/tmp/p1-replay/training REPLAY_N=3 ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/training/runs.csv

# the pinned Low Hauxley prompt (hard stop: must stay 4 on arms 1, 2 and 4): production, then R1
mkdir -p /tmp/p1-hauxley && cp src/test/resources/p0-replay/low-hauxley-1004.coastal.txt /tmp/p1-hauxley/
REPLAY_PROMPTS_DIR=/tmp/p1-hauxley REPLAY_N=3 ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/hauxley/runs.csv
REPLAY_PROMPTS_DIR=/tmp/p1-hauxley REPLAY_N=3 REPLAY_MODELS=SONNET_55 REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/hauxley/runs-r1.csv
cd ../scripts/far-cloud-p1
```

**Batch variant (half price; same prompts, same bodies, results in minutes to hours).** Add `REPLAY_BATCH=true`
to any arm above. The harness submits one Message Batch per model instead of direct calls, after a one-request
primer batch per system prefix so the real requests read a 1-hour prompt cache (`REPLAY_PRIMER_WAIT_SECONDS`,
default 180, 0 = skip), then polls (`REPLAY_BATCH_POLL_SECONDS`, default 30; give up after
`REPLAY_BATCH_DEADLINE_MINUTES`, default 1440) and writes the same `runs.csv` and `summary.md`, with extra
`mode`, `batch_id` and token columns at the end (`replay_set.py` reads by column name, so it takes either).

It prints the request count per batch and a cost estimate, **then refuses to submit unless
`REPLAY_CONFIRM_COST` equals the printed total request count** (primers included). So run each arm twice: the
first time without it (the test fails with `Refusing to submit N request(s): set REPLAY_CONFIRM_COST=N`, having
spent nothing), the second with the number typed back. Option A, three arms, one run each (works in zsh and
bash; the settings are plain exports and `${T[@]}` is an array, so nothing depends on word splitting):

```
cd ../../backend
export ANTHROPIC_API_KEY=...        # in your shell only
T=(-Pprompt-regression -Dtest=PromptReplayHarnessTest#replayStoredPrompts)
export REPLAY_BATCH=true REPLAY_N=1 REPLAY_PROMPTS_DIR=/tmp/p1-replay
mkdir -p /tmp/p1-arms/arm1 /tmp/p1-arms/arm4

# arm 1: production prompt on SONNET_55 and SONNET (one batch each)
export REPLAY_MODELS=SONNET_55,SONNET
unset REPLAY_SYSTEM_PROMPT_FILE REPLAY_CONFIRM_COST
./mvnw test "${T[@]}"                                  # prints the plan and refuses: read N
REPLAY_CONFIRM_COST=<N> ./mvnw test "${T[@]}"          # submits and waits
cp target/p0-replay/runs.csv /tmp/p1-arms/arm1/runs.csv
cp target/p0-replay/batches.json /tmp/p1-arms/arm1/batches.json

# arm 4: R1 prompt on SONNET_55
export REPLAY_MODELS=SONNET_55 REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt
./mvnw test "${T[@]}"                                  # plan, refuses
REPLAY_CONFIRM_COST=<N> ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/arm4/runs.csv
cp target/p0-replay/batches.json /tmp/p1-arms/arm4/batches.json
unset REPLAY_BATCH REPLAY_MODELS REPLAY_SYSTEM_PROMPT_FILE   # so a later direct arm is not batched
```

**Trimmed Option A (about half the calls; the plan §12 step 2 recipe).** A stored production rating is one
sample from the model that made it, for that exact prompt, so it is that model's arm for free. `split` copies
the set into one directory per replay that is still needed, and `compare` uses the stored rating where the
model was not replayed. Nothing in `/tmp/p1-replay` is moved or changed.

```
python3 replay_set.py split --set /tmp/p1-replay --out /tmp/p1-replay-arms          # all 3-stars
python3 replay_set.py split --set /tmp/p1-replay --out /tmp/p1-replay-arms --three-sample 300   # or a seeded 300
python3 replay_set.py split --set /tmp/p1-replay --out /tmp/p1-replay-arms --control-sample 150  # recommended: + the control
```

| directory | prompts (2026-10-14 set) | replayed on | why |
|---|---|---|---|
| `need-sonnet/` | 1,112 (513 go, 599 3-star) | `SONNET`, production prompt | SONNET_55 rated these, so its arm is stored |
| `need-sonnet55/` | 204 (145 go, 59 3-star) | `SONNET_55`, production prompt | HAIKU rated these (Haiku is not a D-2 arm) |
| `r1-gos/` | 658 (go group only) | `SONNET_55`, R1 prompt | a 3-star tells R1 nothing: it can only demote |
| `training/` | 2 | all models, step c | as before |
| `control-sonnet55/` | N (`--control-sample`, 150 recommended; 69 go + 81 3-star at 150) | `SONNET_55`, production prompt | the noise floor: the same model on prompts it already rated (below) |

`split` refuses a target directory that already holds files (they would be replayed and billed), and prints
the calls and the **batch** cost per directory: half the standard `cost.*` prices, the same assumed 3,500 input
and 400 output tokens as `select`, thinking tokens and caching not included. Full 3-star group: **1,974 calls,
about $13.9 to $20.9**; `--three-sample 300`: **1,616 calls, about $11.0 to $16.6**. With
`--control-sample 150` the control adds 150 calls and **about $0.8 to $1.2** (`SONNET_55` at batch prices):
**2,124 calls, about $14.7 to $22.1** at the full 3-star group, **1,766 calls, about $11.9 to $17.8** at
`--three-sample 300`. It also writes `arms.json`, which `compare` checks the runs against.

**Why the control exists.** A stored rating is not a random sample of what its model says: the `three` group
is 3 by construction and the `go` group is 4 or more, so it was selected on its own value. Replay either group
and it regresses toward the mean, whichever model or prompt does the replaying: some gos fall below 4 and some
3-stars rise to 4 with **no** real difference. `compare` would otherwise read that drift as an effect (R1 "takes
x% of gos below 4", SONNET "rates y% of SONNET_55's 3-stars 4 or higher"). `--control-sample N` replays a seeded
random N of the SONNET_55-stored prompts (go and 3-star drawn in proportion to their size in that pool, from the
whole pool, never from what `--three-sample` kept) on **SONNET_55 with the production prompt**, the very model and
prompt that made the stored rating, so any move off it is selection plus sampling noise and nothing else.
`compare` prints that as the noise floor and sets it beside the two raw lines that read stored ratings (below).
It is the one deliberate self-replay, so `split` and `compare --arms-json` exempt it from the "replayed on the
model that rated it" refusal and instead refuse it on any model but SONNET_55, any prompt but the production one,
or any prompt SONNET_55 did not rate. It is never an arm: it is not pooled with, paired against or counted in any
decision line (a control prompt is also in `need-sonnet/` and, for a go, `r1-gos/`; that is intended).

Three Maven runs (four with `--control-sample`), each twice (the first prints the cost gate and spends nothing,
the second types the number back), from `backend/`:

```
cd ../../backend
export ANTHROPIC_API_KEY=...        # in your shell only
T=(-Pprompt-regression -Dtest=PromptReplayHarnessTest#replayStoredPrompts)
export REPLAY_BATCH=true REPLAY_N=1
mkdir -p /tmp/p1-arms/need-sonnet /tmp/p1-arms/need-sonnet55 /tmp/p1-arms/r1-gos /tmp/p1-arms/control-sonnet55
unset REPLAY_SYSTEM_PROMPT_FILE REPLAY_CONFIRM_COST

# 1. SONNET (4.6) on what SONNET_55 rated
export REPLAY_PROMPTS_DIR=/tmp/p1-replay-arms/need-sonnet REPLAY_MODELS=SONNET
./mvnw test "${T[@]}"                                  # prints the plan and refuses: read N
REPLAY_CONFIRM_COST=<N> ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/need-sonnet/runs.csv
cp target/p0-replay/batches.json /tmp/p1-arms/need-sonnet/batches.json

# 2. SONNET_55 on what HAIKU rated
export REPLAY_PROMPTS_DIR=/tmp/p1-replay-arms/need-sonnet55 REPLAY_MODELS=SONNET_55
./mvnw test "${T[@]}"
REPLAY_CONFIRM_COST=<N> ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/need-sonnet55/runs.csv
cp target/p0-replay/batches.json /tmp/p1-arms/need-sonnet55/batches.json

# 3. SONNET_55 with the R1 prompt on the go group
export REPLAY_PROMPTS_DIR=/tmp/p1-replay-arms/r1-gos REPLAY_MODELS=SONNET_55 REPLAY_SYSTEM_PROMPT_FILE=/tmp/sp/system-r1.txt
./mvnw test "${T[@]}"
REPLAY_CONFIRM_COST=<N> ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/r1-gos/runs.csv
cp target/p0-replay/batches.json /tmp/p1-arms/r1-gos/batches.json

# 4. (only with --control-sample) SONNET_55 with the PRODUCTION prompt on N prompts SONNET_55 already rated
unset REPLAY_SYSTEM_PROMPT_FILE      # the control must NOT carry the R1 prompt
export REPLAY_PROMPTS_DIR=/tmp/p1-replay-arms/control-sonnet55 REPLAY_MODELS=SONNET_55
./mvnw test "${T[@]}"
REPLAY_CONFIRM_COST=<N> ./mvnw test "${T[@]}"
cp target/p0-replay/runs.csv /tmp/p1-arms/control-sonnet55/runs.csv
cp target/p0-replay/batches.json /tmp/p1-arms/control-sonnet55/batches.json
unset REPLAY_BATCH REPLAY_MODELS REPLAY_SYSTEM_PROMPT_FILE REPLAY_PROMPTS_DIR REPLAY_CONFIRM_COST
cd ../scripts/far-cloud-p1

python3 replay_set.py compare --manifest /tmp/p1-replay/manifest.csv --arms-json /tmp/p1-replay-arms/arms.json \
  --runs /tmp/p1-arms/need-sonnet/runs.csv,need-sonnet /tmp/p1-arms/need-sonnet55/runs.csv,need-sonnet55 \
         /tmp/p1-arms/r1-gos/runs.csv,r1-gos --slots out/slots.csv --out out/part_d.md
# with the control, add:  /tmp/p1-arms/control-sonnet55/runs.csv,control-sonnet55
```

**Scripted run.** `run_option_a.sh` (this directory) does the Maven plan/confirm pairs above for you, in the order
`need-sonnet`, `need-sonnet55`, `r1-gos`, `control-sonnet55`, then `training-r1` (the 2-prompt training set on
`SONNET_55` with the R1 prompt, `REPLAY_N=20`). It reads the split from `$P1_HOME/p1-replay-arms/` and
`$P1_HOME/sp/system-r1.txt` (`P1_HOME` defaults to `~/goldenhour-p1`), needs `ANTHROPIC_API_KEY` exported in the
shell and never prints or writes it, and puts each arm's `plan.log`, `run.log` (and `run-resume.log` after a retry), `runs.csv`, `summary.md` and
`batches.json` in `$P1_HOME/arms/<arm>/` (use those paths, with the arm name as the label, in `compare`).

```
scripts/far-cloud-p1/run_option_a.sh --max-usd 30     # plan each arm, ask "Submit N requests for <arm>? [y/N]"
scripts/far-cloud-p1/run_option_a.sh --yes --max-usd 30   # no questions; the --max-usd ceiling still applies
scripts/far-cloud-p1/run_option_a.sh --dry-run            # fake plan, no Maven, writes nothing under $P1_HOME
scripts/far-cloud-p1/run_option_a.sh --only r1-gos        # one arm
MVN_JDK_FLAGS= scripts/far-cloud-p1/run_option_a.sh       # JDK 25 machine: drop the three JDK 23 flags
```

Per arm it runs Maven once without `REPLAY_CONFIRM_COST` (spending nothing), prints the request count and the cost
estimate, asks (unless `--yes`), then runs it again with `REPLAY_CONFIRM_COST=<N>`; a failed run is retried once
with `REPLAY_BATCH_RESUME=true`. `--max-usd X` aborts, submitting nothing, when an arm's printed **ceiling**
estimate (every reply at `max_tokens`) is above X; it is checked per arm, not summed over arms. An arm whose
`runs.csv` already exists in `$P1_HOME/arms/<arm>/` is skipped, so a rerun picks up where an earlier one stopped.
A run that finishes with failed attempts is still kept, flagged `submitted, F/T FAILED` in the summary. Anything already in
`backend/target/p0-replay` is moved to a `previous-<time>/` folder before a paid run. If an arm fails twice the script stops (the next arm would be refused while `batches.json` holds uncollected
batches); finish that arm by hand (check the console for an orphan batch first, as in the safety notes below) with
`REPLAY_BATCH_RESUME=true` and the same settings, copy its three files
into `$P1_HOME/arms/<arm>/` (`runs.csv` last), and rerun. Ctrl-C does not cancel a batch that was submitted.

Label each `runs.csv` with its directory name (`,need-sonnet`): that is how `--arms-json` matches a run to
its directory, and `compare` exits 2, writing nothing, if a run is on the wrong model or system prompt, holds a
prompt that is not in its directory, or replays a prompt on the model that already rated it. `stored` is a
reserved label.

The report then has, per replayed arm, the usual section, a `stored/SONNET_55 [production]` section marked
**STORED RATINGS, NOT A REPLAY** (it can show no fall or rise), and a **Decision lines** section: one line for
D-2 (the 4-star share of the 3-star sample, SONNET_55 vs SONNET, paired on the prompts that have both, with
numerator, denominator and how many of each side are stored vs replayed; a pair with a stored side goes on a
separate ONE-SIDED line, never into the gap) and one for D-1 part 2 (the share of
go prompts the R1 arm takes below 4, with the 15% cap). Expected calls: **about 1,112 + 204 + 658** at the full
3-star group (+ N for the control).

With a control run loaded the report adds a **CONTROL** section (the noise floor: of the control prompts, the
share whose replay moved off the stored rating, the control gos that fell below 4 and the control 3-stars that
rose to 4 or more, each as numerator/denominator over the prompts with a rated attempt), a **net** line under
the D-1 part 2 line (R1's below-4 share minus the control gos' own; descriptive only, the raw line above it
keeps the 15% cap), and a **floor** line under the ONE-SIDED D-2 line (SONNET's rise-to-4 share against the control 3-stars').
The 15% cap in plan §6 D-1 is on the **raw** share and stays there: `compare` never applies it to the net
figure, which only shows how much of the raw share the control reproduces with no R1 (a large floor means the
raw share is mostly drift, and that is for the owner to weigh). The control holds SONNET_55-stored prompts only, while R1 runs on every go (145 were rated by HAIKU).
The D-2 floor is set against SONNET's replays of SONNET_55-stored 3-stars only (a SONNET-stored side is not a
replay). A floor over fewer than 30 rated control prompts is printed with a THIN warning (one prompt is
100/n pp).

Read it with these limits in mind; they are the price of the trim:

- **A stored rating is one sample; a replay is not.** P0 found the replay 20 of 20 identical on the Angel, so a
  stored rating is probably what a replay would have said, but that is one prompt, not a measurement of the set.
- **The control measures the drift; it does not remove the selection.** It is one more replay of the same
  prompts by the same model, so it prices the regression to the mean in the other two lines, and a replay of
  its 3-stars on SONNET_55 is also a SONNET_55 side for them. `compare` deliberately does not pair those
  against the SONNET replays (a new decision line, not built; do not read D-2.1 off it by hand).
- **The 3-star group is 3 by selection, so D-2.1 cannot be confirmed from stored ratings.** For the 599 prompts
  SONNET_55 rated, its stored side is 3 for every prompt, 0% at 4 stars by construction, and D-2.1 needs
  SONNET_55 to be 8 points *above* SONNET. `compare` therefore prints those prompts as a **ONE-SIDED** line (how
  often SONNET rates a SONNET_55 3-star 4 or higher) with no gap figure, and prints a D-2 gap only over prompts
  where both sides were replayed. There are none in the trimmed run, so it can show SONNET_55 is *not* the
  generous one, not that it is. To get a real D-2.1 you must replay a sample of those 599 prompts on SONNET_55
  as well (a seeded 100 of them is about $0.5 to $0.8 at batch prices; put copies of the files in a directory
  of their own and run it as arm 2). The 59 HAIKU-rated 3-stars have a SONNET_55 replay and no SONNET one, so
  they are listed, not paired (and `--three-sample` may spend slots on them).
- **D-1 part 2 needs no stored arm**: it is the R1 replay against the stored go rating (>= 4 by selection).
  Angel and Low Hauxley (D-1 part 1) still come from the training and `hauxley` runs above.

`/tmp/p1-replay` for `REPLAY_PROMPTS_DIR` is the set from step a; the training and Low Hauxley sets take the same
`REPLAY_BATCH=true` prefix and their own `REPLAY_PROMPTS_DIR`.

Safety notes, all deliberate:

- Every batch id is written to `target/p0-replay/batches.json` the moment it exists. If the JVM dies or the
  deadline passes (the test then **fails**, and `runs.csv` holds `BatchNotCollected` rows: it is not a finished
  run), **rerun the same command with `REPLAY_BATCH_RESUME=true`**: it re-polls those ids and submits nothing
  that exists, so it needs no `REPLAY_CONFIRM_COST`. The one exception: if the run died before a model's batch
  was created (the plan it prints says `to submit`), that model is still to be spent and the confirmation is
  asked for again, for the printed number. A plan that changed since (prompts, models, `REPLAY_N`,
  system prompt) is refused on resume. A fresh run is refused while `batches.json` holds uncollected batches, so
  a second arm cannot overwrite the first arm's ids: collect or resume first, or delete the file knowing those
  batches still bill.
- A batch create is made with SDK retries off (a lost response may still have made the batch). If it fails,
  check the console at `console.anthropic.com` for an orphan batch before resuming.
- The estimate assumes about 400 output tokens a reply and a warm cache; the ceiling assumes every reply runs to
  `max_tokens`. Thinking tokens bill as output, so Sonnet 5.5 lands between the two. `summary.md` reports the
  actual token totals and a cost computed from them (primer batches are not in `runs.csv`).

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

What it established on 2026-10-14 (plan section 11): Previous Runs has no low/mid/high `_previous_dayN`, so
`case.py` and `size.py` read each model's **day-0** layers from the Historical Forecast API and report no
"as issued the day before" column; a 20-coordinate archive request keeps its order, and a sea point may be
answered from the neighbouring land cell (the probe reports those as "land-snapped", not as failures).
An `INFO` line in check (b) that says `_previous_day1 IS NOW SERVED` means the day-before arm can be restored.

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
(exit 2), re-run the same command later and it resumes from `cache/`. Read `out/pilot.md` before the full run. The pilot's reliability verdict is **per lead** (T+0 and T+1 each need 20 comparable pairs within 15 pp):
`size.py` then fetches forecast values only for the leads marked reliable and uses stored values for the rest.

## 7. Weak-canvas sizing (no network, no spend)

Sizes the Angel's 13 October shape (clear overhead, cirrus only at the horizon) and its mirror, a mid/high canopy
overhead rated 3 or lower, on the stored forecast rows. Definitions are at the top of `out/canvas.md` and in plan section 17.
Sizing only: it prints counts and shares and recommends nothing. It takes about a minute on the full export.

```
python3 canvas.py p1_forecasts.csv --out out/
```

Writes `out/canvas.md` and `out/canvas_weak_gos.csv` (every weak-canvas go with its slot key). The forecast export carries
no outcome ratings, so section 4 lists the slot keys to look up. To join your ratings, export them (personal data,
`user_id` is not exported) and re-run:

```
ssh gregochr@<host> 'docker exec -i goldenhour-db psql -X -U goldenhour -d goldenhour -v ON_ERROR_STOP=1 -f -' < export_outcome_ratings.sql > outcome_ratings.csv
python3 canvas.py p1_forecasts.csv --out out/ --ratings outcome_ratings.csv
```

Tests: `python3 -m unittest tests.test_canvas` from this directory.

## 8. Send back to Claude

`out/part_b.md`, `out/part_d.md`, `out/case_2026-10-13.md` (plus what you saw on Meteosat), `out/pilot.md`,
`out/part_c.md`, `out/canvas.md`, and `p1_summary.csv`. Not the prompt or forecast CSVs, and never the API key.
