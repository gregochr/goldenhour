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


class CrosstabTest(unittest.TestCase):
    def rows(self):
        mk = lambda group, basis, combined: {"group": group, "basis": basis, "stored_rating": combined,  # noqa: E731
                                              "rating": combined}
        return [mk("go", "sky_rating", "4"), mk("go", "sky_rating", "5"), mk("go", "sky_rating", "3"),
                mk("go", "rating", "4"), mk("three", "sky_rating", "4"), mk("three", "sky_rating", "3"),
                mk("three", "sky_rating", "2"), mk("three", "sky_rating", ""), {"group": "training:x", "basis": "rating"}]

    def test_bands_and_totals(self):
        table = replay_set.basis_crosstab(self.rows(), "stored_rating")
        self.assertEqual(table["go"]["sky_rating"], {"combined >= 4": 2, "combined 3": 1})
        self.assertEqual(table["go"]["rating"], {"combined >= 4": 1})
        self.assertEqual(table["three"]["sky_rating"],
                         {"combined >= 4": 1, "combined 3": 1, "combined < 3": 1, "combined missing": 1})
        self.assertNotIn("training:x", table)
        flat = {(r[0], r[1]): r for r in replay_set.crosstab_rows(table)}
        self.assertEqual(flat[("go", "all")][2:], [4, 3, 1, 0, 0])
        self.assertEqual(flat[("three", "all")][2:], [4, 1, 1, 1, 1])

    def test_note_counts_reader_visible_gos(self):
        note = replay_set.crosstab_note(replay_set.basis_crosstab(self.rows(), "stored_rating"))
        self.assertIn("3 of 4", note)
        self.assertIn("1 are gos on the basis star only", note)
        self.assertIn("Mirror: 1 of 4 basis-3 rows are a 4 or more to a reader", note)

    def test_select_prints_the_crosstab(self):
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            run_select()
        text = buf.getvalue()
        self.assertIn("basis x combined rating", text)
        self.assertIn("go | all | 1 | 1 | 0 | 0 | 0", text)
        self.assertIn("reader-visible gos", text)

    def test_compare_header_repeats_it_and_excludes_training(self):
        manifest = make_set(Path(tempfile.mkdtemp()))
        text = replay_set.compare(manifest, {})
        self.assertIn("| group | basis | n | combined >= 4 | combined 3 | combined < 3 | combined missing |", text)
        self.assertIn("| go | all | 3 | 3 | 0 | 0 | 0 |", text)
        self.assertIn("| three | all | 7 | 0 | 7 | 0 | 0 |", text)   # the training row (training=1) is not counted
        self.assertIn("reader-visible gos (basis >= 4 and combined rating also >= 4): 3 of 3", text)


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


class ControlSplitTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.set = self.root / "set"
        self.set.mkdir()
        self.rows = make_set(self.set)
        self.out = self.root / "arms"

    def split(self, *extra):
        return replay_set.main(["split", "--set", str(self.set), "--out", str(self.out), *extra])

    @staticmethod
    def big_manifest():
        """30 go + 70 three rated by SONNET_55, 10 go + 10 three by HAIKU, one training row."""
        rows, i = [], 0
        for model, group, n in (("SONNET_55", "go", 30), ("SONNET_55", "three", 70),
                                ("HAIKU", "go", 10), ("HAIKU", "three", 10)):
            for _ in range(n):
                i += 1
                rows.append({"evaluation_id": str(i), "label": str(i), "model": model, "group": group,
                             "training": "0", "basis_rating": "4" if group == "go" else "3"})
        rows.append({"evaluation_id": "9999", "label": "tr", "model": "SONNET_55", "group": "go",
                     "training": "1", "basis_rating": "4"})
        return rows

    def test_off_by_default_and_changes_nothing_else(self):
        self.assertEqual(self.split(), 0)
        self.assertFalse((self.out / replay_set.CONTROL_RUN).exists())
        import json
        meta = json.loads((self.out / "arms.json").read_text())
        self.assertNotIn(replay_set.CONTROL_RUN, meta["dirs"])
        self.assertEqual(meta["control_sample"], 0)
        dirs, info = replay_set.split_manifest(self.rows, None, 1)
        self.assertNotIn(replay_set.CONTROL_RUN, dirs)
        self.assertEqual(info["control_kept"], 0)

    def test_draws_only_sonnet55_stored_prompts_proportionally_and_never_training(self):
        rows = self.big_manifest()
        drawn, pool = replay_set.draw_control(rows, 20, 7)
        self.assertEqual(pool, {"go": 30, "three": 70})  # HAIKU's and the training row are not in the pool
        self.assertEqual(len(drawn), 20)
        self.assertEqual({m["model"] for m in drawn}, {"SONNET_55"})
        self.assertNotIn("tr", {m["label"] for m in drawn})
        self.assertEqual(sum(1 for m in drawn if m["group"] == "go"), 6)  # 30 of 100 -> 6 of 20
        self.assertEqual(sum(1 for m in drawn if m["group"] == "three"), 14)
        self.assertEqual(len({m["label"] for m in drawn}), 20)  # no prompt twice
        again, _ = replay_set.draw_control(rows, 20, 7)
        self.assertEqual([m["label"] for m in again], [m["label"] for m in drawn])  # seeded
        other, _ = replay_set.draw_control(rows, 20, 8)
        self.assertNotEqual({m["label"] for m in other}, {m["label"] for m in drawn})
        every, _ = replay_set.draw_control(rows, 500, 7)  # larger than the pool keeps it all
        self.assertEqual(len(every), 100)
        self.assertEqual(replay_set.draw_control(rows, 0, 7)[0], [])

    def test_odd_shares_round_to_nearest_and_the_draw_has_its_own_random_stream(self):
        pool = [{"evaluation_id": str(i), "label": str(i), "model": "SONNET_55", "training": "0",
                 "group": "go" if i <= 3 else "three", "basis_rating": "4"} for i in range(1, 8)]
        drawn, _ = replay_set.draw_control(pool, 4, 1)  # 4 * 3/7 = 1.71 go: nearest is 2, truncating gives 1
        self.assertEqual((sum(1 for m in drawn if m["group"] == "go"),
                          sum(1 for m in drawn if m["group"] == "three")), (2, 2))
        # all-three pool, so the three-sample and the control both sample the same list with the same seed:
        # a shared stream would make the control IS the three-sample's kept prompts
        threes = [{"evaluation_id": str(i), "label": str(i), "model": "SONNET_55", "training": "0",
                   "group": "three", "basis_rating": "3", "file": f"{i}.txt"} for i in range(1, 21)]
        dirs, _ = replay_set.split_manifest(threes, 5, 3, 5)
        kept = {m["label"] for m in dirs["need-sonnet"]}
        control = {m["label"] for m in dirs[replay_set.CONTROL_RUN]}
        self.assertEqual((len(kept), len(control)), (5, 5))
        self.assertNotEqual(kept, control)

    def test_a_group_that_cannot_fill_its_share_is_topped_up_from_the_other(self):
        rows = [r for r in self.big_manifest() if not (r["group"] == "three" and r["model"] == "SONNET_55"
                                                       and int(r["evaluation_id"]) > 33)]
        drawn, pool = replay_set.draw_control(rows, 30, 1)  # 30 go, 3 three
        self.assertEqual(pool, {"go": 30, "three": 3})
        self.assertEqual(len(drawn), 30)
        self.assertEqual(sum(1 for m in drawn if m["group"] == "three"), 3)

    def test_control_does_not_move_with_the_three_sample(self):
        a, _ = replay_set.split_manifest(self.rows, 1, 5, 3)
        b, _ = replay_set.split_manifest(self.rows, 6, 5, 3)
        self.assertEqual([m["label"] for m in a[replay_set.CONTROL_RUN]],
                         [m["label"] for m in b[replay_set.CONTROL_RUN]])

    def test_split_writes_the_directory_and_records_it_in_arms_json(self):
        import json
        self.assertEqual(self.split("--control-sample", "3", "--seed", "7"), 0)
        got = read(self.out / replay_set.CONTROL_RUN / "manifest.csv")
        self.assertEqual(len(got), 3)
        # fixture pool: g1, g3 (go) and t1..t4 (three) are SONNET_55-stored; 3 -> 1 go + 2 three
        self.assertEqual(sorted(m["group"] for m in got), ["go", "three", "three"])
        self.assertEqual({m["model"] for m in got}, {"SONNET_55"})
        for m in got:
            self.assertTrue((self.out / replay_set.CONTROL_RUN / m["file"]).is_file())
            self.assertTrue((self.set / f"{m['label']}.inland.txt").is_file())  # copied, the set is whole
        spec = json.loads((self.out / "arms.json").read_text())["dirs"][replay_set.CONTROL_RUN]
        self.assertEqual((spec["model"], spec["system_prompt"], spec["role"]),
                         ("SONNET_55", "production", "control"))
        self.assertEqual(sorted(spec["labels"]), sorted(m["label"] for m in got))
        # the control prompts are also in the arms' own directories: that is by design, not a clash
        other = {m["label"] for n in ("need-sonnet", "r1-gos")
                 for m in read(self.out / n / "manifest.csv")}
        self.assertLessEqual({m["label"] for m in got} & {"g1", "g3", "t1", "t2", "t3", "t4"}, other)

    def test_self_replay_guard_exempts_only_the_control(self):
        dirs, _ = replay_set.split_manifest(self.rows, None, 1, 4)
        self.assertEqual(replay_set.self_replays(dirs), [])
        bad = dict(dirs, **{"need-sonnet55": [dict(self.rows[0], model="SONNET_55")]})
        self.assertEqual(replay_set.self_replays(bad), [("need-sonnet55", "1")])

    def test_negative_control_sample_is_refused(self):
        self.assertEqual(self.split("--control-sample", "-1"), 2)
        self.assertFalse(self.out.exists())

    def test_plan_prints_the_control_as_a_batch_cost_line_inside_the_total(self):
        import contextlib
        import io
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf):
            self.split("--control-sample", "4")
        text = buf.getvalue()
        want = 0.5 * 4 * (3500 * 2.0 + 400 * 10.0) / 1e6
        line = next(l for l in text.splitlines() if l.startswith(f"  {replay_set.CONTROL_RUN}:"))
        self.assertIn("4 prompts", line)
        self.assertIn("SONNET_55", line)
        self.assertIn("CONTROL", line)
        self.assertIn(f"${want:,.2f} to ${want * 1.5:,.2f}", line)
        total = next(l for l in text.splitlines() if l.startswith("  total:"))
        self.assertIn("control included", total)
        self.assertIn(f"{7 + 4 + 3 + 4} calls", total)  # need-sonnet 7, need-sonnet55 4, r1-gos 3, control 4


class ControlCompareTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        (self.root / "set").mkdir()
        make_set(self.root / "set")
        self.manifest = read(self.root / "set" / "manifest.csv")
        self.arms_dir = self.root / "arms"
        replay_set.main(["split", "--set", str(self.root / "set"), "--out", str(self.arms_dir),
                         "--control-sample", "6"])  # the whole SONNET_55 pool of the fixture
        import json
        self.meta = json.loads((self.arms_dir / "arms.json").read_text())

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

    def control_file(self):
        # stored: g1 4, g3 4, t1..t4 3.  g1 falls, g3 holds; t1, t2, t3 rise, t4 holds.
        return self.runs("control.csv", [("g1", "SONNET_55", [3]), ("g3", "SONNET_55", [4]),
                                         ("t1", "SONNET_55", [4]), ("t2", "SONNET_55", [4]),
                                         ("t3", "SONNET_55", [4]), ("t4", "SONNET_55", [3])])

    def load(self, with_control=True):
        specs = [f"{f},{l}" for f, l in zip(self.trimmed(), ["need-sonnet", "need-sonnet55", "r1-gos"])]
        if with_control:
            specs.append(f"{self.control_file()},{replay_set.CONTROL_RUN}")
        return replay_set.load_runs(specs)

    def test_noise_floor_numerators_and_denominators(self):
        st = replay_set.control_stats(self.manifest, self.load())
        self.assertEqual((st["rated"], st["moved"], st["up"], st["down"]), (6, 4, 3, 1))
        self.assertEqual((st["go_below"], st["go_rated"]), (1, 2))
        self.assertEqual((st["three_rose"], st["three_rated"]), (3, 4))
        text = replay_set.compare(self.manifest, self.load())
        self.assertIn("replay moved off the stored rating: **4/6 (66.7%)** (3 up, 1 down)", text)
        self.assertIn("control gos that fell below 4 stars: **1/2 (50.0%)**", text)
        self.assertIn("control 3-stars that rose to 4 or more: **3/4 (75.0%)**", text)
        self.assertIn("CONTROL, the noise floor", text)

    def test_unrated_control_prompts_are_not_in_the_denominators(self):
        arms = replay_set.load_runs([self.runs("c.csv", [("g1", "SONNET_55", [""]), ("t1", "SONNET_55", [4]),
                                                          ("t2", "SONNET_55", [3])]) + ",control-sonnet55"])
        st = replay_set.control_stats(self.manifest, arms)
        self.assertEqual((st["prompts"], st["rated"], st["unrated"]), (3, 2, 1))
        self.assertEqual((st["go_rated"], st["three_rated"], st["moved"]), (0, 2, 1))
        text = replay_set.compare(self.manifest, arms)
        self.assertIn("**0/0**", text)  # a zero denominator prints, it never divides
        self.assertNotIn("Decision lines", text)  # the control alone is not an arm of any decision

    def test_net_lines_print_the_raw_figure_and_the_floor(self):
        text = replay_set.compare(self.manifest, self.load())
        lines = text.splitlines()
        d1 = next(i for i, l in enumerate(lines) if l.startswith("- **D-1 part 2"))
        self.assertIn("1/3 go prompts taken below 4 stars (33.3%; cap 15%: over)", lines[d1])  # raw, unchanged
        self.assertIn("took 1/2 (50.0%) below 4 with no R1", lines[d1 + 1])
        self.assertIn("**-16.7 pp**", lines[d1 + 1])
        self.assertNotIn("within", lines[d1 + 1])  # the 15% cap stays on the raw share; the net is descriptive
        self.assertNotIn("over", lines[d1 + 1])
        self.assertIn("the 15% cap applies to the raw share above", lines[d1 + 1])
        d2 = next(i for i, l in enumerate(lines) if "ONE-SIDED" in l)
        self.assertIn("SONNET 2/4 (50.0%) [4 replayed]", lines[d2])  # raw, unchanged
        self.assertIn("3/4 (75.0%) rose to 4 or more with no model change", lines[d2 + 1])
        self.assertIn("-25.0 pp against that floor", lines[d2 + 1])

    def test_control_is_never_double_counted_with_the_decision_lines(self):
        with_c, without = self.load(), self.load(with_control=False)
        self.assertEqual(replay_set.with_stored_arms(self.manifest, with_c).keys() - {
            f"{replay_set.CONTROL_RUN}/SONNET_55 [production]"}, replay_set.with_stored_arms(
            self.manifest, without).keys())
        full_c = replay_set.with_stored_arms(self.manifest, with_c)
        full = replay_set.with_stored_arms(self.manifest, without)
        for model in ("SONNET_55", "SONNET"):  # the control's SONNET_55 replay of t1 must not make it "replayed"
            self.assertEqual(replay_set.model_view(full_c, model), replay_set.model_view(full, model))
        self.assertEqual(replay_set.model_view(full_c, "SONNET_55")["t1"][1], "stored")
        strip = lambda ls: [l for l in ls if not l.startswith("  - net of") and not l.startswith("  - floor")]  # noqa
        self.assertEqual(strip(replay_set.decision_lines(self.manifest, with_c, replay_set.control_stats(
            self.manifest, with_c))), replay_set.decision_lines(self.manifest, without))
        text = replay_set.compare(self.manifest, with_c)
        self.assertEqual(text.count("## Arm control-sonnet55/SONNET_55 [production]"), 1)
        self.assertNotIn("paired on", text)  # no both-replayed D-2 pair appears from the control
        # a report without the control has no net or floor line at all
        self.assertNotIn("net of the control", replay_set.compare(self.manifest, without))
        self.assertNotIn("floor", replay_set.compare(self.manifest, without))

    def test_check_arms_accepts_the_control_and_refuses_the_wrong_model_or_prompt(self):
        ok = self.load()
        self.assertEqual(replay_set.check_arms(self.meta, self.manifest, ok), [])  # no self-replay false alarm
        wrong_model = replay_set.load_runs([self.runs("w.csv", [("t1", "SONNET", [3])]) + ",control-sonnet55"])
        problems = replay_set.check_arms(self.meta, self.manifest, wrong_model)
        self.assertTrue(any("is for SONNET_55" in p for p in problems), problems)
        r1 = replay_set.load_runs([self.runs("r.csv", [("t1", "SONNET_55", [3])], system_prompt="r1.txt@sha256:a")
                                   + ",control-sonnet55"])
        self.assertTrue(any("expects the production system prompt" in p for p in
                            replay_set.check_arms(self.meta, self.manifest, r1)))
        haiku_rated = replay_set.load_runs([self.runs("h.csv", [("g2", "SONNET_55", [4])]) + ",control-sonnet55"])
        self.assertTrue(any("not stored by SONNET_55" in p or "not in directory" in p for p in
                            replay_set.check_arms(self.meta, self.manifest, haiku_rated)))
        training = replay_set.load_runs([self.runs("t.csv", [("tr1", "SONNET_55", [4])]) + ",control-sonnet55"])
        self.assertTrue(replay_set.check_arms(self.meta, self.manifest, training))

    def test_without_arms_json_a_control_of_prompts_it_may_not_hold_is_refused(self):
        for label in ("g2", "tr1"):  # HAIKU-stored go; a training row stored by SONNET_55
            arms = replay_set.load_runs([self.runs("a.csv", [(label, "SONNET_55", [4])]) + ",control-sonnet55"])
            problems = replay_set.check_arms({}, self.manifest, arms)
            self.assertTrue(any("not stored by SONNET_55" in p for p in problems), (label, problems))
        self.assertEqual(replay_set.check_arms({}, self.manifest, replay_set.load_runs(
            [self.runs("b.csv", [("g1", "SONNET_55", [4])]) + ",control-sonnet55"])), [])

    def test_floor_is_read_against_sonnet_replays_of_stored_threes_only(self):
        # t5/t6 are HAIKU-stored; give them a SONNET-STORED twin so one-sided pairs mix stored sides
        rows = [dict(m) for m in self.manifest]
        rows.append(dict(rows[3], evaluation_id="50", label="s1", model="SONNET"))  # SONNET-stored 3-star
        arms = replay_set.load_runs([self.runs("n.csv", [("t1", "SONNET", [4]), ("t2", "SONNET", [3]),
                                                         ("t3", "SONNET", [3]), ("t4", "SONNET", [3])]) + ",need-sonnet",
                                     self.runs("c.csv", [("s1", "SONNET_55", [4])]) + ",need-sonnet55",
                                     self.control_file() + ",control-sonnet55"])
        text = replay_set.compare(rows, arms)
        floor = next(l for l in text.splitlines() if l.startswith("  - floor"))
        self.assertIn("SONNET replayed on SONNET_55's stored 3-stars: 1/4 (25.0%)", floor)  # s1 is not in it
        self.assertIn("-50.0 pp against that floor", floor)  # 25.0 - 75.0
        self.assertIn("THIN: only 4 rated control prompt(s)", floor)

    def test_a_control_run_on_another_prompt_is_not_a_floor(self):
        r1 = replay_set.load_runs([self.runs("r.csv", [("t1", "SONNET_55", [4])], system_prompt="r1.txt@sha256:a")
                                   + ",control-sonnet55"])
        self.assertIsNone(replay_set.control_stats(self.manifest, r1))

    def test_cli_exit_codes(self):
        base = ["compare", "--manifest", str(self.root / "set" / "manifest.csv"), "--out",
                str(self.root / "r.md")]
        files = self.trimmed()
        good = [f"{files[0]},need-sonnet", f"{files[1]},need-sonnet55", f"{files[2]},r1-gos",
                f"{self.control_file()},control-sonnet55"]
        with_json = base + ["--arms-json", str(self.arms_dir / "arms.json")]
        self.assertEqual(replay_set.main(with_json + ["--runs", *good]), 0)
        self.assertIn("CONTROL, the noise floor", (self.root / "r.md").read_text())
        bad = self.runs("bad.csv", [("t1", "SONNET", [3])])
        for args in (with_json, base):  # wrong model: refused with and without arms.json
            if (self.root / "r.md").exists():
                (self.root / "r.md").unlink()
            self.assertEqual(replay_set.main(args + ["--runs", f"{bad},control-sonnet55"]), 2)
            self.assertFalse((self.root / "r.md").exists())
        bad_prompt = self.runs("bp.csv", [("t1", "SONNET_55", [3])], system_prompt="r1.txt@sha256:a")
        self.assertEqual(replay_set.main(base + ["--runs", f"{bad_prompt},control-sonnet55"]), 2)
        # and a control run of the right model and prompt is fine without arms.json
        self.assertEqual(replay_set.main(base + ["--runs", f"{self.control_file()},control-sonnet55"]), 0)


if __name__ == "__main__":
    unittest.main()


class HardStopTest(unittest.TestCase):
    def test_every_attempt_must_stay_at_four(self):
        self.assertIn("FAILS", replay_set._hard_stop("low-hauxley-1004", [4, 4, 3]))
        self.assertTrue(replay_set._hard_stop("Low Hauxley", [4, 5, 4]).startswith("ok"))
        self.assertIn("FAILS", replay_set._hard_stop("low-hauxley-1004", []))
        self.assertEqual(replay_set._hard_stop("Bravo", [1]), "")
