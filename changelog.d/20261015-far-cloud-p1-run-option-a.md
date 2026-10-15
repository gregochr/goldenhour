### Added — far-cloud P1: a script that runs the trimmed Option A replay arms

- `scripts/far-cloud-p1/run_option_a.sh` runs the five Part D replay arms (`need-sonnet`, `need-sonnet55`, `r1-gos`,
  `control-sonnet55`, `training-r1`) through the batch-mode `PromptReplayHarnessTest`: a plan run that spends nothing,
  the request count and cost estimate, a per-arm confirmation (or `--yes`), the confirmed run (retried once with
  `REPLAY_BATCH_RESUME=true`), and the three result files copied to `$P1_HOME/arms/<arm>/`.
- `--max-usd X` aborts before submitting when an arm's printed ceiling estimate is above X; `--only <arm>` runs one arm;
  `--dry-run` prints a fake plan and runs no Maven; an arm whose `runs.csv` already exists is skipped.
- The API key is only inherited from the environment, never printed or put on a command line; a log that somehow contains it is deleted.
