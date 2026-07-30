#!/bin/bash
# Tag a commit on main as a release version and push it,
# triggering the GitHub Actions deploy pipeline.
#
# Prompts for the target commit (default: current main HEAD), so you can
# release HEAD or a specific earlier commit on main without command-line
# arguments. Useful when later commits are sitting on top of main but
# you want to release just the validated subset.
#
# Safety checks:
#   - must be on main, working tree clean
#   - syncs with origin/main before tagging
#   - prompts for target commit (default HEAD)
#   - verifies target is reachable from main
#   - shows commits between last tag and target for review
#   - confirms tag doesn't already exist
#   - offers to promote CHANGELOG's [Unreleased] entries to the version being
#     tagged, via an auto-merged docs-only PR, and refuses to tag if they are
#     still unpromoted or if nothing is documented for the version
#   - prompts for confirmation before pushing
#
# Usage: ./release.sh

set -euo pipefail

# 1. Branch check
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$BRANCH" != "main" ]]; then
    echo "Error: not on main (current: $BRANCH)"
    echo "Releases tag from main only. Switch with: git checkout main"
    exit 1
fi

# 2. Clean working tree check
if [[ -n "$(git status --porcelain)" ]]; then
    echo "Error: working tree not clean. Commit or stash first."
    git status --short
    exit 1
fi

# 3. Sync with remote
echo "Fetching from origin..."
git fetch origin --tags --quiet

LOCAL=$(git rev-parse main)
REMOTE=$(git rev-parse origin/main)
if [[ "$LOCAL" != "$REMOTE" ]]; then
    echo "Local main differs from origin/main. Fast-forwarding..."
    git pull --ff-only origin main
fi

# 4. Prompt for target commit (default: HEAD)
echo ""
read -p "Commit to tag (blank or 'HEAD' for current main HEAD, or a SHA/ref): " TARGET_INPUT
TARGET="${TARGET_INPUT:-HEAD}"

TARGET_SHA=$(git rev-parse --verify "$TARGET" 2>/dev/null || true)
if [[ -z "$TARGET_SHA" ]]; then
    echo "Error: '$TARGET' is not a valid commit reference"
    exit 1
fi

# Verify target is reachable from main (don't tag commits that aren't on main)
if ! git merge-base --is-ancestor "$TARGET_SHA" main; then
    echo "Error: $TARGET ($TARGET_SHA) is not reachable from main"
    echo "Releases tag commits on main only."
    exit 1
fi

# 5. Show what's about to be tagged
CURRENT=$(git tag | sort -V | tail -1 2>/dev/null || echo "none")
echo ""
echo "Current latest tag: $CURRENT"
echo "Target commit:      $(git log -1 --oneline "$TARGET_SHA")"
echo "Target date:        $(git log -1 --format=%cI "$TARGET_SHA")"
echo ""

if [[ "$CURRENT" != "none" ]]; then
    COMMIT_COUNT=$(git rev-list --count "$CURRENT".."$TARGET_SHA")
    if [[ "$COMMIT_COUNT" -eq 0 ]]; then
        echo "No new commits between $CURRENT and target. Nothing to release."
        exit 1
    fi
    echo "Commits between $CURRENT and target ($COMMIT_COUNT total):"
    git log "$CURRENT".."$TARGET_SHA" --oneline --reverse
else
    echo "No previous tag found. Showing recent commits up to target:"
    # `| head` has the same SIGPIPE-under-pipefail hazard as the grep below; -n caps
    # the output at the source instead of killing the writer mid-stream.
    git log "$TARGET_SHA" --oneline --reverse -n 20
fi
echo ""

# 6. Prompt for version with validation
read -p "New tag version (without v, e.g. 2.11.11): " VERSION

if [[ "$VERSION" =~ ^v ]]; then
    echo "Error: don't include the 'v' prefix — just the version number e.g. 2.8.2"
    exit 1
fi

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "Error: version must be in format x.y.z e.g. 2.8.2"
    exit 1
fi

# 7. Tag-already-exists check
if git rev-parse "v$VERSION" >/dev/null 2>&1; then
    echo "Error: tag v$VERSION already exists at $(git rev-parse --short v$VERSION)"
    echo "Delete it first if you really mean to retag:"
    echo "  git tag -d v$VERSION && git push origin :refs/tags/v$VERSION"
    exit 1
fi

