#!/bin/bash
# Fold changelog.d/ entries into CHANGELOG.md under a new version heading.
#
# Usage: scripts/promote-changelog.sh <version-without-v> <date YYYY-MM-DD>
#
# Pure file transformation — no git, no prompts. release.sh calls this from the repo
# root on a clean tree during its promotion step; it is a separate script so the
# rewrite can be exercised against fixtures without walking release.sh's interactive
# orchestration (branch, PR, auto-merge, poll).
#
# What it does, and proves it did:
#   1. Collects changelog.d/*.md (excluding README.md), newest filename first —
#      filenames are YYYYMMDD-<slug>.md, so a reverse lexical sort is reverse
#      chronological.
#   2. Validates each entry's first non-blank line is a '### ' heading; any failure
#      exits non-zero with NOTHING changed.
#   3. Inserts below '## [Unreleased]': a blank line, '## [v<version>] - <date>',
#      then the collected entries verbatim (each preceded by one blank line, trailing
#      blank lines trimmed). Entries still written directly under [Unreleased] — the
#      old convention, or a straggler — follow naturally, unchanged, and end up under
#      the same new heading.
#   4. Verifies the rewrite before touching anything in place: the new heading appears
#      exactly once, no line of CHANGELOG.md was removed, and the number of added
#      lines equals the insertion block it built — the same prove-it-did-exactly-one-
#      thing discipline release.sh applied when the insertion was two fixed lines.
#   5. Writes CHANGELOG.md and deletes the folded entry files. The caller stages both.
#
# Counting is done with `grep -c ... || true` on files/here-strings, never
# `printf | grep -q`, for the SIGPIPE-under-pipefail reason documented in release.sh.

set -euo pipefail

if [[ $# -ne 2 ]]; then
    echo "Usage: $0 <version-without-v> <date YYYY-MM-DD>" >&2
    exit 1
fi
VERSION="$1"
TODAY="$2"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: version must be x.y.z (got: $VERSION)" >&2
    exit 1
fi
if [[ ! "$TODAY" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]]; then
    echo "Error: date must be YYYY-MM-DD (got: $TODAY)" >&2
    exit 1
fi
if [[ ! -f CHANGELOG.md ]]; then
    echo "Error: no CHANGELOG.md here — run from the repo root." >&2
    exit 1
fi
if ! grep -q '^## \[Unreleased\]' CHANGELOG.md; then
    echo "Error: CHANGELOG.md has no '## [Unreleased]' heading to insert below." >&2
    exit 1
fi

# Newline-separated rather than an array: the empty-array "${a[@]}" expansion is an
# unbound-variable error under set -u on the bash 3.2 macOS ships, and entry filenames
# are convention-bound to YYYYMMDD-<slug>.md (no whitespace), which the loop enforces.
# A shell glob rather than find(1): a suppressed find failure would silently empty the
# list and promote a heading with nothing folded under it — a half-promoted state the
# duplicate-heading guard then blocks a retry of — and the glob has no error path at
# all (an unmatched pattern stays literal and fails the -e test).
PENDING=""
for f in changelog.d/*.md; do
    [[ -e "$f" ]] || continue
    [[ "${f##*/}" == "README.md" ]] && continue
    PENDING="${PENDING}${f}"$'\n'
done
PENDING=$(printf '%s' "$PENDING" | LC_ALL=C sort -r)

BLOCK=$(mktemp)
PROMOTED=$(mktemp)
trap 'rm -f "$BLOCK" "$PROMOTED"' EXIT

{
    echo ""
    echo "## [v$VERSION] - $TODAY"
} > "$BLOCK"

if [[ -n "$PENDING" ]]; then
    while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        case "$f" in
            *[[:space:]]*)
                echo "Error: $f has whitespace in its name — entries are YYYYMMDD-<slug>.md." >&2
                exit 1
                ;;
        esac
        FIRST=$(awk 'NF { print; exit }' "$f")
        case "$FIRST" in
            '### '*) ;;
            *)
                echo "Error: $f does not open with a '### Category — title' heading" >&2
                echo "       (first non-blank line: ${FIRST:-<empty file>})" >&2
                echo "Fix the entry (see changelog.d/README.md); nothing was changed." >&2
                exit 1
                ;;
        esac
        echo "" >> "$BLOCK"
        # Trim leading and trailing blank lines so consecutive entries keep the file's
        # canonical one-blank-line spacing regardless of how each file happens to start
        # or end — the separator above is the one blank line between entries.
        awk '{ lines[NR] = $0 }
             END { s = 1; n = NR
                   while (s <= n && lines[s] ~ /^[[:space:]]*$/) s++
                   while (n >= s && lines[n] ~ /^[[:space:]]*$/) n--
                   for (i = s; i <= n; i++) print lines[i] }' "$f" >> "$BLOCK"
    done <<< "$PENDING"
fi

awk -v blockfile="$BLOCK" '
    { print }
    !inserted && /^## \[Unreleased\]/ {
        while ((getline line < blockfile) > 0) print line
        close(blockfile)
        inserted = 1
    }
' CHANGELOG.md > "$PROMOTED"

HEADING_COUNT=$(grep -c "^## \[v$VERSION\] - $TODAY" "$PROMOTED" || true)
if [[ "$HEADING_COUNT" -ne 1 ]]; then
    echo "Error: expected exactly one '## [v$VERSION] - $TODAY' heading after the rewrite, found $HEADING_COUNT." >&2
    echo "Refusing to write; CHANGELOG.md and changelog.d/ are unchanged." >&2
    exit 1
fi
ADDED=$(diff CHANGELOG.md "$PROMOTED" | grep -c '^> ' || true)
REMOVED=$(diff CHANGELOG.md "$PROMOTED" | grep -c '^< ' || true)
EXPECTED=$(wc -l < "$BLOCK" | tr -d ' ')
if [[ "$REMOVED" -ne 0 || "$ADDED" -ne "$EXPECTED" ]]; then
    echo "Error: the rewrite should add exactly the $EXPECTED-line insertion block and remove nothing;" >&2
    echo "       got $ADDED added / $REMOVED removed. Refusing to write; nothing was changed." >&2
    exit 1
fi

# Verified — now, and only now, touch the tree.
mv "$PROMOTED" CHANGELOG.md
# mv consumed $PROMOTED; recreate an empty file so the EXIT trap's rm has a target
# and set -u never sees an unset name.
PROMOTED=$(mktemp)

FOLDED=0
if [[ -n "$PENDING" ]]; then
    while IFS= read -r f; do
        [[ -z "$f" ]] && continue
        rm -- "$f"
        FOLDED=$((FOLDED + 1))
    done <<< "$PENDING"
fi

echo "Promoted to v$VERSION: folded $FOLDED changelog.d entr(y/ies); added $EXPECTED lines, removed 0."
