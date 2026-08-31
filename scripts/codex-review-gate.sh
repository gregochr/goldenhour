#!/usr/bin/env bash
#
# Codex review gate — fails a PR that Codex has not answered on.
#
# Codex is the only reviewer on this repo, so "CI is green" is not evidence that anything
# reviewed the diff. This script is the check that says it did. Run by
# .github/workflows/codex-review-gate.yml; see that file for the branch-protection setup.
#
# ── Codex answers in TWO ways, and only one of them is a review record ───────────────────
#
#   a review        it had suggestions  →  carries `commit_id`, so it pins to a SHA
#   a 👍 reaction   it had none         →  carries NO sha, and GitHub emits NO webhook for it
#
# The second row is why this polls instead of just listening for `pull_request_review`:
# there is no reaction event to subscribe to. It is also why freshness is reported rather
# than enforced by default — see REQUIRE_FRESH below.
#
# ⚠️ A 👍 CANNOT BE REFRESHED. GitHub allows one reaction of a given content per user, so once
# Codex has 👍'd a PR it cannot 👍 it again — a second request that finds nothing to say
# produces NO observable change. Codex also reviews on open / ready-for-review / an explicit
# `@codex review`, but NOT on push. So after a re-push, a PR whose only signal is a 👍 has no
# mechanism to produce a fresh one. Enforcing freshness there would deadlock every PR that
# takes a fix commit, which is most of them — and a gate everyone overrides gates nothing.
# So the default is: require a signal (hard), report staleness loudly (soft).
#
# Set the repo variable CODEX_GATE_REQUIRE_FRESH=true to make staleness fail instead. Only do
# that once you know an explicit `@codex review` yields a REVIEW rather than a silent 👍 on
# this repo, or you will be reaching for the override label constantly.
set -euo pipefail

BOT_LOGIN="chatgpt-codex-connector[bot]"
OVERRIDE_LABEL="codex-override"

# Assigned from the environment rather than read bare, so `check-unassigned-uppercase` can
# see them and a missing one fails here with a name instead of somewhere further down.
REPO="${REPO:?REPO is required}"
PR="${PR:?PR is required}"
HEAD_SHA="${HEAD_SHA:?HEAD_SHA is required}"
PR_AUTHOR="${PR_AUTHOR:-}"
IS_DRAFT="${IS_DRAFT:-false}"
IS_FORK="${IS_FORK:-false}"
REQUIRE_FRESH="${REQUIRE_FRESH:-false}"
POLL_SECONDS="${POLL_SECONDS:-30}"
DEADLINE_MINUTES="${DEADLINE_MINUTES:-15}"

say() { printf '%s\n' "$*"; }

# Everything the reader needs is in the check's own summary; a maintainer reading a red gate
# should not have to open the job log to find out what it wanted.
summary() {
  say "$*"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then printf '%s\n' "$*" >>"$GITHUB_STEP_SUMMARY"; fi
}

pass() { summary "✅ $*"; exit 0; }
fail() { summary "❌ $*"; exit 1; }

api() { gh api -H "Accept: application/vnd.github+json" "$@"; }

# The newest Codex review that names the CURRENT head, if any.
review_on_head() {
  api --paginate "repos/$REPO/pulls/$PR/reviews" \
    --jq "[.[] | select(.user.login == \"$BOT_LOGIN\") | select(.commit_id == \"$HEAD_SHA\")] | length" \
    2>/dev/null | awk '{ n += $1 } END { print n + 0 }'
}

# Any Codex review at all, newest first — used only to describe a stale state.
newest_review_sha() {
  api --paginate "repos/$REPO/pulls/$PR/reviews" \
    --jq "[.[] | select(.user.login == \"$BOT_LOGIN\")] | last | .commit_id // empty" \
    2>/dev/null | tail -1
}

# Codex's 👍 on the PR itself, as an ISO timestamp. Empty when absent.
thumbs_created_at() {
  api --paginate "repos/$REPO/issues/$PR/reactions" \
    --jq "[.[] | select(.user.login == \"$BOT_LOGIN\") | select(.content == \"+1\")] | last | .created_at // empty" \
    2>/dev/null | tail -1
}

head_committed_at() {
  api "repos/$REPO/commits/$HEAD_SHA" --jq '.commit.committer.date' 2>/dev/null
}

