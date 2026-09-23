#!/bin/bash
# Tag a commit on main as a release version and push it,
# triggering the GitHub Actions deploy pipeline.
#
# Asks ONE question: the version. The commits since the last tag are printed
# first, so choosing the version is the go-ahead — the CHANGELOG promotion, tag
# and push then run without further prompts. It offers three versions — the
# next major, patch and minor — as 1/2/3 (Enter takes the patch).
#
# Usage: ./release.sh [VERSION] [--target REF] [-m MESSAGE]
#   VERSION       skip the question (e.g. ./release.sh 2.21.3)
#   --target REF  tag an earlier commit on main instead of HEAD
#   -m MESSAGE    tag message (default: "Release vVERSION")
#
# Safety checks:
#   - must be on main, working tree clean
#   - syncs with origin/main before tagging, and refuses to run when local main is
#     ahead (unpushed commits get swept into the CHANGELOG promotion PR)
#   - target commit defaults to HEAD (--target to override)
#   - verifies target is reachable from main
#   - shows commits between last tag and target for review
#   - confirms tag doesn't already exist
#   - promotes CHANGELOG's [Unreleased] entries to the version being tagged,
#     via an auto-merged docs-only PR, and refuses to tag if they are still
#     unpromoted or if nothing is documented for the version
#   - stops to ask only if main gained an unexpected commit during the promotion
#   - after pushing the tag, watches the Deploy workflow and reports (with a bell
#     and a macOS notification) when production is deployed or the deploy fails

set -euo pipefail

# 0. Arguments
VERSION=""
TARGET="HEAD"
MESSAGE=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --target) TARGET="${2:?--target needs a commit ref}"; shift 2 ;;
        -m)       MESSAGE="${2:?-m needs a message}"; shift 2 ;;
        -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d; s/^# \{0,1\}//'; exit 0 ;;
        -*)       echo "Error: unknown option $1 (see ./release.sh --help)"; exit 1 ;;
        *)        VERSION="$1"; shift ;;
    esac
done

# 1. Branch check
BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$BRANCH" != "main" ]]; then
    echo "Error: not on main (current: $BRANCH)"
    echo "Releases tag from main only. Switch with: git checkout main"
    exit 1
fi

# 1.5. Anchor to the repo root.
#
# The promotion block reads and rewrites CHANGELOG.md by relative path. Run from a
# subdirectory, that resolves to nothing — and it would fail AFTER the branch had been
# created and checked out, stranding the user mid-promotion with no message of its own.
# Every git command here is already root-relative, so this only ever helps.
cd "$(git rev-parse --show-toplevel)"

# 2. Clean working tree check
if [[ -n "$(git status --porcelain)" ]]; then
    echo "Error: working tree not clean. Commit or stash first."
    git status --short
    exit 1
fi

# 3. Sync with remote
echo "Fetching from origin..."
git fetch origin --tags --quiet

# "Differs from origin/main" is three states, not one, and only one of them is safe
# to fast-forward. Behind is the ordinary case. Ahead is fatal: the promotion block
# below cuts its branch from LOCAL main, so an unpushed commit rides into the
# changelog PR and comes back squashed, leaving main un-fast-forwardable at step 9.
# `git pull --ff-only` does not catch this — on an ahead branch it prints
# "Already up to date." and exits 0, which is exactly how a stray commit got into
# the v2.17.6 promotion PR (#380).
COUNTS=$(git rev-list --left-right --count main...origin/main)
AHEAD=${COUNTS%%[[:space:]]*}
BEHIND=${COUNTS##*[[:space:]]}

if [[ "$AHEAD" -gt 0 ]]; then
    echo "Error: local main is $AHEAD commit(s) ahead of origin/main."
    echo ""
    git log --oneline origin/main..main
    echo ""
    echo "Release branches are cut from local main, so these would be swept into"
    echo "the CHANGELOG promotion PR and squash-merged under its commit message."
    echo "Push them first, or drop them with: git reset --hard origin/main"
    exit 1
fi

if [[ "$BEHIND" -gt 0 ]]; then
    echo "Local main is $BEHIND commit(s) behind origin/main. Fast-forwarding..."
    git pull --ff-only origin main
fi

# 4. Target commit (default HEAD; --target to override)
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

# 6. Version — the one question. Offers major / patch / minor bumps of the latest
# tag; answer 1-3, Enter for the patch, or type any x.y.z.
if [[ -z "$VERSION" ]]; then
    MAJOR_V="" PATCH_V="" MINOR_V=""
    if [[ "$CURRENT" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
        MAJOR_V="$((BASH_REMATCH[1] + 1)).0.0"
        PATCH_V="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))"
        MINOR_V="${BASH_REMATCH[1]}.$((BASH_REMATCH[2] + 1)).0"
        echo "Next version:"
        printf "  1) %-9s major\n" "$MAJOR_V"
        printf "  2) %-9s patch (default)\n" "$PATCH_V"
        printf "  3) %-9s minor\n" "$MINOR_V"
    fi
    read -p "Choose 1-3, Enter for ${PATCH_V:-?}, or type a version (Ctrl-C to stop): " VERSION
    case "$VERSION" in
        1) VERSION="$MAJOR_V" ;;
        2|"") VERSION="$PATCH_V" ;;
        3) VERSION="$MINOR_V" ;;
    esac
