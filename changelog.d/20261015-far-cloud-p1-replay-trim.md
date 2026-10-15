### Changed — far-cloud P1 Part D replay trimmed to about half by using stored ratings as one arm

`scripts/far-cloud-p1/replay_set.py` gains `split`, which copies the Part D prompt set into `need-sonnet/` (prompts
SONNET_55 rated, to replay on SONNET 4.6), `need-sonnet55/` (prompts HAIKU rated, to replay on SONNET_55), `r1-gos/`
(the go group only, for the R1 arm) and `training/`, with a per-directory `manifest.csv`, an `arms.json`, an optional
seeded `--three-sample N`, and a printed call count and batch-price cost estimate. `compare` stands the stored rating
in for the model that was not replayed, as a distinct `stored/<model> [production]` arm that is never presented as a
replay, prints the D-2 and D-1 part 2 figures as one line each with numerator, denominator and stored-vs-replayed
sides, and takes `--arms-json` to refuse runs that do not match the split. A stored rating is one sample, and a stored
3-star is 3 by selection, so the trimmed D-2 is a ONE-SIDED line with no gap figure (it cannot confirm D-2.1); the
README says so and names the control run. Python standard library only, no
Maven run, no Anthropic call.