# One solicitation per head SHA. The marker is an HTML comment so the PR thread stays readable
# on a PR that takes several pushes.
solicit_review() {
  local marker="<!-- codex-gate:$HEAD_SHA -->"
  if api --paginate "repos/$REPO/issues/$PR/comments" --jq '.[].body' 2>/dev/null \
      | grep -qF "codex-gate:$HEAD_SHA"; then
    return 0
  fi
  if [ "$IS_FORK" = "true" ]; then
    say "Fork PR — the token is read-only, so no re-review can be requested from here."
    return 0
  fi
  say "Requesting a re-review for $HEAD_SHA."
  api --method POST "repos/$REPO/issues/$PR/comments" \
    -f "body=@codex review

The last Codex signal on this PR predates commit \`${HEAD_SHA:0:7}\`, and Codex does not
review on push. Requesting a look at the current head.

$marker" >/dev/null 2>&1 || say "Could not post the re-review request (continuing)."
}

# ── Exemptions ───────────────────────────────────────────────────────────────────────────

# A draft cannot be merged, and Codex reviews on ready-for-review. Polling one for 15 minutes
# on every push would bill the wait and prove nothing; `ready_for_review` re-runs this.
if [ "$IS_DRAFT" = "true" ]; then
  pass "Draft PR — the gate runs when it is marked ready for review."
fi

# ⚠️ Dependabot PRs are governed by the EXISTING policy in dependabot-auto-merge.yml, which
# auto-merges minor and patch bumps on green CI. Making this a required check without this
# exemption would block that automation on a Codex signal that may never come. Remove this
# branch if you confirm Codex reviews Dependabot PRs and you want them gated too.
if [ "$PR_AUTHOR" = "dependabot[bot]" ]; then
  pass "Dependabot PR — governed by dependabot-auto-merge.yml, not by this gate."
fi

if api "repos/$REPO/issues/$PR/labels" --jq '.[].name' 2>/dev/null | grep -qxF "$OVERRIDE_LABEL"; then
  pass "Overridden by the \`$OVERRIDE_LABEL\` label. This bypassed the only review on this repo — the label is the audit trail."
fi

# ── Poll ─────────────────────────────────────────────────────────────────────────────────

HEAD_TIME="$(head_committed_at || true)"
if [ -z "$HEAD_TIME" ]; then
  fail "Could not read the head commit $HEAD_SHA. Refusing to pass a gate that could not run its own check."
fi

say "Waiting for Codex on $HEAD_SHA (head committed $HEAD_TIME)."
say "Polling every ${POLL_SECONDS}s for up to ${DEADLINE_MINUTES}m."

deadline=$(( $(date +%s) + DEADLINE_MINUTES * 60 ))
stale_thumbs=""
solicited=false

while :; do
  if [ "$(review_on_head)" -gt 0 ]; then
    pass "Codex reviewed \`${HEAD_SHA:0:7}\`. Read its findings and address them before merging — a review means it had something to say."
  fi

  thumbs="$(thumbs_created_at || true)"
  if [ -n "$thumbs" ]; then
    # String compare is correct here: both are ISO-8601 UTC from the GitHub API, so
    # lexicographic order is chronological order.
    if [[ "$thumbs" > "$HEAD_TIME" || "$thumbs" == "$HEAD_TIME" ]]; then
      pass "Codex reviewed \`${HEAD_SHA:0:7}\` and had no suggestions (👍 at $thumbs)."
    fi
    stale_thumbs="$thumbs"
    if [ "$solicited" = false ]; then
      solicit_review
      solicited=true
    fi
  fi

  if [ "$(date +%s)" -ge "$deadline" ]; then break; fi
  sleep "$POLL_SECONDS"
done

# ── Deadline ─────────────────────────────────────────────────────────────────────────────

if [ -n "$stale_thumbs" ]; then
  reviewed_sha="$(newest_review_sha || true)"
  msg="Codex's newest signal (👍 at $stale_thumbs) PREDATES head \`${HEAD_SHA:0:7}\` (committed $HEAD_TIME)."
  if [ -n "$reviewed_sha" ]; then
    msg="$msg Its last review named \`${reviewed_sha:0:7}\`."
  fi
  if [ "$REQUIRE_FRESH" = "true" ]; then
    fail "$msg CODEX_GATE_REQUIRE_FRESH is set, so this is a failure. Comment \`@codex review\`, or apply \`$OVERRIDE_LABEL\` if Codex will not produce a fresh record."
  fi
  summary "⚠️ $msg"
  pass "Passing on the stale signal: a 👍 cannot be re-issued (GitHub allows one per user), and Codex does not review on push, so requiring a fresh one would deadlock. **The commits after that 👍 have not been reviewed — read them yourself before merging.**"
fi

# A review naming some OTHER commit is not "no answer" — say which, or the message sends the
# reader hunting for a review that is sitting right there on the PR.
stale_review="$(newest_review_sha || true)"
if [ -n "$stale_review" ]; then
  fail "Codex last reviewed \`${stale_review:0:7}\`, not head \`${HEAD_SHA:0:7}\`, and has not
answered for the current commits within ${DEADLINE_MINUTES}m. Comment \`@codex review\` to ask it
to look at the head, or apply the \`$OVERRIDE_LABEL\` label to merge without that review."
fi

fail "Codex has not answered on this PR within ${DEADLINE_MINUTES}m — no review and no 👍.
Absence of a review is NOT a pass: it usually means the review has not run yet.
Re-run this job once Codex has answered, comment \`@codex review\` to ask it, or apply the
\`$OVERRIDE_LABEL\` label to merge without the only review this repo has."
