#!/usr/bin/env bash
# Far-cloud P1, Part D: runs the "trimmed Option A" replay arms one after another through the
# batch-mode prompt replay harness (PromptReplayHarnessTest#replayStoredPrompts), so the owner
# does not have to type the plan/confirm/copy sequence five times by hand.
#
# Per arm: plan run (REPLAY_CONFIRM_COST unset: the harness prints the plan and a cost estimate,
# then refuses, spending nothing) -> parse N and the estimate -> optional --max-usd ceiling ->
# prompt (unless --yes) -> confirmed run (REPLAY_CONFIRM_COST=N; retried ONCE with
# REPLAY_BATCH_RESUME=true if it fails) -> copy runs.csv, summary.md, batches.json away.
# See README.md section 3 ("Scripted run").
#
# Usage: run_option_a.sh [--yes] [--max-usd X] [--only ARM] [--dry-run]
#
# The Anthropic key is only ever inherited by Maven through the environment. It is never printed,
# written, or placed on a command line by this script, and tracing is switched off.

set -euo pipefail
set +x

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BACKEND_DIR="$SCRIPT_DIR/../../backend"
A=${P1_HOME:-$HOME/goldenhour-p1}
# Maven runs from backend/, so every path handed to it must be absolute.
if [ ! -d "$A" ]; then
    printf 'run_option_a: P1_HOME is not a directory: %s\n' "$A" >&2
    exit 2
fi
A=$(cd "$A" && pwd)
ARMS_OUT="$A/arms"
ARMS_IN="$A/p1-replay-arms"
R1_PROMPT="$A/sp/system-r1.txt"
TEST_SELECTOR="PromptReplayHarnessTest#replayStoredPrompts"
# Blank this (MVN_JDK_FLAGS= ./run_option_a.sh) on a machine with JDK 25.
MVN_JDK_FLAGS=${MVN_JDK_FLAGS-"-Djava.version=23 -Dmaven.compiler.enablePreview=true -DargLine=--enable-preview"}
ARM_NAMES=(need-sonnet need-sonnet55 r1-gos control-sonnet55 training-r1)

ASSUME_YES=0
DRY_RUN=0
MAX_USD=""
ONLY=""
DRY_LOGS=""

# The arm being processed, set by set_arm.
ARM_DIR=""
ARM_MODELS=""
ARM_SP=""
ARM_N=""

# One entry per arm reported at the end.
R_NAME=()
R_N=()
R_STATUS=()
R_TOKENS=()

usage() {
    cat <<'EOF'
Usage: run_option_a.sh [--yes] [--max-usd X] [--only ARM] [--dry-run]

  --yes         do not ask before submitting each arm
  --max-usd X   abort, submitting nothing, if an arm's printed upper (ceiling) cost estimate exceeds X
  --only ARM    run one arm: need-sonnet, need-sonnet55, r1-gos, control-sonnet55 or training-r1
  --dry-run     print what would run, with a fake plan, and run no Maven
  -h, --help    this text

Inputs and outputs live under P1_HOME (default $HOME/goldenhour-p1). MVN_JDK_FLAGS holds the JDK 23
flags added to every Maven call; set it to the empty string on a JDK 25 machine.
ANTHROPIC_API_KEY must be exported in the shell that runs this script.
EOF
}

die() {
    printf 'run_option_a: %s\n' "$*" >&2
    exit 2
}

# Records one arm's outcome for the final summary: name, N, status, tokens.
record() {
    R_NAME+=("$1")
    R_N+=("$2")
    R_STATUS+=("$3")
    R_TOKENS+=("${4:-}")
}

print_summary() {
    local i=0
    echo
    echo "== Summary =="
    while [ "$i" -lt "${#R_NAME[@]}" ]; do
        printf '%-18s N=%-7s %-30s %s\n' "${R_NAME[$i]}" "${R_N[$i]}" "${R_STATUS[$i]}" "${R_TOKENS[$i]}"
        i=$((i + 1))
    done
}

