### Added — far-cloud P1 sizes the weak-canvas forecast shape (scripts only)

`scripts/far-cloud-p1/canvas.py` counts how often a go has clear overhead and cirrus only at the horizon
(the Angel's 13 October shape) and its mirror, a mid/high canopy overhead rated 3 or lower, from the
stored forecast export, with ERA5's view where a verification row exists. It makes no network calls and
recommends nothing; `export_outcome_ratings.sql` and `--ratings` join owner ratings once exported. The
headline counts are in `far-cloud-p1-plan.md` §17. No app, API or database change.
