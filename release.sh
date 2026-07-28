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
#   - refuses to tag while CHANGELOG's [Unreleased] still holds entries,
#     and refuses if nothing is documented for the version being tagged
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
    git log "$TARGET_SHA" --oneline --reverse | head -20
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

# 7.5. CHANGELOG guard
#
# release.sh only ever tags — it cannot commit the promotion itself, because main
# requires a PR. So it guards instead: without this, [Unreleased] silently accumulates
# across releases and every set of release notes becomes indistinguishable from the
# next. That is exactly what happened between v2.15.4 and v2.17.0 — 83 entries across
# 13 releases piled under one heading, and untangling them afterwards needed a
# git-archaeology pass over the file's own history.
#
# Checked against the TARGET COMMIT, not the working tree: the tag ships whatever
# CHANGELOG.md looks like at that commit.

CHANGELOG_AT_TARGET=$(git show "$TARGET_SHA:CHANGELOG.md" 2>/dev/null || true)
if [[ -z "$CHANGELOG_AT_TARGET" ]]; then
    echo "Error: no CHANGELOG.md at $TARGET"
    exit 1
fi

UNRELEASED_BODY=$(printf '%s\n' "$CHANGELOG_AT_TARGET" | awk '
    /^## \[Unreleased\]/ { inside = 1; next }
    /^## \[/            { inside = 0 }
    inside && NF          { print }
' || true)

if [[ -n "$UNRELEASED_BODY" ]]; then
    ENTRY_COUNT=$(printf '%s\n' "$UNRELEASED_BODY" | grep -c '^### ' || true)
    echo ""
    echo "Error: CHANGELOG.md still has ${ENTRY_COUNT:-0} entr(y/ies) under [Unreleased]."
    echo ""
    echo "Promote them to this release before tagging, or they will be attributed to"
    echo "whichever release happens to be cut next:"
    echo ""
    echo "  1. git checkout -b docs/changelog-v$VERSION"
    echo "  2. In CHANGELOG.md, change:  ## [Unreleased]"
    echo "                          to:  ## [Unreleased]"
    echo "                               "
    echo "                               ## [v$VERSION] - $(date +%Y-%m-%d)"
    echo "  3. Open a PR, merge it, then re-run ./release.sh against the merged commit."
    echo ""
    exit 1
fi

if ! printf '%s\n' "$CHANGELOG_AT_TARGET" | grep -q "^## \[v$VERSION\]"; then
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
