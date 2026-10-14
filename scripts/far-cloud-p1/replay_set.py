"""Part D of docs/engineering/far-cloud-p1-plan.md: build the replay prompt set, the R1 prompt, and read the results.

Subcommands:
  select    p1_prompts.csv + p1_forecasts.csv -> <evaluation_id>.<inland|coastal>.txt files + manifest.csv
  build-r1  production system prompt text + patch markdown -> override system prompt file
  compare   manifest.csv + runs.csv per arm (+ optional premise.py slots.csv) -> out/part_d.md

Standard library only. Never touches the network.
"""
import argparse
import csv
import random
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

csv.field_size_limit(sys.maxsize)

# Training cases (plan section 4): reported individually, never pooled with the set.
# (location name, ISO date, target type)
TRAINING_CASES = [
    ("Angel of the North", "2026-10-13", "SUNSET"),
    ("Low Hauxley", "2026-10-04", "SUNRISE"),
    ("Copt Hill", "2026-03-05", "SUNSET"),
    ("Copt Hill", "2026-03-11", "SUNSET"),
]
# Evaluations the plan names explicitly: the Angel's 14:22 UTC intraday run that rated 4, and the
# stored Low Hauxley production prompt. Used when present in the export; otherwise the slot's latest run.
PINNED_TRAINING_IDS = {
    ("Angel of the North", "2026-10-13", "SUNSET"): "101110",
    ("Low Hauxley", "2026-10-04", "SUNRISE"): "86677",
}

# Approximate USD per million tokens, from backend/src/main/resources/application-example.yml
# (cost.sonnet55-*, cost.sonnet-*, cost.haiku-*). Standard (not batch) prices, no cache discount.
PRICES = {
    "SONNET_55": (2.00, 10.00),
    "SONNET": (3.00, 15.00),
    "HAIKU": (1.00, 5.00),
}
# ASSUMPTION, not measured: tokens per replay call (user message ~ plus uncached share of system).
DEFAULT_INPUT_TOKENS = 3500
DEFAULT_OUTPUT_TOKENS = 400
# The estimate is printed as a range: the assumption x1 up to x1.5 (thinking tokens bill as output and
# are not in the assumption; the system prompt is cached per request, which the estimate ignores).
COST_HIGH_FACTOR = 1.5
DEFAULT_ARMS = ["SONNET_55", "SONNET", "HAIKU", "SONNET_55"]  # arm 4 is SONNET_55 + R1 prompt
OK_STATES = ("", None, "SCORED")


def read_csv(path):
    with open(path, newline="", encoding="utf-8") as f:
        return list(csv.DictReader(f))


def to_int(value):
    try:
        return int(float(value))
    except (TypeError, ValueError):
        return None


def blank(value):
    return value is None or str(value).strip() == ""


def is_coastal_prompt(forecast_row, user_message):
    """Which system prompt production used for this row.

    Production keys the builder on DATA: BatchRequestFactory.selectBuilder returns the coastal builder
    when AtmosphericData.tide() != null, else the inland one. The closest stored proxy is the
    location's tide types (loc_tide_types, string_agg of location_tide_type.tide_type): non-empty means
    coastal. Residual: a coastal location with no stored tide extremes has tide() == null in
    production (inland prompt) and is misclassified coastal here; the stored prompt cannot tell (the
    tide line was removed in v2.13.2). A STORM SURGE block in the message proves coastal. Use
    --coastal-ids to override per evaluation id.
    """
    if "STORM SURGE" in user_message.upper():
        return True
    if forecast_row is None:
        return False
    return not blank(forecast_row.get("loc_tide_types"))


def training_key(name, date, target):
    for t in TRAINING_CASES:
        if (name, date, target) == t:
            return t
    return None


def basis_of(row):
    """(basis name, rating): the replay returns Claude's sky rating, so compare on sky_rating when
    stored; the combined rating averages tide in for coastal rows."""
    sky = to_int(row.get("sky_rating"))
    if sky is not None:
        return "sky_rating", sky
    return "rating", to_int(row.get("rating"))