# 7.5. CHANGELOG guard, with automatic promotion
#
# Without this, [Unreleased] silently accumulates across releases and every set of
# release notes becomes indistinguishable from the next. That is exactly what happened
# between v2.15.4 and v2.17.0 — 83 entries across 13 releases piled under one heading,
# and untangling them afterwards needed a git-archaeology pass over the file's own
# history.
#
# The promotion is mechanical — insert one dated heading directly below [Unreleased],
# changing no entry text — so the script performs it rather than printing instructions
# for a hand-made PR. That hand-made PR was the whole cost of the guard: it turned every
# release into two, and its CHANGELOG edit reliably conflicted with any feature branch
# that had also written under [Unreleased].
#
# It goes through a PR because main is protected. `enforce_admins` is currently false,
# so an admin *could* push the promotion straight to main — deliberately not what
# happens here. A release tool whose normal operating mode is bypassing branch
# protection makes the protection decorative for every other change too, and that
# capability is worth keeping manual.
#
# The wait is short despite CodeQL being a required check, because the required `CodeQL`
# context is published by the github-advanced-security app, NOT by the `CodeQL Analysis`
# workflow job, and it reports `neutral` within seconds for a diff touching no analysable
# code. Measured on the v2.17.4 promotion (#372): merged at 23:23:50Z while the workflow
# job ran on until 23:25:25Z — 95 seconds past the merge. ci.yml's own docs-only detection
# clears Backend and Frontend the same way. So a CHANGELOG-only PR gates in ~3-4 minutes
# and no workflow needed changing to make that true.
#
# Checked against the TARGET COMMIT, not the working tree: the tag ships whatever
# CHANGELOG.md looks like at that commit.

unreleased_body_at() {
    git show "$1:CHANGELOG.md" 2>/dev/null | awk '
        /^## \[Unreleased\]/ { inside = 1; next }
        /^## \[/            { inside = 0 }
        inside && NF          { print }
    '
}

CHANGELOG_AT_TARGET=$(git show "$TARGET_SHA:CHANGELOG.md" 2>/dev/null || true)
if [[ -z "$CHANGELOG_AT_TARGET" ]]; then
    echo "Error: no CHANGELOG.md at $TARGET"
    exit 1
fi

UNRELEASED_BODY=$(unreleased_body_at "$TARGET_SHA" || true)

