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


def make_set(root):
    """A tiny select-shaped set: 3 gos, 6 3-stars, 1 training row, with the stored model mixed."""
    rows = []
    spec = [("g1", "go", "SONNET_55"), ("g2", "go", "HAIKU"), ("g3", "go", "SONNET_55"),
            ("t1", "three", "SONNET_55"), ("t2", "three", "SONNET_55"), ("t3", "three", "SONNET_55"),
            ("t4", "three", "SONNET_55"), ("t5", "three", "HAIKU"), ("t6", "three", "HAIKU"),
            ("x1", "three", "")]
    for label, group, model in spec:
        rating = "4" if group == "go" else "3"
        (root / f"{label}.inland.txt").write_text(f"message {label}")
        rows.append({"evaluation_id": str(len(rows) + 1), "label": label, "location": f"Loc {label}",
                     "location_id": "1", "date": "2026-10-01", "event": "SUNSET", "model": model,
                     "stored_rating": rating, "stored_sky_rating": rating, "basis": "sky_rating",
                     "basis_rating": rating, "coastal": "0", "training": "0", "group": group,
                     "file": f"{label}.inland.txt"})
    (root / "training").mkdir()
    (root / "training" / "tr1.inland.txt").write_text("message tr1")
    rows.append(dict(rows[0], evaluation_id="99", label="tr1", location="Training", model="SONNET_55",
                     training="1", group="training:Angel", file="training/tr1.inland.txt"))
    with open(root / "manifest.csv", "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=replay_set.MANIFEST_FIELDS)
        w.writeheader()
        w.writerows(rows)
    return rows


class SplitTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.set = self.root / "set"
        self.set.mkdir()
        self.rows = make_set(self.set)
        self.out = self.root / "arms"

    def split(self, *extra):
        return replay_set.main(["split", "--set", str(self.set), "--out", str(self.out), *extra])

    def labels(self, name):
        return sorted(m["label"] for m in read(self.out / name / "manifest.csv"))

    def test_split_is_by_stored_model_and_group(self):
        self.assertEqual(self.split(), 0)
        # SONNET_55 rated these: their missing arm is SONNET; "" is unknown, so it needs both
        self.assertEqual(self.labels("need-sonnet"), ["g1", "g3", "t1", "t2", "t3", "t4", "x1"])
        # HAIKU rated these: their missing arm is SONNET_55 (HAIKU is not a D-2 arm)
        self.assertEqual(self.labels("need-sonnet55"), ["g2", "t5", "t6", "x1"])
        self.assertEqual(self.labels("r1-gos"), ["g1", "g2", "g3"])  # go group only, whatever rated it
        self.assertEqual(self.labels("training"), ["tr1"])
        self.assertEqual(sorted(p.name for p in (self.out / "r1-gos").glob("*.txt")),
                         ["g1.inland.txt", "g2.inland.txt", "g3.inland.txt"])

    def test_files_are_copied_not_moved(self):
        self.assertEqual(self.split(), 0)
        for m in self.rows:
            original = self.set / m["file"]
            self.assertTrue(original.is_file(), m["label"])
        copy = self.out / "need-sonnet" / "t1.inland.txt"
        self.assertEqual(copy.read_text(), (self.set / "t1.inland.txt").read_text())
        self.assertEqual((self.out / "training" / "tr1.inland.txt").read_text(), "message tr1")
        self.assertEqual(read(self.set / "manifest.csv"), [dict(r) for r in self.rows])

    def test_per_directory_manifests_are_strict_subsets_with_local_paths(self):
        self.assertEqual(self.split(), 0)
        full = {m["label"]: m for m in read(self.set / "manifest.csv")}
        for name in ("need-sonnet", "need-sonnet55", "r1-gos", "training"):
            sub = read(self.out / name / "manifest.csv")
            self.assertLess(len(sub), len(full), name)
            for m in sub:
                self.assertEqual({k: v for k, v in m.items() if k != "file"},
                                 {k: v for k, v in full[m["label"]].items() if k != "file"})
                self.assertEqual(m["file"], Path(full[m["label"]]["file"]).name)
                self.assertTrue((self.out / name / m["file"]).is_file())

    def test_three_sample_is_seeded_and_only_touches_the_three_group(self):
        self.assertEqual(self.split("--three-sample", "3", "--seed", "7"), 0)
        first = {n: self.labels(n) for n in ("need-sonnet", "need-sonnet55", "r1-gos", "training")}
        three = {"t1", "t2", "t3", "t4", "t5", "t6", "x1"}
        kept = (set(first["need-sonnet"]) | set(first["need-sonnet55"])) & three
        self.assertEqual(len(kept), 3)
        for n in ("need-sonnet", "need-sonnet55"):  # the same sampled prompts in both directories
            self.assertLessEqual(set(first[n]) & three, kept)
        self.assertEqual(sorted(set(first["need-sonnet"]) - three), ["g1", "g3"])  # gos untouched
        self.assertEqual(first["r1-gos"], ["g1", "g2", "g3"])
        self.assertEqual(first["training"], ["tr1"])
        # same seed, same draw; the draw is also what the pure function returns
        again, _ = replay_set.split_manifest(self.rows, 3, 7)
        self.assertEqual(sorted(m["label"] for m in again["need-sonnet"]), first["need-sonnet"])
        dirs, info = replay_set.split_manifest(self.rows, 99, 7)  # larger than the group: keeps all
        self.assertEqual(info["three_kept"], 7)
        none, info0 = replay_set.split_manifest(self.rows, 0, 7)
        self.assertEqual(info0["three_kept"], 0)
        self.assertEqual({m["group"] for m in none["need-sonnet"]}, {"go"})

    def test_no_prompt_is_replayed_on_the_model_that_rated_it(self):
        for n in (None, 0, 2, 5):
            dirs, _ = replay_set.split_manifest(self.rows, n, 1)
            self.assertEqual(replay_set.self_replays(dirs), [])
            for m in dirs["need-sonnet"]:
                self.assertNotEqual(m["model"], "SONNET")
            for m in dirs["need-sonnet55"]:
                self.assertNotEqual(m["model"], "SONNET_55")
        for stored in ("SONNET_55", "SONNET", "HAIKU", "", "OPUS"):
            self.assertNotIn(stored, replay_set.models_needing_replay(stored))
        # and the guard itself would catch one
        bad = {"need-sonnet": [dict(self.rows[0], model="SONNET")], "need-sonnet55": [], "r1-gos": [],
               "training": []}
        self.assertEqual(replay_set.self_replays(bad), [("need-sonnet", "1")])

    def test_arms_json_maps_directories_to_models(self):
        import json
        self.assertEqual(self.split(), 0)
        meta = json.loads((self.out / "arms.json").read_text())
        self.assertEqual((meta["dirs"]["need-sonnet"]["model"], meta["dirs"]["need-sonnet"]["system_prompt"]),
                         ("SONNET", "production"))
        self.assertEqual((meta["dirs"]["need-sonnet55"]["model"], meta["dirs"]["r1-gos"]["model"]),
                         ("SONNET_55", "SONNET_55"))
        self.assertEqual(meta["dirs"]["r1-gos"]["system_prompt"], "override")

    def test_prints_calls_and_batch_cost_at_half_standard_price(self):
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.split()
        text = buf.getvalue()
        self.assertIn("need-sonnet: 7 prompts", text)
        self.assertIn("BATCH prices", text)
        self.assertIn("ASSUMED", text)
        want = 0.5 * 7 * (3500 * 3.0 + 400 * 15.0) / 1e6
        self.assertIn(f"${want:,.2f} to ${want * 1.5:,.2f}", text)

    def test_refuses_a_non_empty_target_and_a_missing_prompt_file(self):
        (self.out / "need-sonnet").mkdir(parents=True)
        (self.out / "need-sonnet" / "stale.inland.txt").write_text("old")
        self.assertEqual(self.split(), 2)  # it would be replayed, and billed
        self.assertEqual((self.out / "need-sonnet" / "stale.inland.txt").read_text(), "old")
        self.assertFalse((self.out / "arms.json").exists())
        shutil_out = self.root / "arms2"
        (self.set / "g1.inland.txt").unlink()
        self.assertEqual(replay_set.main(["split", "--set", str(self.set), "--out", str(shutil_out)]), 2)
        self.assertFalse(shutil_out.exists())


class StoredArmCompareTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "set").mkdir()
        self.rows = make_set(self.root / "set")
        self.manifest = read(self.root / "set" / "manifest.csv")

    def runs(self, name, rows, system_prompt="production"):
        path = self.root / name
        with open(path, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["label", "model", "attempt", "rating", "fiery", "golden", "failure", "summary",
                        "system_prompt"])
            for label, model, ratings in rows:
                for i, r in enumerate(ratings, 1):
                    w.writerow([label, model, i, r, "", "", "", "", system_prompt])
        return str(path)

    def trimmed(self):
        return [
            self.runs("need-sonnet.csv", [("g1", "SONNET", [4]), ("g3", "SONNET", [4]), ("t1", "SONNET", [4]),
                                          ("t2", "SONNET", [3]), ("t3", "SONNET", [4]), ("t4", "SONNET", [3])]),
            self.runs("need-sonnet55.csv", [("g2", "SONNET_55", [4]), ("t5", "SONNET_55", [4]),
                                            ("t6", "SONNET_55", [3])]),
            self.runs("r1.csv", [("g1", "SONNET_55", [3]), ("g2", "SONNET_55", [4]), ("g3", "SONNET_55", [4])],
                      system_prompt="system-r1.txt@sha256:abc"),
        ]

    def report(self, files, labels, extra=()):
        arms = replay_set.load_runs([f"{f},{l}" for f, l in zip(files, labels)])
        return replay_set.compare(self.manifest, arms), arms

    def test_stored_rating_is_a_distinct_arm_and_never_a_replay(self):
        text, _ = self.report(self.trimmed(), ["need-sonnet", "need-sonnet55", "r1-gos"])
        self.assertIn("## Arm stored/SONNET_55 [production]: STORED RATINGS, NOT A REPLAY", text)
        self.assertIn("## Arm need-sonnet/SONNET [production]", text)
        self.assertNotIn("## Arm stored/SONNET [production]", text)  # nothing of SONNET's is stored here
        stored = text.split("## Arm stored/SONNET_55 [production]")[1].split("## Arm")[0]
        self.assertNotIn("median below 4", stored)  # no fall/rise figures for a stored arm

    def test_d2_with_a_stored_side_is_one_sided_and_gives_no_gap(self):
        text, _ = self.report(self.trimmed(), ["need-sonnet", "need-sonnet55", "r1-gos"])
        # t1..t4: stored SONNET_55 3 each (0/4); replayed SONNET 4,3,4,3 (2/4). t5, t6 have no SONNET rating.
        line = next(l for l in text.splitlines() if l.startswith("- **D-2"))
        self.assertIn("ONE-SIDED (not a test of D-2.1), 4 prompts", line)
        self.assertIn("SONNET_55 0/4 (0.0%) [4 stored]", line)
        self.assertIn("SONNET 2/4 (50.0%) [4 replayed]", line)
        self.assertNotIn("minus", line)  # no gap is printed, so none can be read against +8 pp
        self.assertNotIn("+0.0", line)
        self.assertIn("2 3-star prompts have a SONNET_55 rating and no SONNET one", text)

    def test_d2_gap_is_printed_only_when_both_sides_are_replayed(self):
        both = self.runs("b.csv", [("t1", "SONNET_55", [4]), ("t1", "SONNET", [3]), ("t2", "SONNET_55", [4]),
                                   ("t2", "SONNET", [4]), ("t3", "SONNET_55", [3]), ("t3", "SONNET", [4])])
        text, _ = self.report([both], ["b"])
        line = next(l for l in text.splitlines() if l.startswith("- **D-2 (4-star"))
        self.assertIn("paired on 3 prompts", line)
        self.assertIn("SONNET_55 2/3 (66.7%) [3 replayed] vs SONNET 2/3 (66.7%) [3 replayed]", line)
        self.assertIn("+0.0 pp", line)
        mixed = self.runs("m.csv", [("t1", "SONNET_55", [4]), ("t1", "SONNET", [3]), ("t4", "SONNET", [4])])
        text, _ = self.report([mixed], ["m"])  # t4: SONNET_55 stored 3 vs SONNET replayed 4
        self.assertIn("paired on 1 prompts", text)
        self.assertIn("ONE-SIDED (not a test of D-2.1), 1 prompts", text)

    def test_d1_part2_line_counts_gos_the_r1_arm_takes_below_four(self):
        text, _ = self.report(self.trimmed(), ["need-sonnet", "need-sonnet55", "r1-gos"])
        line = next(l for l in text.splitlines() if l.startswith("- **D-1 part 2"))
        self.assertIn("1/3 go prompts taken below 4 stars (33.3%; cap 15%: over)", line)
        self.assertIn("r1-gos/SONNET_55 [system-r1.txt@sha256:abc]", line)
        self.assertIn("R1 side: replayed", line)
        self.assertIn("stored sky rating", line)

    def test_a_full_replay_has_no_stored_arm_and_keeps_its_report(self):
        everything = self.runs("full.csv", [
            (m["label"], mdl, [4 if mdl == "SONNET_55" else 3])
            for m in self.manifest if m["training"] != "1" for mdl in ("SONNET_55", "SONNET", "HAIKU")])
        text, arms = self.report([everything], ["full"])
        self.assertEqual(replay_set.with_stored_arms(self.manifest, arms), arms)
        head = text.split("## Decision lines")[0]  # the report a full replay always produced
        self.assertNotIn("stored/", head)
        self.assertNotIn("STORED", head)
        self.assertEqual([l for l in head.splitlines() if l.startswith("## ")],
                         ["## Arm full/HAIKU [production]", "## Arm full/SONNET [production]",
                          "## Arm full/SONNET_55 [production]"])
        self.assertNotIn("stored/", text)
        self.assertNotIn("STORED", text)
        self.assertIn("## Arm full/SONNET_55 [production]", text)
        line = next(l for l in text.splitlines() if l.startswith("- **D-2"))
        self.assertIn("[7 replayed]", line)
        self.assertNotIn("stored", line)
        self.assertNotIn("ONE-SIDED", text)

    def test_replayed_rating_wins_over_a_stored_one(self):
        both = self.runs("both.csv", [("t1", "SONNET_55", [4]), ("t1", "SONNET", [4]), ("t2", "SONNET", [3])])
        _, arms = self.report([both], ["both"])
        view = replay_set.model_view(replay_set.with_stored_arms(self.manifest, arms), "SONNET_55")
        self.assertEqual(view["t1"], ([4], "replayed"))
        self.assertEqual(view["t2"], ([3], "stored"))  # not replayed on SONNET_55: the stored sample fills in

    def test_check_arms_refuses_runs_that_break_the_split(self):
        import json
        out = self.root / "arms"
        self.assertEqual(replay_set.main(["split", "--set", str(self.root / "set"), "--out", str(out)]), 0)
        meta = json.loads((out / "arms.json").read_text())
        ok = replay_set.load_runs([f"{f},{l}" for f, l in
                                   zip(self.trimmed(), ["need-sonnet", "need-sonnet55", "r1-gos"])])
        self.assertEqual(replay_set.check_arms(meta, self.manifest, ok), [])
        wrong_model = replay_set.load_runs(
            [self.runs("w.csv", [("t1", "SONNET_55", [3])]) + ",need-sonnet"])
        problems = replay_set.check_arms(meta, self.manifest, wrong_model)
        self.assertTrue(any("is for SONNET" in p for p in problems), problems)
        self.assertTrue(any("replayed on the model that rated them" in p for p in problems), problems)
        stray = replay_set.load_runs([self.runs("s.csv", [("t5", "SONNET", [3])]) + ",need-sonnet"])
        self.assertTrue(any("not in directory need-sonnet" in p for p in
                            replay_set.check_arms(meta, self.manifest, stray)))
        prod_on_r1 = replay_set.load_runs([self.runs("p.csv", [("g1", "SONNET_55", [4])]) + ",r1-gos"])
        self.assertTrue(any("expects the override system prompt" in p for p in
                            replay_set.check_arms(meta, self.manifest, prod_on_r1)))

    def test_cli_compare_with_arms_json_exit_codes(self):
        out = self.root / "arms"
        replay_set.main(["split", "--set", str(self.root / "set"), "--out", str(out)])
        files = self.trimmed()
        base = ["compare", "--manifest", str(self.root / "set" / "manifest.csv"), "--arms-json",
                str(out / "arms.json"), "--out", str(self.root / "r.md"), "--runs"]
        self.assertEqual(replay_set.main(base + [f"{files[0]},need-sonnet", f"{files[1]},need-sonnet55",
                                                 f"{files[2]},r1-gos"]), 0)
        self.assertEqual(replay_set.main(base + [f"{files[1]},need-sonnet"]), 2)
        self.assertEqual(replay_set.main(base[:-1] + ["--runs", f"{files[0]},stored"]), 2)

    def test_training_run_is_exempt_from_the_model_rules_and_adds_no_stored_arm(self):
        import json
        out = self.root / "arms"
        replay_set.main(["split", "--set", str(self.root / "set"), "--out", str(out)])
        meta = json.loads((out / "arms.json").read_text())
        training = self.runs("tr.csv", [("tr1", m, [4]) for m in ("SONNET_55", "SONNET", "HAIKU")])
        arms = replay_set.load_runs([training + ",training"])
        self.assertEqual(replay_set.check_arms(meta, self.manifest, arms), [])
        text = replay_set.compare(self.manifest, arms)
        self.assertNotIn("stored/", text)
        self.assertNotIn("Decision lines", text)  # nothing go/three was replayed

    def test_prompts_dropped_by_three_sample_are_not_counted_as_stored_or_unpaired(self):
        dirs, _ = replay_set.split_manifest(self.rows, 1, 3)
        kept = {m["label"] for m in dirs["need-sonnet"] if m["group"] == "three" and m["model"] == "SONNET_55"}
        sonnet = [(m["label"], "SONNET", [4]) for m in dirs["need-sonnet"]]
        s55 = [(m["label"], "SONNET_55", [4]) for m in dirs["need-sonnet55"]]
        arms = replay_set.load_runs([self.runs("n.csv", sonnet) + ",need-sonnet",
                                     self.runs("n55.csv", s55) + ",need-sonnet55"])
        stored = replay_set.with_stored_arms(self.manifest, arms)["stored/SONNET_55 [production]"]
        self.assertEqual({l for l in stored if l.startswith("t")}, kept)  # not the whole manifest

    def test_unreadable_arms_json_is_exit_two(self):
        self.assertEqual(replay_set.main(["compare", "--manifest", str(self.root / "set" / "manifest.csv"),
                                          "--arms-json", str(self.root / "nope.json"), "--runs",
                                          self.runs("a.csv", [("t1", "SONNET", [4])]) + ",a",
                                          "--out", str(self.root / "o.md")]), 2)


if __name__ == "__main__":
    unittest.main()


class HardStopTest(unittest.TestCase):
    def test_every_attempt_must_stay_at_four(self):
        self.assertIn("FAILS", replay_set._hard_stop("low-hauxley-1004", [4, 4, 3]))
        self.assertTrue(replay_set._hard_stop("Low Hauxley", [4, 5, 4]).startswith("ok"))
        self.assertIn("FAILS", replay_set._hard_stop("low-hauxley-1004", []))
        self.assertEqual(replay_set._hard_stop("Bravo", [1]), "")
