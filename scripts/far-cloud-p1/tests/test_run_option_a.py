"""Tests for run_option_a.sh against a stub backend/mvnw in a temporary copy of the repository layout.

No real Maven and no network: the stub prints a harness-shaped plan and refusal when REPLAY_CONFIRM_COST is
unset, and writes target/p0-replay/{runs.csv,summary.md,batches.json} when it is set.
"""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent.parent / "run_option_a.sh"
KEY = "sk-ant-TESTSENTINEL-0123456789"
ARMS = ["need-sonnet", "need-sonnet55", "r1-gos", "control-sonnet55", "training"]

STUB = r"""#!/bin/bash
printf '%s\n' "$*" >> "$STUB_LOG"
{ env | grep '^REPLAY_' | sort; echo "ARM=$(basename "${REPLAY_PROMPTS_DIR:-none}")"; echo '---'; } >> "$STUB_ENVLOG"
if [ -n "${STUB_LEAK_KEY:-}" ]; then echo "oops $ANTHROPIC_API_KEY"; fi
n="${STUB_N:-5}"
if [ -z "${REPLAY_CONFIRM_COST:-}" ]; then
  if [ -n "${STUB_NO_PLAN:-}" ]; then echo "[ERROR] COMPILATION ERROR"; exit 1; fi
  if [ -n "${STUB_PLAN_OK:-}" ]; then echo "TOTAL REQUESTS TO SUBMIT: $n (primers included)"; exit 0; fi
  echo "== Batch replay plan (Message Batches API, 50% of standard prices) =="
  echo "  SONNET_55     $((n - 1)) request(s); to submit: 1 batch of $((n - 1)) + 1 primer request(s); est. \$0.50 expected, \$${STUB_CEILING:-3.00} ceiling"
  echo "TOTAL REQUESTS TO SUBMIT: $n (primers included)"
  echo "ESTIMATED COST at batch prices: \$0.50 expected (400 output tokens each, system prompt read from cache), \$${STUB_CEILING:-3.00} ceiling (every reply at max_tokens, thinking included). Thinking tokens bill as output; the figures are estimates, not a quote."
  echo "[ERROR] java.lang.IllegalStateException: Refusing to submit $n request(s): set REPLAY_CONFIRM_COST=$n to confirm the plan printed above."
  exit 1
fi
if [ -n "${STUB_FAIL_FIRST:-}" ] && [ -z "${REPLAY_BATCH_RESUME:-}" ]; then echo "[ERROR] poll failed"; exit 1; fi
if [ -n "${STUB_FAIL_ALWAYS:-}" ]; then echo "[ERROR] poll failed again"; exit 1; fi
if [ "$REPLAY_CONFIRM_COST" != "$n" ]; then echo "[ERROR] wrong confirmation"; exit 1; fi
mkdir -p target/p0-replay
echo "label,model" > target/p0-replay/runs.csv
{
  printf 'Mode: batch\n\nTokens: 1,000 input, 200 output, 0 cache read, 0 cache write; about $1.00 at batch prices\n\n'
  echo '| Prompt | Model | 1 | 2 | 3 | 4 | 5 | Failed | Mean | Mean fiery | Mean golden |'
  echo '|---|---|---|---|---|---|---|---|---|---|---|'
  echo "| 1 | SONNET | 0 | 0 | 2 | 1 | 0 | ${STUB_FAILED:-0} | 3.3 | 1 | 1 |"
} > target/p0-replay/summary.md
echo '{"collected":true}' > target/p0-replay/batches.json
exit 0
"""


