import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import replay_set  # noqa: E402

FIX = Path(__file__).resolve().parent / "fixtures"
PATCH = Path(__file__).resolve().parent.parent / "r1_system_prompt_patch.md"


class SelectTest(unittest.TestCase):
    def run_select(self, extra=()):
        out = Path(tempfile.mkdtemp())
        rc = replay_set.main(["select", "--prompts", str(FIX / "prompts.csv"),
                              "--forecasts", str(FIX / "forecasts.csv"), "--out", str(out), *extra])
        self.assertEqual(rc, 0)
        return out

    def test_latest_run_only_and_three_star_sample_matches_go_count(self):
        out = self.run_select()
        names = sorted(p.name for p in out.glob("*.txt"))
        # gos: 3 (Bravo 5). Alpha's latest is 3, so Alpha is a 3-star candidate, not a go.
        # 3-star candidates: 2 (Alpha), 4, 5, 6 -> sample size == go count == 1.
        gos = [n for n in names if n.startswith("3.")]
        self.assertEqual(gos, ["3.coastal.txt"])  # is_coastal_tidal = t
        self.assertEqual(len(names), 2)
        self.assertNotIn("1.inland.txt", names)  # superseded by run 2

    def test_training_cases_separated_and_not_counted(self):
        out = self.run_select()
        self.assertEqual(sorted(p.name for p in (out / "training").glob("*.txt")),
                         ["7.inland.txt", "8.inland.txt"])
        self.assertFalse((out / "7.inland.txt").exists())

    def test_abandoned_and_unrated_rows_excluded(self):
        out = self.run_select()
        everything = {p.name for p in out.rglob("*.txt")}
        self.assertFalse({"9.inland.txt", "10.inland.txt"} & everything)

    def test_sample_is_seeded(self):
        a = sorted(p.name for p in self.run_select(["--seed", "1"]).glob("*.txt"))
        b = sorted(p.name for p in self.run_select(["--seed", "1"]).glob("*.txt"))
        self.assertEqual(a, b)

    def test_message_verbatim_including_quotes_and_newline(self):
        out = self.run_select()
        self.assertEqual((out / "3.coastal.txt").read_text(), 'msg3, "quoted"')


class CoastalTest(unittest.TestCase):
    def test_rules(self):
        self.assertTrue(replay_set.is_coastal_prompt({"loc_types": "SEASCAPE", "loc_is_coastal_tidal": "f"}, "m"))
        self.assertTrue(replay_set.is_coastal_prompt({"loc_types": "LANDSCAPE", "loc_is_coastal_tidal": "t"}, "m"))
        self.assertTrue(replay_set.is_coastal_prompt(None, "x STORM SURGE FORECAST"))
        self.assertFalse(replay_set.is_coastal_prompt({"loc_types": "LANDSCAPE", "loc_is_coastal_tidal": "f"}, "m"))
        self.assertFalse(replay_set.is_coastal_prompt(None, "m"))


class CostTest(unittest.TestCase):
    def test_estimate(self):
        total, per = replay_set.estimate_cost(100, 3, ["SONNET_55"], 3500, 400)
        self.assertAlmostEqual(total, 100 * 3 * (3500 * 2.0 + 400 * 10.0) / 1e6)
        self.assertEqual(per[0][0], "SONNET_55")


class PatchTest(unittest.TestCase):
    def test_patch_parses_three_replacements(self):
        self.assertEqual([k for k, _, _ in replay_set.parse_patch(PATCH.read_text())], ["1", "2", "3"])

    def test_apply_exact_once(self):
        reps = [("1", "AAA", "BBB")]
        self.assertEqual(replay_set.apply_patch("x AAA y", reps), "x BBB y")

    def test_fails_loudly_when_missing_or_ambiguous(self):
        with self.assertRaises(ValueError):
            replay_set.apply_patch("nothing", [("1", "AAA", "B")])
        with self.assertRaises(ValueError):
            replay_set.apply_patch("AAA AAA", [("1", "AAA", "B")])

    def test_build_r1_exit_code_one_on_missing(self):
        d = Path(tempfile.mkdtemp())
        (d / "sys.txt").write_text("no ideal clause here")
        rc = replay_set.main(["build-r1", "--system-prompt", str(d / "sys.txt"),
                              "--patch", str(PATCH), "--out", str(d / "o.txt")])
        self.assertEqual(rc, 1)
        self.assertFalse((d / "o.txt").exists())

    def test_build_r1_against_real_prompt_if_dumped(self):
        real = Path("/tmp/sp/system-inland.txt")
        if not real.exists():
            self.skipTest("run PromptReplayHarnessTest#dumpSystemPrompts first")
        d = Path(tempfile.mkdtemp())
        rc = replay_set.main(["build-r1", "--system-prompt", str(real),
                              "--patch", str(PATCH), "--out", str(d / "o.txt")])
        self.assertEqual(rc, 0)
        out = (d / "o.txt").read_text()
        self.assertIn("AND near canvas;", out)
        self.assertNotIn("the solar-side mid/high IS the canvas", out)
        self.assertIn("rating 4-5.", out)


if __name__ == "__main__":
    unittest.main()
