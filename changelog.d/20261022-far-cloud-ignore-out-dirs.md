### Chore — far-cloud P1 output directories are ignored whatever their suffix

`scripts/far-cloud-p1/.gitignore` ignores `out*/` rather than `out/` alone: the Part B re-run of 2026-10-21 wrote to `out2/`, which git saw as untracked and which stopped `release.sh` on its working-tree check. A third run (`out3/`) no longer trips it.
