import csv
import re
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import replay_set  # noqa: E402

FIX = Path(__file__).resolve().parent / "fixtures"
PATCH = Path(__file__).resolve().parent.parent / "r1_system_prompt_patch.md"
REPO = Path(__file__).resolve().parents[3]
PROMPT_BUILDER = REPO / "backend/src/main/java/com/gregochr/goldenhour/service/evaluation/PromptBuilder.java"


def production_inland_prompt():
    """Reconstruct PromptBuilder.SYSTEM_PROMPT from the Java source concatenation (no JVM needed)."""
    src = PROMPT_BUILDER.read_text(encoding="utf-8")
    i = src.index("static final String SYSTEM_PROMPT =")
    j = src.index(";\n", i)
    out = ""
    for p in re.findall(r'"((?:[^"\\]|\\.)*)"', src[i:j]):
        p = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m.group(1), 16)), p)
        out += p.replace("\\n", "\n").replace('\\"', '"').replace("\\'", "'").replace("\\\\", "\\")
    return out


def run_select(extra=()):
    out = Path(tempfile.mkdtemp())
    rc = replay_set.main(["select", "--prompts", str(FIX / "prompts.csv"),
                          "--forecasts", str(FIX / "forecasts.csv"), "--out", str(out), *extra])
    assert rc == 0
    return out


def read(path):
    with open(path, newline="") as f:
        return list(csv.DictReader(f))