fi

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
# Release notes arrive from TWO places now: one file per change under changelog.d/
# (the convention — it exists so two open PRs never conflict on the [Unreleased]
# line; see changelog.d/README.md) and, for stragglers or history, entries written
# directly under [Unreleased]. The promotion folds both under the new version
# heading via scripts/promote-changelog.sh.
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

# Entries also arrive as one file per change under changelog.d/ (see its README) —
# the convention that ended the every-two-PRs-conflict on the [Unreleased] line.
# Read at the TARGET commit like the body above; the fold itself runs on the working
# tree, which the target-is-main-HEAD and clean-tree guards make the same content.
#
# Refuse FIRST on anything in the directory that is not a dated entry. The fold's
# glob matches exactly YYYYMMDD-<slug>.md, so a dot-prefixed or misnamed tracked file
# is invisible to it while remaining visible to the accounting here — promoting would
# insert the heading, leave that file behind, trip the post-promotion leftover check,
# and the duplicate-heading guard would then block a retry at this version. The helper
# re-checks this on the promotion branch; checking here as well fails BEFORE any
# branch exists, so nothing is left to clean up.
STRAY_AT_TARGET=$(git ls-tree -r --name-only "$TARGET_SHA" -- changelog.d 2>/dev/null | grep -v 'README\.md$' | grep -Ev '^changelog\.d/[0-9]{8}-[^/]+\.md$' || true)
if [[ -n "$STRAY_AT_TARGET" ]]; then
    echo "Error: changelog.d/ contains files that are not YYYYMMDD-<slug>.md entries:"
    printf '%s\n' "$STRAY_AT_TARGET" | sed 's/^/  /'
    echo "The release fold would not see them and the release would wedge at its"
    echo "leftover check. Rename or remove them first (see changelog.d/README.md)."
    exit 1
fi
PENDING_AT_TARGET=$(git ls-tree -r --name-only "$TARGET_SHA" -- changelog.d 2>/dev/null | grep -E '^changelog\.d/[0-9]{8}-[^/]+\.md$' || true)