# Prints the summary so far and exits: abort_run EXIT_CODE MESSAGE...
abort_run() {
    local rc=$1
    shift
    printf 'run_option_a: ABORT: %s\n' "$*" >&2
    print_summary
    exit "$rc"
}

# extract FILE GREP_REGEX SED_EXPR: the first line of FILE matching GREP_REGEX, through sed -n SED_EXPR.
extract() {
    { grep -m1 -E -- "$2" "$1" 2>/dev/null || true; } | sed -n "$3"
}

# attempt_counts FILE: "F/T" for the summary.md table (failed attempts / all attempts), or empty if there is none.
attempt_counts() {
    awk -F'|' '/^\| / && $2 !~ /Prompt/ { f += $9; t += $4 + $5 + $6 + $7 + $8 + $9 }
        END { if (NR > 0) printf "%d/%d", f, t }' "$1" 2>/dev/null || true
}

# stash_previous DIR: moves any runs.csv, summary.md and batches.json already in the harness output directory aside,
# so nothing left by an earlier run (a manual one, or an arm whose directory was deleted) can be mistaken for this
# arm's results or be resumed in its place.
stash_previous() {
    local src=$1 f stamp
    stamp=$(date +%Y%m%d-%H%M%S)
    for f in runs.csv summary.md batches.json; do
        if [ -e "$src/$f" ]; then
            mkdir -p "$src/previous-$stamp"
            mv "$src/$f" "$src/previous-$stamp/$f"
        fi
    done
}

set_arm() {
    case $1 in
        need-sonnet)      ARM_DIR=need-sonnet;      ARM_MODELS=SONNET;    ARM_SP="";           ARM_N=1 ;;
        need-sonnet55)    ARM_DIR=need-sonnet55;    ARM_MODELS=SONNET_55; ARM_SP="";           ARM_N=1 ;;
        r1-gos)           ARM_DIR=r1-gos;           ARM_MODELS=SONNET_55; ARM_SP="$R1_PROMPT"; ARM_N=1 ;;
        control-sonnet55) ARM_DIR=control-sonnet55; ARM_MODELS=SONNET_55; ARM_SP="";           ARM_N=1 ;;
        training-r1)      ARM_DIR=training;         ARM_MODELS=SONNET_55; ARM_SP="$R1_PROMPT"; ARM_N=20 ;;
        *) die "unknown arm: $1" ;;
    esac
}

# Deletes a log that contains the API key (it should never, but a log is the one place it could land).
scrub_log() {
    local f=$1
    [ -f "$f" ] || return 0
    if grep -qF -f <(printf '%s\n' "${ANTHROPIC_API_KEY:-}") "$f"; then
        rm -f "$f"
        echo "run_option_a: WARNING: $f contained the API key and was deleted" >&2
    fi
}

# The fake output of a plan run in --dry-run mode, shaped like the real harness's.
fake_plan() {
    echo "== Batch replay plan (Message Batches API, 50% of standard prices) =="
    echo "System prompt: dry-run"
    echo "  SONNET_55      6 request(s); to submit: 1 batch of 6 + 1 primer request(s); est. \$1.00 expected, \$2.00 ceiling"
    echo "TOTAL REQUESTS TO SUBMIT: 7 (primers included)"
    echo "ESTIMATED COST at batch prices: \$1.00 expected (400 output tokens each, system prompt read from cache), \$2.00 ceiling (every reply at max_tokens, thinking included). Thinking tokens bill as output; the figures are estimates, not a quote."
    echo "java.lang.IllegalStateException: Refusing to submit 7 request(s): set REPLAY_CONFIRM_COST=7 to confirm the plan printed above."
}