class SelectTest(unittest.TestCase):
    def test_forecasts_are_required(self):
        with self.assertRaises(SystemExit):
            replay_set.main(["select", "--prompts", str(FIX / "prompts.csv"), "--out", "/tmp/x"])

    def test_latest_run_before_event_only(self):
        out = run_select()
        names = {p.name for p in out.glob("*.txt")}
        self.assertIn("3.coastal.txt", names)  # the only go: Bravo, tide types HIGH
        self.assertNotIn("1.inland.txt", names)  # superseded by run 2
        self.assertNotIn("11.inland.txt", names)  # after the event, never eligible
        self.assertEqual(len(names), 2)  # one go + an equal sample of one 3-star

    def test_triaged_or_unrated_latest_run_is_not_a_go(self):
        out = run_select()
        everything = {p.name for p in out.rglob("*.txt")}
        # Hotel's 4 at 10:00 is superseded by a triaged 15:00 run; Golf is unrated; Foxtrot ABANDONED;
        # India's latest has no stored prompt.
        for eid in ("12", "13", "10", "9", "14"):
            self.assertFalse({f"{eid}.inland.txt", f"{eid}.coastal.txt"} & everything, eid)

    def test_selection_uses_sky_rating_when_present(self):
        # Charlie: combined rating 4 but sky_rating 3 -> a 3-star candidate, not a go.
        manifest = read(run_select(["--seed", "3"]) / "manifest.csv")
        self.assertEqual([m["evaluation_id"] for m in manifest if m["group"] == "go"], ["3"])
        gos, sample, _, counts = replay_set.select(read(FIX / "prompts.csv"), read(FIX / "forecasts.csv"), 1)
        self.assertEqual(counts["three_star_candidates"], 4)  # Alpha, Charlie, Delta, Echo
        self.assertEqual(replay_set.basis_of({"rating": "4", "sky_rating": "3"}), ("sky_rating", 3))
        self.assertEqual(replay_set.basis_of({"rating": "3", "sky_rating": ""}), ("rating", 3))

    def test_manifest_columns_and_values(self):
        manifest = read(run_select() / "manifest.csv")
        for col in ("evaluation_id", "label", "location", "date", "event", "model", "stored_rating",
                    "stored_sky_rating", "basis", "coastal", "training"):
            self.assertIn(col, manifest[0])
        bravo = next(m for m in manifest if m["evaluation_id"] == "3")
        self.assertEqual((bravo["location"], bravo["basis"], bravo["coastal"], bravo["training"]),
                         ("Bravo", "sky_rating", "1", "0"))

    def test_training_cases_pinned_separated_and_not_counted(self):
        out = run_select()
        self.assertEqual(sorted(p.name for p in (out / "training").glob("*.txt")),
                         ["101110.inland.txt", "86677.coastal.txt"])  # the 14:22 run, not the later 16:00 one
        self.assertFalse((out / "101110.inland.txt").exists())
        training = [m for m in read(out / "manifest.csv") if m["training"] == "1"]
        self.assertEqual(len(training), 2)

    def test_sample_is_seeded(self):
        a = sorted(p.name for p in run_select(["--seed", "1"]).glob("*.txt"))
        b = sorted(p.name for p in run_select(["--seed", "1"]).glob("*.txt"))
        self.assertEqual(a, b)

    def test_message_verbatim_including_quotes_and_newline(self):
        self.assertEqual((run_select() / "3.coastal.txt").read_text(), 'msg3, "quoted"')

    def test_coastal_override_file(self):
        d = Path(tempfile.mkdtemp())
        (d / "ids.txt").write_text("3\n")
        out = run_select(["--coastal-ids", str(d / "ids.txt")])
        self.assertTrue((out / "3.coastal.txt").exists())

    def test_old_export_without_tide_column_is_refused(self):
        d = Path(tempfile.mkdtemp())
        rows = read(FIX / "forecasts.csv")
        with open(d / "f.csv", "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=[k for k in rows[0] if k != "loc_tide_types"])
            w.writeheader()
            w.writerows({k: v for k, v in r.items() if k != "loc_tide_types"} for r in rows)
        rc = replay_set.main(["select", "--prompts", str(FIX / "prompts.csv"), "--forecasts",
                              str(d / "f.csv"), "--out", str(d / "o")])
        self.assertEqual(rc, 2)


class CoastalTest(unittest.TestCase):
    def test_rules(self):
        self.assertTrue(replay_set.is_coastal_prompt({"loc_tide_types": "HIGH"}, "m"))
        self.assertTrue(replay_set.is_coastal_prompt({"loc_tide_types": "HIGH,LOW"}, "m"))
        self.assertTrue(replay_set.is_coastal_prompt(None, "x STORM SURGE FORECAST"))
        self.assertFalse(replay_set.is_coastal_prompt({"loc_tide_types": ""}, "m"))
        self.assertFalse(replay_set.is_coastal_prompt({"loc_tide_types": None}, "m"))
        self.assertFalse(replay_set.is_coastal_prompt(None, "m"))


class CostTest(unittest.TestCase):
    def test_estimate(self):
        total, per = replay_set.estimate_cost(100, 3, ["SONNET_55"], 3500, 400)
        self.assertAlmostEqual(total, 100 * 3 * (3500 * 2.0 + 400 * 10.0) / 1e6)
        self.assertEqual(per[0][0], "SONNET_55")

    def test_select_prints_a_range_and_caveats(self):
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            run_select()
        text = buf.getvalue()
        self.assertIn(" to $", text)
        self.assertIn("Thinking tokens", text)
        self.assertIn("caching", text)


class PatchTest(unittest.TestCase):
    def test_patch_parses_six_replacements(self):
        self.assertEqual([k for k, _, _ in replay_set.parse_patch(PATCH.read_text())],
                         ["1", "2", "3", "4", "5", "6"])

    def test_apply_exact_once(self):
        self.assertEqual(replay_set.apply_patch("x AAA y", [("1", "AAA", "BBB")]), "x BBB y")

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

    def test_every_old_text_occurs_once_in_the_production_prompt(self):
        prompt = production_inland_prompt()
        self.assertGreater(len(prompt), 10000)
        for key, old, _ in replay_set.parse_patch(PATCH.read_text(encoding="utf-8")):
            self.assertEqual(prompt.count(old), 1, f"replacement {key}")

    def test_build_r1_against_production_prompt_is_self_consistent(self):
        d = Path(tempfile.mkdtemp())
        (d / "sys.txt").write_text(production_inland_prompt(), encoding="utf-8")
        rc = replay_set.main(["build-r1", "--system-prompt", str(d / "sys.txt"),
                              "--patch", str(PATCH), "--out", str(d / "o.txt")])
        self.assertEqual(rc, 0)
        out = (d / "o.txt").read_text(encoding="utf-8")
        # the contradicting old wordings are gone
        self.assertNotIn("the solar-side mid/high IS the canvas", out)
        self.assertNotIn("When solar low cloud <20%, diffuse/soft", out)
        self.assertNotIn("; mid/high cloud above clear horizon = ideal canvas", out)
        self.assertNotIn("light penetration and canvas availability. Key rules", out)
        # every rate-4 permission now requires near canvas
        self.assertIn("When solar low cloud <20% AND near canvas is present, diffuse/soft", out)
        self.assertIn("(solar low <20%) AND near canvas;", out)
        self.assertIn("RATE 4 (not 3, not 5) when there is NEAR CANVAS", out)
        self.assertIn("NEAR canvas (see the IDEAL scenario) is judged from the observer-point", out)
        # the cap is stated once in each of the two places it is introduced
        self.assertEqual(out.count("cap rating at 3 and fiery_sky at 55"), 2)
        # nothing else changed: unchanged rules survive verbatim
        for keep in ("Solar horizon low cloud >60% = light is BLOCKED",
                     "NEVER rate 5 when solar horizon mid cloud >80%.",
                     "Antisolar mid/high cloud 20-60% = ideal canvas"):
            self.assertIn(keep, out)
        # removing the replaced text from both sides leaves identical strings
        base = production_inland_prompt()
        for _, old, new in replay_set.parse_patch(PATCH.read_text(encoding="utf-8")):
            base = base.replace(old, new)
        self.assertEqual(base, out)


class CompareTest(unittest.TestCase):
    def setUp(self):
        self.out = run_select()
        self.manifest = read(self.out / "manifest.csv")
        self.three = next(m["evaluation_id"] for m in self.manifest if m["group"] == "three")
        self.d = Path(tempfile.mkdtemp())

    def write_runs(self, name, rows, system_prompt="production"):
        with open(self.d / name, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["label", "model", "attempt", "rating", "fiery", "golden", "failure", "summary",
                        "system_prompt"])
            for label, model, ratings in rows:
                for i, r in enumerate(ratings, 1):
                    w.writerow([label, model, i, r, "", "", "" if r != "" else "Boom", "", system_prompt])
        return str(self.d / name)

    def test_report(self):
        arm1 = self.write_runs("arm1.csv", [
            ("3", "SONNET_55", [3, 3, 4]),                 # a go that falls (median 3)
            (self.three, "SONNET_55", [4, 4, ""]),          # a 3-star that rises
            ("101110", "SONNET_55", [4, 4, 3]),             # Angel training row
            ("low-hauxley-1004", "SONNET_55", [3, 3, 3]),   # pinned prompt, not in the manifest
        ])
        slots = self.d / "slots.csv"
        slots.write_text("location_id,loc_name,target_date,event,p_low,p_far,p_canvas\n"
                         "11,Bravo,2026-10-01,SUNSET,1,0,\n")
        out = self.d / "part_d.md"
        rc = replay_set.main(["compare", "--manifest", str(self.out / "manifest.csv"),
                              "--runs", f"{arm1},first", "--slots", str(slots), "--out", str(out)])
        self.assertEqual(rc, 0)
        text = out.read_text()
        self.assertIn("## Arm first/SONNET_55 [production]", text)
        self.assertIn("gos replayed: 1; median below 4: **1**", text)
        self.assertIn("median at 4 or more: **1**", text)
        self.assertIn("| Bravo | 1 | 1 |", text)
        self.assertIn("canvas=?, far=0, low=1", text)
        self.assertIn("| 101110 |", text)
        self.assertIn("FAILS (an attempt fell below 4; must stay 4)", text)

    def test_arms_split_by_model_and_system_prompt(self):
        a = self.write_runs("a.csv", [("3", "SONNET_55", [4, 4, 4]), ("3", "HAIKU", [3, 3, 3])])
        b = self.write_runs("b.csv", [("3", "SONNET_55", [3, 3, 3])], system_prompt="r1.txt@sha256:abc")
        arms = replay_set.load_runs([a + ",a", b + ",b"])
        self.assertEqual(sorted(arms), ["a/HAIKU [production]", "a/SONNET_55 [production]",
                                        "b/SONNET_55 [r1.txt@sha256:abc]"])

    def test_premise_join_prefers_evaluation_id(self):
        p = self.d / "s.csv"
        p.write_text("evaluation_id,p_low\n3,1\n")
        by_id, by_slot = replay_set.load_premises(str(p))
        m = {"evaluation_id": "3", "location_id": "11", "location": "Bravo", "date": "d", "event": "e"}
        self.assertEqual(replay_set.premise_of(m, by_id, by_slot), {"p_low": "1"})
        self.assertEqual(replay_set.premise_of(dict(m, evaluation_id="99"), by_id, by_slot), None)

    def test_failed_calls_are_not_ratings(self):
        arms = replay_set.load_runs([self.write_runs("x.csv", [("3", "HAIKU", ["", ""])])])
        self.assertEqual(arms["x/HAIKU [production]"]["3"], [])


if __name__ == "__main__":
    unittest.main()


class HardStopTest(unittest.TestCase):
    def test_every_attempt_must_stay_at_four(self):
        self.assertIn("FAILS", replay_set._hard_stop("low-hauxley-1004", [4, 4, 3]))
        self.assertTrue(replay_set._hard_stop("Low Hauxley", [4, 5, 4]).startswith("ok"))
        self.assertIn("FAILS", replay_set._hard_stop("low-hauxley-1004", []))
        self.assertEqual(replay_set._hard_stop("Bravo", [1]), "")