if [[ -n "$UNRELEASED_BODY" || -n "$PENDING_AT_TARGET" ]]; then
    DIRECT_COUNT=0
    if [[ -n "$UNRELEASED_BODY" ]]; then
        DIRECT_COUNT=$(printf '%s\n' "$UNRELEASED_BODY" | grep -c '^### ' || true)
    fi
    PENDING_COUNT=0
    if [[ -n "$PENDING_AT_TARGET" ]]; then
        PENDING_COUNT=$(printf '%s\n' "$PENDING_AT_TARGET" | grep -c '.' || true)
    fi
    ENTRY_COUNT=$((DIRECT_COUNT + PENDING_COUNT))
    PROMOTE_BRANCH="docs/changelog-v$VERSION"
    TODAY=$(date +%Y-%m-%d)

    echo ""
    echo "Pending release notes: $PENDING_COUNT changelog.d file(s) + $DIRECT_COUNT direct [Unreleased] entr(y/ies)."
    echo "These must be promoted to v$VERSION before tagging, or they will be"
    echo "attributed to whichever release happens to be cut next."
    echo ""

    # Refuse if v$VERSION is ALREADY promoted at the target. Every other guard here is
    # blind to this: inserting a second identical heading still adds exactly 2 lines and
    # removes 0, still leaves [Unreleased] empty, and the version check below is `grep -q`,
    # which matches the first of the two. So without this the script would tag a CHANGELOG
    # whose notes are split across two same-named sections carrying different dates —
    # precisely the attribution corruption section 7.5 exists to prevent.
    #
    # Reachable without any race, because the script's own exits create the state: the
    # promotion PR merges to main, and then the run stops before tagging — the user answers
    # "n" at the final confirmation, or Ctrl-Cs the poll, or hits the 15-minute timeout
    # whose message says "merge it, then re-run ./release.sh". main now carries the heading
    # with no tag. Later entries land under [Unreleased], the user re-runs at the same
    # version (correctly — step 7 confirms the tag is still free), and the duplicate lands.
    #
    # Here-string, not `printf … | grep -q`, for the SIGPIPE reason documented at the
    # version check further down.
    if grep -q "^## \[v$VERSION\]" <<< "$CHANGELOG_AT_TARGET"; then
        echo "Cannot promote: CHANGELOG.md already documents v$VERSION at the target."
        echo ""
        grep -n "^## \[v$VERSION\]" <<< "$CHANGELOG_AT_TARGET" | sed 's/^/  /'
        echo ""
        echo "An earlier run promoted this version but never tagged it, and new entries"
        echo "have accumulated under [Unreleased] since. Promoting again would create a"
        echo "SECOND '## [v$VERSION]' heading and split the notes between them."
        echo ""
        echo "Either tag v$VERSION as it stands (clear [Unreleased] by folding those"
        echo "entries into the existing section by hand), or release them under a new"
        echo "version number."
        exit 1
    fi

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
    BLOCKER=""
    if ! command -v gh >/dev/null 2>&1; then
        echo "Cannot promote automatically: the gh CLI is not installed."
        CAN_PROMOTE=0
    elif ! gh auth status >/dev/null 2>&1; then
        echo "Cannot promote automatically: gh is not authenticated (run: gh auth login)."
        CAN_PROMOTE=0
    elif git rev-parse --verify "$PROMOTE_BRANCH" >/dev/null 2>&1 ||
         git ls-remote --exit-code --heads origin "$PROMOTE_BRANCH" >/dev/null 2>&1; then
        echo "Cannot promote automatically: branch $PROMOTE_BRANCH already exists."
        CAN_PROMOTE=0
        BLOCKER="branch-exists"
    fi

    if [[ "$CAN_PROMOTE" -eq 0 ]]; then
        echo ""
        # The recipe has to match the blocker. Printing "git checkout -b $PROMOTE_BRANCH"
        # when the refusal was *that the branch already exists* hands the user a first
        # step guaranteed to fail, which reads as the script being broken rather than as
        # the branch needing attention.
        if [[ "$BLOCKER" == "branch-exists" ]]; then
            echo "That branch already carries a promotion, or is left over from one."
            if git rev-parse --verify "$PROMOTE_BRANCH" >/dev/null 2>&1; then
                echo "  local:  $(git log -1 --oneline "$PROMOTE_BRANCH")"
            fi
            if git ls-remote --exit-code --heads origin "$PROMOTE_BRANCH" >/dev/null 2>&1; then
                echo "  remote: origin/$PROMOTE_BRANCH exists"
            fi
            echo ""
            echo "Finish it (merge its PR), or clear it and re-run ./release.sh:"
            echo "  git branch -D $PROMOTE_BRANCH"
            echo "  git push origin --delete $PROMOTE_BRANCH"
        else
            echo "Promote by hand instead:"
            echo "  1. git checkout -b $PROMOTE_BRANCH"
            echo "  2. ./scripts/promote-changelog.sh $VERSION $TODAY"
            echo "     (inserts '## [v$VERSION] - $TODAY' below [Unreleased] and folds the"
            echo "      changelog.d entries beneath it, deleting the folded files)"
            echo "  3. git add -A -- CHANGELOG.md changelog.d && commit"
            echo "  4. Open a PR, merge it, then re-run ./release.sh against the merged commit."
        fi
        echo ""
        exit 1
    fi

    echo "Promotion is mechanical: it inserts the line"
    echo "    ## [v$VERSION] - $TODAY"
    echo "below [Unreleased], folds the changelog.d entries verbatim beneath it"
    echo "(deleting the folded files), changes no entry text, and opens a"
    echo "docs-only PR with auto-merge enabled."
    echo ""

    # From here the script owns a branch and a checkout. Any exit before the final
    # cleanup must put the user back on main rather than stranding them mid-promotion.
    #
    # INT and TERM as well as EXIT: the longest window here is a 15-minute poll, which is
    # exactly where a Ctrl-C is likely, and that is the worst moment to leave someone on a
    # promotion branch with a half-applied rewrite.
    #
    # Discarding CHANGELOG.md and changelog.d/ is safe and is what makes the checkout
    # reliable: step 2 guarantees a clean tree at start-up, so any modification present
    # here — the rewritten changelog, the deleted entry files — is the promotion script's
    # own mechanical work, and it is regenerable. Unstage first — after `git add`,
    # `git checkout --` restores from the index and would preserve it.
    restore_main() {
        git reset -q HEAD -- CHANGELOG.md changelog.d 2>/dev/null || true
        git checkout -- CHANGELOG.md changelog.d 2>/dev/null || true
        if [[ "$(git rev-parse --abbrev-ref HEAD)" != "main" ]]; then
            git checkout main --quiet 2>/dev/null || true
        fi
        return 0
    }
    trap restore_main EXIT INT TERM

    git checkout -b "$PROMOTE_BRANCH" --quiet

    # The rewrite, its validation and its prove-it-did-exactly-what-it-built check all
    # live in the helper (which exits non-zero having changed nothing on any failure) —
    # separate so it can be tested against fixtures without this script's orchestration.
    if ! ./scripts/promote-changelog.sh "$VERSION" "$TODAY"; then
        echo "Error: promotion rewrite failed — nothing committed. See the message above."
        exit 1
    fi

    # -A: the fold DELETES the changelog.d files it consumed, and deletions need -A to
    # stage through a pathspec.
    git add -A -- CHANGELOG.md changelog.d
    git commit --quiet -m "docs: promote [Unreleased] to v$VERSION" \
        -m "Mechanical: inserts the dated version heading and folds the changelog.d entries verbatim beneath it, deleting the folded files. No entry text is changed."
    git push --quiet -u origin "$PROMOTE_BRANCH"

    PR_URL=$(gh pr create --base main --head "$PROMOTE_BRANCH" \
        --title "docs: promote [Unreleased] to v$VERSION" \
        --body "Promotes ${ENTRY_COUNT:-0} entr(y/ies) — $PENDING_COUNT changelog.d file(s) folded verbatim + $DIRECT_COUNT direct — under a dated \`## [v$VERSION]\` heading so \`release.sh\` can tag. Opened automatically by \`release.sh\`; no entry text is changed.")
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
    trap - EXIT INT TERM
    git branch -D "$PROMOTE_BRANCH" --quiet 2>/dev/null || true
    git pull --ff-only origin main --quiet

    # Re-target onto the promotion commit. The tag must point at a commit that actually
    # contains the notes, which the originally-chosen target no longer is.
    TARGET_SHA=$(git rev-parse main)

    # The go-ahead given at the version prompt covered the commits listed then plus
    # the promotion commit. Anything else that merged during the wait was never shown,
    # so that case — and only that case — stops to ask.
    EXTRA=$(git rev-list --count "$MAIN_HEAD".."$TARGET_SHA")
    if [[ "$EXTRA" -ne 1 ]]; then
        echo ""
        echo "Warning: main gained $((EXTRA - 1)) commit(s) besides the promotion while waiting:"
        git log --oneline --reverse "$MAIN_HEAD".."$TARGET_SHA"
        read -p "Tag v$VERSION including these? (y/N): " CONFIRM_EXTRA
        if [[ "$CONFIRM_EXTRA" != "y" && "$CONFIRM_EXTRA" != "Y" ]]; then
            echo "Cancelled — the notes are promoted on main; re-run ./release.sh $VERSION to tag."
            exit 1
        fi
    fi
    CHANGELOG_AT_TARGET=$(git show "$TARGET_SHA:CHANGELOG.md" 2>/dev/null || true)

    if [[ -n "$(unreleased_body_at "$TARGET_SHA" || true)" ]]; then
        echo "Error: [Unreleased] is still non-empty after promotion."
        echo "Something landed on main mid-promotion. Nothing tagged."
        exit 1
    fi
    LEFTOVER=$(git ls-tree -r --name-only "$TARGET_SHA" -- changelog.d 2>/dev/null | grep -v 'README\.md$' || true)
    if [[ -n "$LEFTOVER" ]]; then
        echo "Error: changelog.d/ still holds pending entries after promotion:"
        printf '%s\n' "$LEFTOVER" | sed 's/^/  /'
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

