# changelog.d — per-PR changelog entries

Every PR used to write directly under `CHANGELOG.md`'s `## [Unreleased]` heading — the same line
of the same file — so any two open PRs conflicted there, whatever code they touched. Entries now
land as **one file per change** in this directory; new files never conflict with each other, and
`CHANGELOG.md` itself is only ever rewritten by the release promotion.

## Adding an entry

Create `changelog.d/YYYYMMDD-<slug>.md` — date first so files sort chronologically, slug in
kebab-case (e.g. `20260901-map-callout-anchor.md`, no whitespace). Its content is exactly what you
would previously have written under `[Unreleased]`:

```markdown
### Fixed — the thing that was broken

One or more paragraphs in the changelog's usual voice, wrapped as this file wraps.
```

Rules:

- The **first non-blank line must be a `### Category — title` heading** (`Added`, `Fixed`,
  `Changed`, `Docs`, `Removed` — the changelog's existing vocabulary). The promotion script
  validates this and refuses the release fold otherwise.
- **One entry (one `###` block) per file.** Two changes in one PR = two files.
- **Do not write under `CHANGELOG.md`'s `[Unreleased]` any more.** A straggler that lands there
  anyway still gets promoted correctly — the fold carries it under the same version heading — but
  it re-opens the merge-conflict problem for that PR, which is the one thing this directory
  exists to end.
- **Never rewrite or delete another change's entry file.** They are consumed at release time.
- **The filename convention is enforced, loudly.** The release fold folds exactly
  `YYYYMMDD-<slug>.md` and refuses to run while anything else sits in this directory (a
  dot-prefixed, undated, or misplaced file would otherwise be invisible to the fold but visible
  to the release's accounting — a wedge, not a skip).

## What happens at release

`release.sh` (owner-run, as ever) calls `scripts/promote-changelog.sh <version> <date>` during its
promotion step. That script inserts the new `## [vX.Y.Z] - date` heading below `[Unreleased]`,
folds these files' contents **verbatim** beneath it (newest filename first, then anything still
sitting directly under `[Unreleased]`), proves the rewrite added exactly the block it built and
removed nothing, and deletes the folded files — all in the same auto-merged, docs-only promotion
PR the release flow already uses. If a release lands while your PR is open, your entry file rides
through the merge untouched and is picked up by the next release instead.
