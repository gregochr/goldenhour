### Fixed — `release.sh` no longer exits silently when origin has moved a tag

Step 3 ran `git fetch origin --tags --quiet` under `set -euo pipefail`. When the owner's local
`v2.22.2` tag pointed at 325438c9 and origin's `v2.22.2` had since been force-moved to c545e8d6 (the
first deploy from 325438c9 failed, so the release was re-tagged onto the fixed commit), git rejected
the update with `! [rejected] v2.22.2 -> v2.22.2 (would clobber existing tag)` and exited 1 —
`--quiet` suppressed that line, so the script printed "Fetching from origin..." and stopped with no
explanation.

The fetch now runs without `--quiet`, capturing its combined output instead. A plain failure prints
"Error: git fetch origin failed:" followed by git's full message and exits 1, same as before but
visible. A failure whose only complaint is one or more clobbered tags is handled rather than treated
as fatal: origin is authoritative for tags here (the Deploy workflow runs off origin's tags, and this
script is the only thing that pushes them), so each rejected tag is force-updated individually —
`git fetch origin --force refs/tags/<tag>:refs/tags/<tag>`, never a blanket `--force` across every
tag — with a notice naming the tag, the old local commit and the new one from origin. A local-only
tag origin doesn't have is left untouched. The plain fetch is re-run afterwards to pick up everything
else and to catch any other failure that might have been hiding behind the clobber. A clean fetch is
unchanged: silent, exit 0.
