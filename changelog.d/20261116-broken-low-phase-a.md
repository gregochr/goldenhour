### Added — broken-low cloud Phase A sizing scripts

`scripts/far-cloud-p1/bl_size.py` sizes the broken-low-canvas plan's Phase A from the existing forecast export:
how often a low deck with a 20-39% solar cone and no mid or high cloud occurs, how often it is forecast 1-2 stars
(E1) or described as having no canvas (E2), and the plan's gate to Phase B as an explicit decision block
(proceed, stop on a named criterion, or waiting for protocol ratings). `bl_export_ratings.sql` exports outcome
ratings with a non-personal `by_admin` flag, and `bl_today.sql` lists today's broken-low and control slots for the
owner's rating protocol. Read-only scripts, no production code and no spend.