# 8. Tag and push (tag message from -m, else the default)
TAG_MESSAGE="${MESSAGE:-Release v$VERSION}"
echo ""
echo "Tagging v$VERSION at $(git log -1 --oneline "$TARGET_SHA")"
git tag -a "v$VERSION" "$TARGET_SHA" -m "$TAG_MESSAGE"
git push origin "v$VERSION"

echo ""
echo "Tag v$VERSION pushed — pipeline deploying to production."

# 9. Watch the Deploy workflow until it finishes. The tag is already pushed, so
# nothing here can change the release — Ctrl-C only stops the watching.
if ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
    echo "Watch progress at: https://github.com/gregochr/goldenhour/actions"
    exit 0
fi

notify() {
    printf '\a'
    if command -v osascript >/dev/null 2>&1; then
        osascript -e "display notification \"$1\" with title \"release.sh\"" 2>/dev/null || true
    fi
}

echo "Waiting for the Deploy run to start (Ctrl-C stops watching; the deploy carries on)..."
RUN_ID=""
for _ in $(seq 1 24); do
    RUN_ID=$(gh run list --workflow deploy.yml --limit 20 \
        --json databaseId,headBranch \
        --jq ".[] | select(.headBranch == \"v$VERSION\") | .databaseId" 2>/dev/null | head -n 1 || true)
    [[ -n "$RUN_ID" ]] && break
    sleep 5