# run_maven LOG VAR=value...: one harness run with exactly the given REPLAY_* settings. Returns Maven's status.
run_maven() {
    local log=$1 rc=0 a confirm=0
    shift
    if [ "$DRY_RUN" = 1 ]; then
        for a in "$@"; do
            case $a in REPLAY_CONFIRM_COST=*) confirm=1 ;; *) ;; esac
        done
        {
            printf 'DRY-RUN: would run in %s:\n  env' "$BACKEND_DIR"
            for a in "$@"; do printf ' %s' "$a"; done
            printf ' ./mvnw test %s -Pprompt-regression -Dtest=%s\n' "$MVN_JDK_FLAGS" "$TEST_SELECTOR"
            if [ "$confirm" = 0 ]; then
                fake_plan
            fi
        } >"$log"
        [ "$confirm" = 1 ] || rc=1
        return "$rc"
    fi
    (
        cd "$BACKEND_DIR" || exit 1
        env "$@" ./mvnw test ${JDK_FLAGS[@]+"${JDK_FLAGS[@]}"} -Pprompt-regression "-Dtest=$TEST_SELECTOR"
    ) </dev/null >"$log" 2>&1 || rc=$?
    scrub_log "$log"
    return "$rc"
}

process_arm() {
    local name=$1 dir logdir prompts rc=0 total refused n ceiling reply tokens f src counts status
    local -a base_env
    set_arm "$name"
    dir="$ARMS_OUT/$name"
    prompts="$ARMS_IN/$ARM_DIR"
    src="$BACKEND_DIR/target/p0-replay"

    if [ -e "$dir/runs.csv" ]; then
        tokens=""
        if [ -f "$dir/summary.md" ]; then
            tokens=$(extract "$dir/summary.md" '^Tokens: ' 's/^Tokens: //p')
        fi
        echo "[$name] skipped: $dir/runs.csv already exists"
        record "$name" "-" "skipped (runs.csv exists)" "$tokens"
        return 0
    fi
    if [ "$DRY_RUN" = 1 ]; then
        # A dry run must leave the owner's arms directory untouched, logs included.
        logdir="$DRY_LOGS/$name"
        mkdir -p "$logdir"
    else
        mkdir -p "$dir"
        logdir=$dir
    fi
    for f in summary.md batches.json; do
        if [ -e "$dir/$f" ]; then
            record "$name" "?" "failed (partial arm directory)" ""
            abort_run 2 "$dir/$f exists but $dir/runs.csv does not: a half-copied arm. Move $dir aside and rerun."
        fi
    done

    base_env=("REPLAY_BATCH=true" "REPLAY_N=$ARM_N" "REPLAY_PROMPTS_DIR=$prompts" "REPLAY_MODELS=$ARM_MODELS")
    if [ -n "$ARM_SP" ]; then
        base_env+=("REPLAY_SYSTEM_PROMPT_FILE=$ARM_SP")
    fi

    echo "[$name] planning (REPLAY_CONFIRM_COST unset: nothing is submitted)..."
    run_maven "$logdir/plan.log" "${base_env[@]}" || rc=$?
    if [ "$rc" -eq 0 ] && [ "$DRY_RUN" = 0 ]; then
        record "$name" "?" "failed (plan run did not refuse)" ""
        abort_run 2 "the plan run for $name exited 0 instead of refusing; see $logdir/plan.log. Not continuing."
    fi
    total=$(extract "$logdir/plan.log" 'TOTAL REQUESTS TO SUBMIT: [0-9]+' 's/.*TOTAL REQUESTS TO SUBMIT: \([0-9][0-9]*\).*/\1/p')
    refused=$(extract "$logdir/plan.log" 'Refusing to submit [0-9]+ request' 's/.*Refusing to submit \([0-9][0-9]*\) request.*/\1/p')
    if [ -z "$total" ] && [ -z "$refused" ]; then
        record "$name" "?" "failed (no plan parsed)" ""
        echo "run_option_a: last relevant lines of $logdir/plan.log:" >&2
        { grep -E 'IllegalState|ERROR' "$logdir/plan.log" || true; } | tail -n 5 >&2
        abort_run 2 "could not parse the request count from $logdir/plan.log"
    fi
    if [ -n "$total" ] && [ -n "$refused" ] && [ "$total" != "$refused" ]; then
        record "$name" "?" "failed (plan disagrees)" ""
        abort_run 2 "plan.log names $total requests in one place and $refused in another"
    fi
    n=${total:-$refused}
    if [ "$n" -le 0 ]; then
        record "$name" "$n" "failed (nothing to submit)" ""
        abort_run 2 "the plan for $name has $n requests to submit; see $logdir/plan.log"
    fi
    echo "[$name] plan (N=$n):"
    { grep -E 'request\(s\); |TOTAL REQUESTS TO SUBMIT|ESTIMATED COST' "$logdir/plan.log" || true; } | awk '!seen[$0]++' | sed 's/^/    /'

    if [ -n "$MAX_USD" ]; then
        ceiling=$(extract "$logdir/plan.log" 'ESTIMATED COST at batch prices' 's/.*, \$\([0-9][0-9,]*\.[0-9][0-9]*\) ceiling.*/\1/p')
        ceiling=${ceiling//,/}
        if [ -z "$ceiling" ]; then
            record "$name" "$n" "aborted (no ceiling parsed)" ""
            abort_run 2 "--max-usd given but no ceiling estimate could be parsed from $logdir/plan.log"
        fi
        if awk -v c="$ceiling" -v m="$MAX_USD" 'BEGIN { exit !(c + 0 > m + 0) }'; then
            record "$name" "$n" "aborted (ceiling \$$ceiling > \$$MAX_USD)" ""
            abort_run 3 "$name's ceiling estimate \$$ceiling exceeds --max-usd $MAX_USD. Nothing was submitted for it."
        fi
    fi

    if [ "$ASSUME_YES" = 0 ]; then
        printf 'Submit %s requests for %s? [y/N] ' "$n" "$name" >&2
        reply=""
        read -r reply || true
        if [ ! -t 0 ]; then echo >&2; fi
        case $reply in
            y|Y) ;;
            *)
                echo "[$name] not submitted"
                record "$name" "$n" "skipped (declined)" ""
                return 0
                ;;
        esac
    fi

    if [ "$DRY_RUN" = 0 ]; then
        stash_previous "$src"
    fi
    echo "[$name] submitting $n requests and waiting (log: $logdir/run.log)..."
    rc=0
    run_maven "$logdir/run.log" "${base_env[@]}" "REPLAY_CONFIRM_COST=$n" || rc=$?
    if [ "$rc" -ne 0 ]; then
        echo "[$name] run failed (exit $rc); retrying once with REPLAY_BATCH_RESUME=true (log: $logdir/run-resume.log)..." >&2
        if [ "$DRY_RUN" = 0 ]; then
            rm -f "$src/runs.csv" "$src/summary.md"
        fi
        rc=0
        run_maven "$logdir/run-resume.log" "${base_env[@]}" "REPLAY_CONFIRM_COST=$n" "REPLAY_BATCH_RESUME=true" || rc=$?
    fi
    if [ "$rc" -ne 0 ]; then
        record "$name" "$n" "failed" ""
        if [ -f "$logdir/run-resume.log" ] && grep -q 'Refusing to submit' "$logdir/run-resume.log"; then
            echo "run_option_a: the resume run was refused: its plan differs from N=$n, so part of the work was already submitted before the failure." >&2
            echo "run_option_a: check the Anthropic console for an orphan batch from $name BEFORE confirming the new number (README section 3)." >&2
        fi
        abort_run 1 "$name failed twice. Batches may still be running and billing: see $src/batches.json and $logdir/run-resume.log, then resume by hand with REPLAY_BATCH_RESUME=true and the same settings (README section 3)."
    fi

    if [ "$DRY_RUN" = 1 ]; then
        echo "[$name] dry-run: would copy runs.csv, summary.md and batches.json to $dir"
        record "$name" "$n" "dry-run (would submit)" ""
        return 0
    fi
    for f in summary.md batches.json runs.csv; do
        if [ ! -f "$src/$f" ]; then
            record "$name" "$n" "failed (no $f)" ""
            abort_run 1 "$src/$f is missing after a successful run"
        fi
        if [ -e "$dir/$f" ]; then
            record "$name" "$n" "failed (would overwrite $f)" ""
            abort_run 1 "$dir/$f already exists; refusing to overwrite it. The results are still in $src"
        fi
    done
    # runs.csv goes last: its presence is what a rerun reads as "this arm is done".
    for f in summary.md batches.json runs.csv; do
        cp "$src/$f" "$dir/$f"
    done
    tokens=$(extract "$dir/summary.md" '^Tokens: ' 's/^Tokens: //p')
    counts=$(attempt_counts "$dir/summary.md")
    status="submitted"
    case $counts in
        ''|0/*) ;;
        *)
            status="submitted, $counts FAILED"
            echo "run_option_a: WARNING: $name has failed attempts ($counts failed/all) in $dir/summary.md; its runs.csv was kept." >&2
            ;;
    esac
    echo "[$name] done: copied to $dir"
    record "$name" "$n" "$status" "$tokens"
}

while [ "$#" -gt 0 ]; do
    case $1 in
        --yes) ASSUME_YES=1 ;;
        --dry-run) DRY_RUN=1 ;;
        --max-usd)
            [ "$#" -ge 2 ] || die "--max-usd needs a number"
            MAX_USD=$2
            shift
            ;;
        --only)
            [ "$#" -ge 2 ] || die "--only needs an arm name"
            ONLY=$2
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            usage >&2
            die "unknown argument: $1"
            ;;
    esac
    shift
done

if [ -n "$MAX_USD" ]; then
    case $MAX_USD in
        ''|*[!0-9.]*|.*|*.|*.*.*) die "--max-usd must be a plain number such as 30 or 12.5" ;;
        *) ;;
    esac
fi
if [ -n "$ONLY" ]; then
    known=0
    for name in "${ARM_NAMES[@]}"; do
        if [ "$name" = "$ONLY" ]; then known=1; fi
    done
    [ "$known" = 1 ] || die "--only: unknown arm '$ONLY' (one of: ${ARM_NAMES[*]})"
fi

# The key is tested, never printed.
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    die "ANTHROPIC_API_KEY is not set (export it in this shell; the script never prints it)"
fi
[ -f "$ARMS_IN/arms.json" ] || die "missing $ARMS_IN/arms.json (run replay_set.py split first)"
[ -f "$R1_PROMPT" ] || die "missing $R1_PROMPT"
if [ "$DRY_RUN" = 0 ]; then
    [ -x "$BACKEND_DIR/mvnw" ] || die "missing or not executable: $BACKEND_DIR/mvnw"
fi

# Nothing from the caller's environment may leak into an arm: every REPLAY_* variable goes.
while IFS= read -r v; do
    unset "$v"
done < <(compgen -v REPLAY_ || true)
read -r -a JDK_FLAGS <<<"$MVN_JDK_FLAGS"

for name in "${ARM_NAMES[@]}"; do
    if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then continue; fi
    set_arm "$name"
    [ -d "$ARMS_IN/$ARM_DIR" ] || die "missing prompt directory $ARMS_IN/$ARM_DIR"
done

trap 'echo >&2; echo "run_option_a: interrupted. A batch that was already submitted still runs and bills; resume it with REPLAY_BATCH_RESUME=true and the same settings (README section 3)." >&2; exit 130' INT TERM

if [ "$DRY_RUN" = 0 ]; then
    mkdir -p "$ARMS_OUT"
else
    DRY_LOGS=$(mktemp -d "${TMPDIR:-/tmp}/run_option_a_dryrun.XXXXXX")
    trap 'rm -rf "$DRY_LOGS"' EXIT
fi
for name in "${ARM_NAMES[@]}"; do
    if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then continue; fi
    process_arm "$name"
done

print_summary