if [[ -n "$UNRELEASED_BODY" ]]; then
    ENTRY_COUNT=$(printf '%s\n' "$UNRELEASED_BODY" | grep -c '^### ' || true)
    PROMOTE_BRANCH="docs/changelog-v$VERSION"
    TODAY=$(date +%Y-%m-%d)

    echo ""
    echo "CHANGELOG.md has ${ENTRY_COUNT:-0} entr(y/ies) under [Unreleased]."
    echo "These must be promoted to v$VERSION before tagging, or they will be"
    echo "attributed to whichever release happens to be cut next."
    echo ""

    # Promotion adds a commit ON TOP of main. If the target is an earlier commit, that
    # commit still carries the unpromoted CHANGELOG, so the tag would ship notes under
    # [Unreleased] regardless — the exact outcome this guard exists to prevent. Refuse
    # rather than appear to fix it.
    MAIN_HEAD=$(git rev-parse main)
    if [[ "$TARGET_SHA" != "$MAIN_HEAD" ]]; then
        echo "Cannot promote automatically: the target is not main HEAD."
        echo "  target:    $(git log -1 --oneline "$TARGET_SHA")"
        echo "  main HEAD: $(git log -1 --oneline "$MAIN_HEAD")"
        echo ""
        echo "A promotion commit would land on top of main and leave the target"
        echo "untouched, so the tag would still ship notes under [Unreleased]."
        echo "Either release main HEAD, or promote by hand onto the commit you want."
        exit 1
    fi

    CAN_PROMOTE=1
    if ! command -v gh >/dev/null 2>&1; then
        echo "Cannot promote automatically: the gh CLI is not installed."
        CAN_PROMOTE=0
    elif ! gh auth status >/dev/null 2>&1; then
        echo "Cannot promote automatically: gh is not authenticated (run: gh auth login)."
        CAN_PROMOTE=0
    elif git rev-parse --verify "$PROMOTE_BRANCH" >/dev/null 2>&1 ||
         git ls-remote --exit-code --heads origin "$PROMOTE_BRANCH" >/dev/null 2>&1; then
        echo "Cannot promote automatically: branch $PROMOTE_BRANCH already exists."
        echo "Delete it, or finish the promotion it already carries."
        CAN_PROMOTE=0
    fi

    if [[ "$CAN_PROMOTE" -eq 0 ]]; then
        echo ""
        echo "Promote by hand instead:"
        echo "  1. git checkout -b $PROMOTE_BRANCH"
        echo "  2. In CHANGELOG.md, change:  ## [Unreleased]"
        echo "                          to:  ## [Unreleased]"
        echo "                               "
        echo "                               ## [v$VERSION] - $TODAY"
        echo "  3. Open a PR, merge it, then re-run ./release.sh against the merged commit."
        echo ""
        exit 1
    fi

    echo "Promotion is mechanical: it inserts the single line"
    echo "    ## [v$VERSION] - $TODAY"
    echo "below [Unreleased], changing no entry text, and opens a docs-only PR"
    echo "with auto-merge enabled."
    echo ""
    read -p "Promote ${ENTRY_COUNT:-0} entr(y/ies) to v$VERSION now? (y/N): " DO_PROMOTE
    if [[ "$DO_PROMOTE" != "y" && "$DO_PROMOTE" != "Y" ]]; then
        echo "Cancelled — nothing tagged, nothing pushed."
        exit 1
    fi

    # From here the script owns a branch and a checkout. Any exit before the final
    # cleanup must put the user back on main rather than stranding them mid-promotion.
    restore_main() {
        if [[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ]]; then
            git checkout main --quiet 2>/dev/null || true
        fi
    }
    trap restore_main EXIT

    git checkout -b "$PROMOTE_BRANCH" --quiet

    PROMOTED=$(mktemp)
    awk -v ver="$VERSION" -v dt="$TODAY" '
        { print }
        !inserted && /^## \[Unreleased\]/ {
            print ""
            print "## [v" ver "] - " dt
            inserted = 1
        }
    ' CHANGELOG.md > "$PROMOTED"

    # Prove the rewrite did exactly one thing before committing it. A heading that
    # failed to insert, or entry text that moved, must not reach a release tag.
    if ! grep -q "^## \[v$VERSION\] - $TODAY" "$PROMOTED"; then
        rm -f "$PROMOTED"
        echo "Error: heading insertion failed — is there a '## [Unreleased]' line?"
        exit 1
    fi
    # `diff` exits 1 when files differ, which is the expected case here — hence `|| true`
    # on both. grep -c must read its input to completion, so neither pipeline can take
    # the SIGPIPE-under-pipefail failure documented at the version check below.
    ADDED=$(diff CHANGELOG.md "$PROMOTED" | grep -c '^> ' || true)
    REMOVED=$(diff CHANGELOG.md "$PROMOTED" | grep -c '^< ' || true)
    if [[ "$ADDED" -ne 2 || "$REMOVED" -ne 0 ]]; then
        rm -f "$PROMOTED"
        echo "Error: expected exactly 2 added and 0 removed lines, got $ADDED/$REMOVED."
        echo "Refusing to commit a promotion that changed entry text."
        exit 1
    fi

    mv "$PROMOTED" CHANGELOG.md
    git add CHANGELOG.md
    git commit --quiet -m "docs: promote [Unreleased] to v$VERSION" \
        -m "Mechanical: inserts the dated version heading only. No entry text is changed, reordered, or removed."
    git push --quiet -u origin "$PROMOTE_BRANCH"

    PR_URL=$(gh pr create --base main --head "$PROMOTE_BRANCH" \
        --title "docs: promote [Unreleased] to v$VERSION" \
        --body "Promotes ${ENTRY_COUNT:-0} entr(y/ies) under a dated \`## [v$VERSION]\` heading so \`release.sh\` can tag. Opened automatically by \`release.sh\`; the diff is two added lines.")
    echo "Opened $PR_URL"

    if ! gh pr merge "$PR_URL" --squash --auto >/dev/null 2>&1; then
        echo ""
        echo "Error: could not enable auto-merge on $PR_URL."
        echo "The branch is pushed and the PR is open — merge it, then re-run ./release.sh."
        exit 1
    fi
    echo "Auto-merge enabled. Waiting for the required checks..."

    # Bounded. A promotion that has not merged in 15 minutes has hit something this
    # script cannot resolve, and silently waiting longer is worse than saying so: the
    # branch is already pushed, so finishing by hand costs one merge.
    POLL_SECONDS=15
    MAX_WAIT=900
    WAITED=0
    while true; do
        PR_STATE=$(gh pr view "$PR_URL" --json state --jq .state 2>/dev/null || echo "UNKNOWN")
        [[ "$PR_STATE" == "MERGED" ]] && break
        if [[ "$PR_STATE" == "CLOSED" ]]; then
            echo ""
            echo "Error: $PR_URL was closed without merging. Nothing tagged."
            exit 1
        fi
        if [[ "$WAITED" -ge "$MAX_WAIT" ]]; then
            echo ""
            echo "Error: $PR_URL has not merged after $((MAX_WAIT / 60)) minutes."
            echo "The branch is pushed and the PR is open — merge it, then re-run ./release.sh."
            gh pr checks "$PR_URL" 2>&1 | head -10 || true
            exit 1
        fi
        sleep "$POLL_SECONDS"
        WAITED=$((WAITED + POLL_SECONDS))
        printf '  ...%ds\r' "$WAITED"
    done
    echo ""
    echo "Merged. Syncing main..."

    git checkout main --quiet
    trap - EXIT
    git branch -D "$PROMOTE_BRANCH" --quiet 2>/dev/null || true
    git pull --ff-only origin main --quiet

    # Re-target onto the promotion commit. The tag must point at a commit that actually
    # contains the notes, which the originally-chosen target no longer is.
    TARGET_SHA=$(git rev-parse main)
    CHANGELOG_AT_TARGET=$(git show "$TARGET_SHA:CHANGELOG.md" 2>/dev/null || true)

    if [[ -n "$(unreleased_body_at "$TARGET_SHA" || true)" ]]; then
        echo "Error: [Unreleased] is still non-empty after promotion."
        echo "Something landed on main mid-promotion. Nothing tagged."
        exit 1
    fi

    echo ""
    echo "Promoted. Target is now:"
    echo "  $(git log -1 --oneline "$TARGET_SHA")"
    echo ""
    echo "Commits between $CURRENT and target ($(git rev-list --count "$CURRENT".."$TARGET_SHA") total):"
    git log "$CURRENT".."$TARGET_SHA" --oneline --reverse
    echo ""