def latest_run_per_slot(forecasts):
    """The latest run strictly before the solar event per (location, date, type): the same rule as
    size.py and premise.py (batch_state NULL or SCORED, across the whole run history). Returns
    {slot key: row}. Whether that row is a go is the caller's question: a triaged or unrated latest
    run means the slot is not a go."""
    best = {}
    for r in forecasts:
        if r.get("batch_state") not in OK_STATES:
            continue
        ev, run = r.get("solar_event_time") or "", r.get("forecast_run_at") or ""
        if not ev or not run or not run < ev:
            continue
        key = (r["location_id"], r["target_date"], r["target_type"])
        cur = best.get(key)
        if cur is None or (run, int(r["evaluation_id"])) > (cur["forecast_run_at"], int(cur["evaluation_id"])):
            best[key] = r
    return best


def select(prompts, forecasts, seed):
    """Returns (go_rows, sample_rows, training_rows, counts). Each row is a dict of the forecast row
    plus prompt fields (user_message), `basis` and `basis_rating`."""
    prompt_by_id = {r["evaluation_id"]: r for r in prompts}
    forecast_by_id = {r["evaluation_id"]: r for r in forecasts}
    latest = latest_run_per_slot(forecasts)
    counts = {"slots": len(latest), "latest_unrated_or_triaged": 0, "latest_without_prompt": 0}
    gos, threes, training = [], [], []
    training_slots = set()

    def joined(frow):
        prow = prompt_by_id.get(frow["evaluation_id"])
        if prow is None:
            return None
        basis, rating = basis_of(frow)
        if rating is None:
            return None
        return dict(frow, user_message=prow["user_message"], basis=basis, basis_rating=rating)

    for key, frow in latest.items():
        tk = training_key(frow["loc_name"], frow["target_date"], frow["target_type"])
        if tk:
            training_slots.add(key)
            pinned = forecast_by_id.get(PINNED_TRAINING_IDS.get(tk, ""))
            use = pinned if pinned is not None and pinned["evaluation_id"] in prompt_by_id else frow
            j = joined(use)
            if j is not None:
                training.append(dict(j, training_case=" ".join(tk)))
            continue
        if not blank(frow.get("triage_reason")) or to_int(frow.get("rating")) is None:
            counts["latest_unrated_or_triaged"] += 1
            continue
        j = joined(frow)
        if j is None:
            counts["latest_without_prompt"] += 1
            continue
        if j["basis_rating"] >= 4:
            gos.append(j)
        elif j["basis_rating"] == 3:
            threes.append(j)
    # A pinned training run (e.g. 86677) may be a slot's older row; add it if its slot had no latest.
    for tk, eid in PINNED_TRAINING_IDS.items():
        frow = forecast_by_id.get(eid)
        if frow is None or eid not in prompt_by_id or any(t["evaluation_id"] == eid for t in training):
            continue
        key = (frow["location_id"], frow["target_date"], frow["target_type"])
        if key in training_slots:
            continue
        j = joined(frow)
        if j is not None:
            training.append(dict(j, training_case=" ".join(tk)))
    byid = lambda r: int(r["evaluation_id"])  # noqa: E731
    gos.sort(key=byid)
    threes.sort(key=byid)
    rng = random.Random(seed)
    sample = rng.sample(threes, min(len(gos), len(threes)))
    sample.sort(key=byid)
    training.sort(key=byid)
    counts["three_star_candidates"] = len(threes)
    return gos, sample, training, counts


MANIFEST_FIELDS = ["evaluation_id", "label", "location", "location_id", "date", "event", "model",
                   "stored_rating", "stored_sky_rating", "basis", "basis_rating", "coastal", "training",
                   "group", "file"]


def write_prompt(directory, row, coastal_ids, group, training_flag, root):
    eid = row["evaluation_id"]
    coastal = eid in coastal_ids or is_coastal_prompt(row, row["user_message"])
    kind = "coastal" if coastal else "inland"
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / f"{eid}.{kind}.txt"
    path.write_text(row["user_message"], encoding="utf-8")
    return {"evaluation_id": eid, "label": eid, "location": row["loc_name"],
            "location_id": row["location_id"], "date": row["target_date"], "event": row["target_type"],
            "model": row.get("evaluation_model", ""), "stored_rating": row.get("rating", ""),
            "stored_sky_rating": row.get("sky_rating", ""), "basis": row["basis"],
            "basis_rating": row["basis_rating"], "coastal": int(coastal), "training": int(training_flag),
            "group": group, "file": str(path.relative_to(root))}


def estimate_cost(n_prompts, runs, arms, in_tok, out_tok):
    per_arm = []
    total = 0.0
    for model in arms:
        p_in, p_out = PRICES[model]
        cost = n_prompts * runs * (in_tok * p_in + out_tok * p_out) / 1_000_000
        per_arm.append((model, cost))
        total += cost
    return total, per_arm


