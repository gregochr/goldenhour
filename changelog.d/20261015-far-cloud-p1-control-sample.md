### Added — far-cloud P1 replay: a same-model control to price the stored rating's selection drift

`scripts/far-cloud-p1/replay_set.py split --control-sample N` (default 0, off; 150 recommended) writes a fourth
directory, `control-sonnet55/`: a seeded random N of the SONNET_55-stored prompts, go and 3-star drawn in
proportion, to replay on SONNET_55 with the production prompt, recorded in `arms.json` with role `control`, and
printed as a cost line in the split plan (about $0.8 to $1.2 at 150). A stored rating is selected on its own value
(the `three` group is 3 by construction, the `go` group 4 or more), so any replay compared with it regresses toward
the mean with no real difference; `compare` could not tell that from an effect. With the control loaded it prints
the noise floor (the share that moved off the stored rating, the control gos that fell below 4 and the control
3-stars that rose to 4, each with numerator and denominator), the D-1 part 2 line net of the control gos' own
below-4 share, and the one-sided D-2 line beside the control 3-stars' rise-to-4 share. The control is never an arm
and is not counted in any decision line; `compare` refuses (exit 2) a control run on another model or prompt.
Python standard library only, no Maven run, no Anthropic call.