fi

# NOTE: a here-string, deliberately NOT `printf ... | grep -q`.
#
# `grep -q` exits the moment it matches. With `set -o pipefail` (line 23) and a
# CHANGELOG this size, printf is still writing hundreds of KB when grep leaves, takes
# SIGPIPE, and exits 141 — so the pipeline reports failure even though grep MATCHED.
# The guard then insists the section is missing when it is present.
#
# The failure is inverted and self-concealing: the EARLIER the heading appears, the
# more data printf has left to write and the more reliably it breaks. A new release
# sits at the top of the file, so this fired on every correctly-promoted CHANGELOG and
# would have passed only if the notes were buried at the bottom. It cost a release
# cycle on v2.17.2. The `[Unreleased]` check above is immune only because awk reads its
# input to completion.
if ! grep -q "^## \[v$VERSION\]" <<< "$CHANGELOG_AT_TARGET"; then
    echo ""
    echo "Error: CHANGELOG.md has no '## [v$VERSION]' section at the target commit."
    echo ""
    echo "[Unreleased] is empty, so the notes were either never written or promoted"
    echo "under a different version number. Check the heading matches v$VERSION."
    echo ""
    exit 1
fi

echo "CHANGELOG: [Unreleased] is empty and v$VERSION is documented."

# 8. Optional tag message
read -p "Tag message (one-liner, optional, blank for default): " MESSAGE
TAG_MESSAGE="${MESSAGE:-Release v$VERSION}"

# 9. Final confirmation
echo ""
echo "About to create annotated tag v$VERSION at:"
echo "  $(git log -1 --oneline "$TARGET_SHA")"
echo "  message: $TAG_MESSAGE"
read -p "Proceed? (y/N): " CONFIRM
if [[ "$CONFIRM" != "y" && "$CONFIRM" != "Y" ]]; then
    echo "Cancelled."
    exit 0
fi

# 10. Tag and push
git tag -a "v$VERSION" "$TARGET_SHA" -m "$TAG_MESSAGE"
git push origin "v$VERSION"

echo ""
echo "Tag v$VERSION pushed — pipeline deploying to production."
echo "Watch progress at: https://github.com/gregochr/goldenhour/actions"