class RunOptionATest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="run_option_a_test."))
        self.addCleanup(shutil.rmtree, self.tmp, True)
        (self.tmp / "scripts" / "far-cloud-p1").mkdir(parents=True)
        self.script = self.tmp / "scripts" / "far-cloud-p1" / "run_option_a.sh"
        shutil.copy(SCRIPT, self.script)
        self.script.chmod(self.script.stat().st_mode | stat.S_IXUSR)
        (self.tmp / "backend").mkdir()
        mvnw = self.tmp / "backend" / "mvnw"
        mvnw.write_text(STUB)
        mvnw.chmod(0o755)
        self.home = self.tmp / "p1"
        for d in ARMS:
            (self.home / "p1-replay-arms" / d).mkdir(parents=True)
        (self.home / "p1-replay-arms" / "arms.json").write_text("{}")
        (self.home / "sp").mkdir()
        (self.home / "sp" / "system-r1.txt").write_text("r1")
        self.stub_log = self.tmp / "stub.log"
        self.env_log = self.tmp / "stub-env.log"

    def run_script(self, *args, stdin="", **extra):
        env = {k: v for k, v in os.environ.items() if not k.startswith(("REPLAY_", "STUB_"))}
        env.update({"P1_HOME": str(self.home), "ANTHROPIC_API_KEY": KEY, "STUB_LOG": str(self.stub_log),
                    "STUB_ENVLOG": str(self.env_log)})
        env.update(extra)
        return subprocess.run([str(self.script), *args], input=stdin, capture_output=True, text=True, env=env,
                              timeout=120)

    def invocations(self):
        """One dict per stub call: the REPLAY_* settings it saw, plus ARM."""
        if not self.env_log.exists():
            return []
        calls = []
        for block in self.env_log.read_text().split("---\n"):
            if block.strip():
                calls.append(dict(line.split("=", 1) for line in block.strip().splitlines()))
        return calls

    def all_text(self):
        parts = []
        for p in self.tmp.rglob("*"):
            if p.is_file() and p != self.script and p.name != "mvnw":
                parts.append(p.read_text(errors="replace"))
        return "\n".join(parts)

    def test_all_arms_with_yes_use_the_right_settings(self):
        r = self.run_script("--yes", REPLAY_THREADS="9", REPLAY_SYSTEM_PROMPT_FILE="/stale", STUB_N="9")
        self.assertEqual(r.returncode, 0, r.stderr)
        calls = self.invocations()
        self.assertEqual(len(calls), 10)  # a plan run and a confirmed run per arm
        plan, confirmed = calls[0::2], calls[1::2]
        self.assertEqual([c["ARM"] for c in plan], ARMS)
        for c in calls:
            self.assertEqual(c["REPLAY_BATCH"], "true")
            self.assertNotIn("REPLAY_THREADS", c)  # nothing from the caller's environment leaks
        for c in plan:
            self.assertNotIn("REPLAY_CONFIRM_COST", c)
        for c in confirmed:
            self.assertEqual(c["REPLAY_CONFIRM_COST"], "9")  # the N parsed from the plan
            self.assertNotIn("REPLAY_BATCH_RESUME", c)
        by_arm = {c["ARM"]: c for c in confirmed}
        self.assertEqual(by_arm["need-sonnet"]["REPLAY_MODELS"], "SONNET")
        self.assertEqual(by_arm["need-sonnet55"]["REPLAY_MODELS"], "SONNET_55")
        self.assertTrue(by_arm["r1-gos"]["REPLAY_SYSTEM_PROMPT_FILE"].endswith("sp/system-r1.txt"))
        self.assertEqual(by_arm["training"]["REPLAY_N"], "20")
        self.assertTrue(by_arm["training"]["REPLAY_SYSTEM_PROMPT_FILE"].endswith("sp/system-r1.txt"))
        for arm in ("need-sonnet", "need-sonnet55", "control-sonnet55"):
            self.assertNotIn("REPLAY_SYSTEM_PROMPT_FILE", by_arm[arm])  # production prompt: the variable is unset
            self.assertEqual(by_arm[arm]["REPLAY_N"], "1")
        self.assertEqual(by_arm["control-sonnet55"]["REPLAY_MODELS"], "SONNET_55")
        for name in ("need-sonnet", "need-sonnet55", "r1-gos", "control-sonnet55", "training-r1"):
            d = self.home / "arms" / name
            for f in ("runs.csv", "summary.md", "batches.json", "plan.log", "run.log"):
                self.assertTrue((d / f).is_file(), f"{name}/{f}")
        self.assertIn("1,000 input, 200 output", r.stdout)  # tokens surfaced in the summary
        self.assertIn("N=9", r.stdout)

    def test_max_usd_aborts_before_submitting(self):
        r = self.run_script("--yes", "--max-usd", "30", "--only", "r1-gos", STUB_CEILING="40.00")
        self.assertEqual(r.returncode, 3)
        self.assertEqual(len(self.invocations()), 1)  # the plan run only
        self.assertFalse((self.home / "arms" / "r1-gos" / "runs.csv").exists())
        self.assertIn("exceeds --max-usd 30", r.stderr)

    def test_max_usd_below_ceiling_proceeds(self):
        r = self.run_script("--yes", "--max-usd", "30", "--only", "r1-gos", STUB_CEILING="29.99")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertTrue((self.home / "arms" / "r1-gos" / "runs.csv").is_file())

    def test_skips_an_arm_whose_runs_csv_exists(self):
        d = self.home / "arms" / "need-sonnet"
        d.mkdir(parents=True)
        (d / "runs.csv").write_text("x")
        r = self.run_script("--yes", "--only", "need-sonnet")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.invocations(), [])
        self.assertIn("skipped", r.stdout)
        self.assertEqual((d / "runs.csv").read_text(), "x")

    def test_declining_the_prompt_skips_the_arm(self):
        r = self.run_script("--only", "r1-gos", stdin="n\n")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(len(self.invocations()), 1)
        self.assertIn("Submit 5 requests for r1-gos? [y/N]", r.stderr)
        self.assertFalse((self.home / "arms" / "r1-gos" / "runs.csv").exists())

    def test_answering_y_submits(self):
        r = self.run_script("--only", "r1-gos", stdin="y\n")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(len(self.invocations()), 2)
        self.assertTrue((self.home / "arms" / "r1-gos" / "runs.csv").is_file())

    def test_unparseable_plan_aborts_the_whole_script(self):
        r = self.run_script("--yes", STUB_NO_PLAN="1")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(len(self.invocations()), 1)  # it stopped at the first arm
        self.assertIn("could not parse", r.stderr)

    def test_failed_run_is_retried_once_with_resume(self):
        r = self.run_script("--yes", "--only", "r1-gos", STUB_FAIL_FIRST="1")
        self.assertEqual(r.returncode, 0, r.stderr)
        calls = self.invocations()
        self.assertEqual(len(calls), 3)
        self.assertNotIn("REPLAY_BATCH_RESUME", calls[1])
        self.assertEqual(calls[2]["REPLAY_BATCH_RESUME"], "true")
        self.assertEqual(calls[2]["REPLAY_CONFIRM_COST"], "5")
        self.assertTrue((self.home / "arms" / "r1-gos" / "run-resume.log").is_file())

    def test_the_key_is_never_written_to_a_log_or_printed(self):
        r = self.run_script("--yes", "--only", "need-sonnet", STUB_LEAK_KEY="1")
        self.assertFalse((self.home / "arms" / "need-sonnet" / "run.log").exists())  # the confirmed run's log too
        self.assertNotIn(KEY, r.stdout + r.stderr)
        self.assertNotIn(KEY, self.all_text())
        self.assertIn("contained the API key", r.stderr)  # the leaking logs were deleted, and it said so

    def test_maven_is_called_with_the_test_selector_and_profile(self):
        self.run_script("--yes", "--only", "r1-gos")
        lines = self.stub_log.read_text().splitlines()
        self.assertEqual(len(lines), 2)
        for line in lines:
            args = line.split()
            self.assertEqual(args[0], "test")
            self.assertIn("-Pprompt-regression", args)
            self.assertIn("-Dtest=PromptReplayHarnessTest#replayStoredPrompts", args)
            self.assertIn("-Djava.version=23", args)

    def test_blank_jdk_flags_drop_the_jdk_23_flags(self):
        r = self.run_script("--yes", "--only", "r1-gos", MVN_JDK_FLAGS="")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertNotIn("-Djava.version=23", self.stub_log.read_text())

    def test_a_caller_exported_confirmation_never_reaches_a_plan_run(self):
        r = self.run_script("--yes", "--only", "r1-gos", REPLAY_CONFIRM_COST="5", REPLAY_BATCH_RESUME="true")
        self.assertEqual(r.returncode, 0, r.stderr)
        plan, confirmed = self.invocations()
        self.assertNotIn("REPLAY_CONFIRM_COST", plan)
        self.assertNotIn("REPLAY_BATCH_RESUME", plan)
        self.assertNotIn("REPLAY_BATCH_RESUME", confirmed)

    def test_a_run_that_fails_twice_stops_the_script_after_one_retry(self):
        r = self.run_script("--yes", STUB_FAIL_ALWAYS="1")
        self.assertEqual(r.returncode, 1)
        self.assertEqual(len(self.invocations()), 3)  # plan, confirmed, one resume; no second arm
        self.assertFalse((self.home / "arms" / "need-sonnet" / "runs.csv").exists())
        self.assertIn("failed twice", r.stderr)

    def test_a_plan_run_that_exits_zero_aborts(self):
        r = self.run_script("--yes", STUB_PLAN_OK="1")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(len(self.invocations()), 1)
        self.assertIn("instead of refusing", r.stderr)

    def test_failed_attempts_are_flagged_in_the_summary(self):
        r = self.run_script("--yes", "--only", "r1-gos", STUB_FAILED="2")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("submitted, 2/5 FAILED", r.stdout)
        self.assertTrue((self.home / "arms" / "r1-gos" / "runs.csv").is_file())

    def test_results_left_in_the_harness_output_are_moved_aside_not_adopted(self):
        old = self.tmp / "backend" / "target" / "p0-replay"
        old.mkdir(parents=True)
        (old / "batches.json").write_text("OLD")
        (old / "runs.csv").write_text("OLD")
        r = self.run_script("--yes", "--only", "r1-gos")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual((self.home / "arms" / "r1-gos" / "batches.json").read_text().strip(), '{"collected":true}')
        kept = list(old.glob("previous-*/batches.json"))
        self.assertEqual([p.read_text() for p in kept], ["OLD"])

    def test_a_missing_p1_home_refuses_to_start(self):
        r = self.run_script("--yes", P1_HOME=str(self.tmp / "nowhere"))
        self.assertEqual(r.returncode, 2)
        self.assertIn("P1_HOME is not a directory", r.stderr)

    def test_unset_key_refuses_to_start(self):
        r = self.run_script("--yes", ANTHROPIC_API_KEY="")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(self.invocations(), [])
        self.assertIn("ANTHROPIC_API_KEY is not set", r.stderr)

    def test_missing_inputs_refuse_to_start(self):
        (self.home / "sp" / "system-r1.txt").unlink()
        self.assertEqual(self.run_script("--yes").returncode, 2)
        (self.home / "sp" / "system-r1.txt").write_text("r1")
        (self.home / "p1-replay-arms" / "arms.json").unlink()
        r = self.run_script("--yes")
        self.assertEqual(r.returncode, 2)
        self.assertIn("arms.json", r.stderr)
        self.assertEqual(self.invocations(), [])

    def test_a_half_copied_arm_directory_is_refused_before_spending(self):
        d = self.home / "arms" / "r1-gos"
        d.mkdir(parents=True)
        (d / "summary.md").write_text("old")
        r = self.run_script("--yes", "--only", "r1-gos")
        self.assertEqual(r.returncode, 2)
        self.assertEqual(self.invocations(), [])
        self.assertEqual((d / "summary.md").read_text(), "old")

    def test_dry_run_runs_no_maven_and_writes_nothing_under_p1_home(self):
        r = self.run_script("--dry-run", "--yes", "--max-usd", "30")
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(self.invocations(), [])
        self.assertFalse((self.home / "arms").exists())
        self.assertIn("N=7", r.stdout)  # the fake plan went through the real parser
        self.assertEqual(r.stdout.count("dry-run (would submit)"), 5)

    def test_unknown_arm_and_bad_max_usd_are_usage_errors(self):
        self.assertEqual(self.run_script("--only", "nope").returncode, 2)
        self.assertEqual(self.run_script("--max-usd", "lots").returncode, 2)
        self.assertEqual(self.invocations(), [])


if __name__ == "__main__":
    unittest.main()