def cmd_select(args):
    prompts = read_csv(args.prompts)
    forecasts = read_csv(args.forecasts)
    if forecasts and "loc_tide_types" not in forecasts[0]:
        print("ERROR: --forecasts has no loc_tide_types column; re-run export_forecasts.sql", file=sys.stderr)
        return 2
    gos, sample, training, counts = select(prompts, forecasts, args.seed)
    coastal_ids = set()
    if args.coastal_ids:
        coastal_ids = {x.strip() for x in Path(args.coastal_ids).read_text().split() if x.strip()}
    out = Path(args.out)
    kinds = {"inland": 0, "coastal": 0}
    manifest = []
    for group, rows in (("go", gos), ("three", sample)):
        for r in rows:
            m = write_prompt(out, r, coastal_ids, group, False, out)
            kinds["coastal" if m["coastal"] else "inland"] += 1
            manifest.append(m)
    for r in training:
        m = write_prompt(out / "training", r, coastal_ids, "training:" + r["training_case"], True, out)
        manifest.append(m)
    out.mkdir(parents=True, exist_ok=True)
    with open(out / "manifest.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=MANIFEST_FIELDS)
        w.writeheader()
        w.writerows(manifest)
    n = len(gos) + len(sample)
    arms = args.arms.split(",") if args.arms else DEFAULT_ARMS
    total, per_arm = estimate_cost(n, args.runs, arms, args.input_tokens, args.output_tokens)
    print(f"slots with a run before the event: {counts['slots']}")
    print(f"  latest run triaged or unrated (not a go, not a candidate): {counts['latest_unrated_or_triaged']}")
    print(f"  latest run has no stored prompt (pruned or synchronous): {counts['latest_without_prompt']}")
    print(f"gos (basis rating >= 4, latest run per slot, sky_rating where stored): {len(gos)}")
    print(f"3-star sample (seed {args.seed}, from {counts['three_star_candidates']} candidates): {len(sample)}")
    print(f"written to {out}: {n} prompts ({kinds['inland']} inland, {kinds['coastal']} coastal)")
    print(f"training cases (in {out / 'training'}, not in the set): {len(training)}")
    print(f"manifest: {out / 'manifest.csv'}")
    print(f"COST ESTIMATE: {n} prompts x N={args.runs} x {len(arms)} arms "
          f"= {n * args.runs * len(arms)} calls")
    print(f"  assumed {args.input_tokens} input + {args.output_tokens} output tokens per call (ASSUMED, not measured),")
    print("  standard (not batch) prices. Thinking tokens bill as output and are NOT in the assumption;")
    print(f"  prompt caching is ignored. Range shown is the assumption x1 to x{COST_HIGH_FACTOR}.")
    print("  USD per MTok in/out from application-example.yml: "
          + ", ".join(f"{m} {PRICES[m][0]}/{PRICES[m][1]}" for m in PRICES))
    for model, cost in per_arm:
        print(f"  {model}: ${cost:,.2f} to ${cost * COST_HIGH_FACTOR:,.2f}")
    print(f"  total: ${total:,.2f} to ${total * COST_HIGH_FACTOR:,.2f}")
    return 0


def parse_patch(text):
    olds = {m.group(1): m.group(2) for m in re.finditer(r"```old (\w+)\n(.*?)\n```", text, re.S)}
    news = {m.group(1): m.group(2) for m in re.finditer(r"```new (\w+)\n(.*?)\n```", text, re.S)}
    if not olds or set(olds) != set(news):
        raise ValueError(f"patch must have matching old/new blocks (old={sorted(olds)}, "
                         f"new={sorted(news)})")
    return [(k, olds[k], news[k]) for k in sorted(olds)]


def apply_patch(system_prompt, replacements):
    """Exact-substring replace; raises ValueError if any old text is not found exactly once."""
    result = system_prompt
    for key, old, new in replacements:
        count = result.count(old)
        if count != 1:
            raise ValueError(f"replacement {key}: old text found {count} times, expected exactly 1")
        result = result.replace(old, new)
    return result


def cmd_build_r1(args):
    system_prompt = Path(args.system_prompt).read_text(encoding="utf-8")
    replacements = parse_patch(Path(args.patch).read_text(encoding="utf-8"))
    try:
        patched = apply_patch(system_prompt, replacements)
    except ValueError as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1
    Path(args.out).write_text(patched, encoding="utf-8")
    print(f"wrote {args.out}: {len(replacements)} replacements, "
          f"{len(system_prompt)} -> {len(patched)} characters")
    return 0


def parse_run_arg(arg):
    """'path' or 'path,label' -> (path, label)."""
    if "," in arg:
        path, label = arg.rsplit(",", 1)
        return path, label
    p = Path(arg)
    return arg, (p.parent.name or p.stem)


def load_runs(specs):
    """{arm: {label: [ratings]}} where arm = '<run label>/<model>[ <system_prompt>]'. A failed call has an
    empty rating and is not counted."""
    arms = defaultdict(lambda: defaultdict(list))
    for spec in specs:
        path, run_label = parse_run_arg(spec)
        for r in read_csv(path):
            sp = r.get("system_prompt") or "production"
            arm = f"{run_label}/{r['model']} [{sp}]"
            v = to_int(r.get("rating"))
            arms[arm][r["label"]]  # touch so a label with only failures still appears
            if v is not None:
                arms[arm][r["label"]].append(v)
    return arms


def load_premises(path):
    """Index premise.py's slots.csv. Header read defensively: match on evaluation_id if the file has
    it, else on (location_id or loc_name, target_date or date, event or target_type)."""
    rows = read_csv(path)
    if not rows:
        return {}, {}
    cols = rows[0].keys()
    pcols = [c for c in ("p_low", "p_far", "p_canvas") if c in cols]
    by_id, by_slot = {}, {}
    for r in rows:
        info = {c: r[c] for c in pcols}
        if "evaluation_id" in cols and r.get("evaluation_id"):
            by_id[r["evaluation_id"]] = info
        loc = r.get("location_id") or r.get("loc_name")
        date = r.get("target_date") or r.get("date")
        event = r.get("event") or r.get("target_type")
        if loc and date and event:
            by_slot[(loc, date, event)] = info
    return by_id, by_slot


def premise_of(m, by_id, by_slot):
    if m["evaluation_id"] in by_id:
        return by_id[m["evaluation_id"]]
    for loc in (m.get("location_id"), m.get("location")):
        if loc and (loc, m["date"], m["event"]) in by_slot:
            return by_slot[(loc, m["date"], m["event"])]
    return None


def premise_class(info):
    if not info:
        return "no premise row"
    return ", ".join(f"{c[2:]}={'?' if blank(v) else v}" for c, v in sorted(info.items())) or "no premise columns"


def md_table(header, rows):
    out = ["| " + " | ".join(header) + " |", "|" + "---|" * len(header)]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return out


def compare(manifest, arms, premises=None):
    """Markdown report text. `premises` is (by_id, by_slot) or None."""
    by_label = {m["label"]: m for m in manifest}
    L = ["# Part D replay: gos that fall, 3-stars that rise", "",
         "Stored basis: sky_rating where stored, else rating (the replay returns Claude's sky rating). "
         "Replay value per prompt = median of the successful attempts. A go is basis >= 4; a "
         "'fall' is a replay median below 4; a 'rise' is a 3-star whose median reaches 4. Training "
         "rows are never pooled.", ""]
    for arm in sorted(arms):
        got = arms[arm]
        med = {lab: (_num(statistics.median(v)) if v else None) for lab, v in got.items()}
        L += [f"## Arm {arm}", ""]
        gos = [m for m in manifest if m["group"] == "go" and m["label"] in got]
        threes = [m for m in manifest if m["group"] == "three" and m["label"] in got]
        fell = [m for m in gos if med[m["label"]] is not None and med[m["label"]] < 4]
        rose = [m for m in threes if med[m["label"]] is not None and med[m["label"]] >= 4]
        no_gos = [m for m in gos if med[m["label"]] is None]
        no_threes = [m for m in threes if med[m["label"]] is None]
        missing = [m["label"] for m in manifest if m["group"] in ("go", "three") and m["label"] not in got]
        L += [f"- gos replayed: {len(gos)}; median below 4: **{len(fell)}**"
              + (f" ({100 * len(fell) / len(gos):.1f}%)" if gos else ""),
              f"- 3-star rows replayed: {len(threes)}; median at 4 or more: **{len(rose)}**"
              + (f" ({100 * len(rose) / len(threes):.1f}%)" if threes else ""),
              f"- rows with no successful attempt: {len(no_gos)} go, {len(no_threes)} 3-star; "
              f"manifest rows not in this runs file: {len(missing)}", ""]
        locs = defaultdict(lambda: [0, 0, 0, 0])  # gos, fell, threes, rose
        for m in gos:
            locs[m["location"]][0] += 1
        for m in fell:
            locs[m["location"]][1] += 1
        for m in threes:
            locs[m["location"]][2] += 1
        for m in rose:
            locs[m["location"]][3] += 1
        rows = [(k, v[1], v[0], v[3], v[2]) for k, v in sorted(locs.items()) if v[1] or v[3]]
        L.append("Per location (only locations with a change):")
        L.append("")
        L += md_table(["location", "gos fell", "of gos", "3-stars rose", "of 3-stars"], rows) if rows \
            else ["(none)"]
        L.append("")
        if fell or rose:
            L += md_table(["evaluation", "location", "date", "event", "stored", "median", "attempts"],
                          [(m["label"], m["location"], m["date"], m["event"], m["basis_rating"],
                            med[m["label"]], got[m["label"]]) for m in fell + rose])
            L.append("")
        if premises is not None:
            classes = defaultdict(lambda: [0, 0])
            for m in gos:
                c = premise_class(premise_of(m, *premises))
                classes[c][0] += 1
                classes[c][1] += 1 if m in fell else 0
            L += ["Premise class (Part B slots.csv) of the gos:", ""]
            L += md_table(["premises", "gos", "fell"], [(k, v[0], v[1]) for k, v in sorted(classes.items())])
            L.append("")
        tr = [m for m in manifest if m["training"] == "1" and m["label"] in got]
        extra = sorted(l for l in got if l not in by_label)
        if tr or extra:
            L += ["Training and pinned cases (individually):", ""]
            rows = []
            for m in tr:
                rows.append((m["label"], f"{m['location']} {m['date']} {m['event']}", m["basis_rating"],
                             med[m["label"]], got[m["label"]], _hard_stop(m["location"], med[m["label"]])))
            for l in extra:
                rows.append((l, "(not in manifest)", "", med[l], got[l], _hard_stop(l, med[l])))
            L += md_table(["label", "case", "stored", "median", "attempts", "Low Hauxley hard stop"], rows)
            L.append("")
    return "\n".join(L) + "\n"


def _num(x):
    return int(x) if x == int(x) else x


def _hard_stop(name, median):
    if "hauxley" not in name.lower():
        return ""
    return "ok (median >= 4)" if median is not None and median >= 4 else "FAILS (must stay 4)"


def cmd_compare(args):
    manifest = read_csv(args.manifest)
    arms = load_runs(args.runs)
    premises = load_premises(args.slots) if args.slots else None
    text = compare(manifest, arms, premises)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(text, encoding="utf-8")
    print(text)
    print(f"wrote {out}")
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("select")
    s.add_argument("--prompts", required=True, help="p1_prompts.csv")
    s.add_argument("--forecasts", required=True,
                   help="p1_forecasts.csv (run history for the latest-run rule, loc_tide_types for coastal)")
    s.add_argument("--out", required=True, help="output directory (REPLAY_PROMPTS_DIR)")
    s.add_argument("--seed", type=int, default=20261014)
    s.add_argument("--runs", type=int, default=3, help="N per prompt and arm")
    s.add_argument("--arms", help="comma-separated models, one per arm (default: 4 arms)")
    s.add_argument("--input-tokens", type=int, default=DEFAULT_INPUT_TOKENS)
    s.add_argument("--output-tokens", type=int, default=DEFAULT_OUTPUT_TOKENS)
    s.add_argument("--coastal-ids", help="file of evaluation ids to force to the coastal prompt")
    s.set_defaults(func=cmd_select)
    b = sub.add_parser("build-r1")
    b.add_argument("--system-prompt", required=True)
    b.add_argument("--patch", required=True)
    b.add_argument("--out", required=True)
    b.set_defaults(func=cmd_build_r1)
    c = sub.add_parser("compare")
    c.add_argument("--manifest", required=True, help="manifest.csv written by select")
    c.add_argument("--runs", nargs="+", required=True, metavar="runs.csv[,label]",
                   help="one harness runs.csv per arm run; optional ',label' names it (default: its folder)")
    c.add_argument("--slots", help="premise.py's out/slots.csv, to show premise classes")
    c.add_argument("--out", default="out/part_d.md")
    c.set_defaults(func=cmd_compare)
    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