done
if [[ -z "$RUN_ID" ]]; then
    echo "No Deploy run for v$VERSION appeared within 2 minutes."
    echo "Check: https://github.com/gregochr/goldenhour/actions/workflows/deploy.yml"
    exit 1
fi
RUN_URL="https://github.com/gregochr/goldenhour/actions/runs/$RUN_ID"
echo "Deploy run: $RUN_URL"

START=$(date +%s)
LAST_JOB=""
while true; do
    RUN_JSON=$(gh run view "$RUN_ID" --json status,conclusion,jobs 2>/dev/null || echo '{}')
    STATUS=$(jq -r '.status // "unknown"' <<< "$RUN_JSON")
    if [[ "$STATUS" == "completed" ]]; then
        break
    fi
    JOB=$(jq -r '[.jobs[]? | select(.status == "in_progress") | .name] | join(", ")' <<< "$RUN_JSON")
    ELAPSED=$(( $(date +%s) - START ))
    if [[ -n "$JOB" && "$JOB" != "$LAST_JOB" ]]; then
        printf '\n  %dm%02ds  %s' $((ELAPSED / 60)) $((ELAPSED % 60)) "$JOB"
        LAST_JOB="$JOB"
    else
        printf '.'
    fi
    if [[ "$ELAPSED" -ge 3600 ]]; then
        echo ""
        echo "Still running after an hour — stopped watching. $RUN_URL"
        exit 1
    fi
    sleep 20
done
echo ""

CONCLUSION=$(jq -r '.conclusion // "unknown"' <<< "$RUN_JSON")
ELAPSED=$(( $(date +%s) - START ))
if [[ "$CONCLUSION" == "success" ]]; then
    echo ""
    echo "✅ v$VERSION is deployed to production ($((ELAPSED / 60))m$(printf '%02d' $((ELAPSED % 60)))s)."
    echo "   https://app.photocast.online"
    notify "v$VERSION deployed to production"
else
    echo ""
    echo "❌ Deploy of v$VERSION finished: $CONCLUSION"
    echo "   $RUN_URL"
    gh run view "$RUN_ID" --json jobs \
        --jq '.jobs[] | select(.conclusion != "success" and .conclusion != "skipped") | "   - \(.name): \(.conclusion)"' 2>/dev/null || true
    notify "v$VERSION deploy $CONCLUSION"
    exit 1
fi
