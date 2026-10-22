"""Part D of docs/engineering/far-cloud-p1-plan.md: build the replay prompt set, the R1 prompt, and read the results.

Subcommands:
  select    p1_prompts.csv + p1_forecasts.csv -> <evaluation_id>.<inland|coastal>.txt files + manifest.csv
  build-r1  production system prompt text + patch markdown -> override system prompt file
  split     select's set -> one directory per replay arm, skipping what a stored rating already answers
            (--control-sample N adds a SONNET_55 production-prompt replay of N SONNET_55-stored prompts:
            the noise floor a stored rating's selection bias puts under every comparison with it)
  compare   manifest.csv + runs.csv per arm (+ optional premise.py slots.csv) -> out/part_d.md

Standard library only. Never touches the network.
"""
import argparse
import csv
import json
import random
import re
import shutil
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


BANDS = ("combined >= 4", "combined 3", "combined < 3", "combined missing")


def band_of(combined):
    """Band of the combined (reader-visible) star: the star the app shows, tide averaged in for coastal rows."""
    c = to_int(combined)
    if c is None:
        return BANDS[3]
    return BANDS[0] if c >= 4 else (BANDS[1] if c == 3 else BANDS[2])


def basis_crosstab(rows, combined_key):
    """{group: {basis: {band: count}}} for the go and three groups (every row passed: in `compare` that is the whole
    manifest, not only the prompts with results). `basis` is what selected the prompt (the
    sky-only star where stored, else the combined one); `combined_key` names the combined rating in each row
    ('rating' in a forecast row, 'stored_rating' in a manifest row). A 'go' whose basis is the sky star can
    be a 3 or less to a reader, because the combined star averages the tide score in for a coastal row."""
    out = {}
    for r in rows:
        group = r.get("group")
        if group not in ("go", "three"):
            continue
        cell = out.setdefault(group, {}).setdefault(r.get("basis") or "unknown", {})
        band = band_of(r.get(combined_key))
        cell[band] = cell.get(band, 0) + 1
    return out


def crosstab_rows(table):
    """Flat rows [group, basis, n, *band counts] in a fixed order, plus a per-group total row."""
    rows = []
    for group in ("go", "three"):
        bases = table.get(group, {})
        for basis in sorted(bases):
            cell = bases[basis]
            rows.append([group, basis, sum(cell.values())] + [cell.get(b, 0) for b in BANDS])
        if bases:
            tot = [sum(c.get(b, 0) for c in bases.values()) for b in BANDS]
            rows.append([group, "all", sum(tot)] + tot)
    return rows


def crosstab_note(table):
    """One sentence on how many gos a reader would also see as a go."""
    go = table.get("go", {})
    n = sum(sum(c.values()) for c in go.values())
    visible = sum(c.get(BANDS[0], 0) for c in go.values())
    below = n - visible
    three = table.get("three", {})
    n3 = sum(sum(c.values()) for c in three.values())
    up = sum(c.get(BANDS[0], 0) for c in three.values())
    return (f"reader-visible gos (basis >= 4 and the stored combined rating also >= 4; the combined star is "
            f"forecast_evaluation.rating, which can differ from the cached star the app serves): {visible} of {n}; "
            f"{below} are gos on the basis star only (combined below 4 or missing). "
            f"Mirror: {up} of {n3} basis-3 rows are a 4 or more to a reader")


def crosstab_text(rows, combined_key):
    """Plain-text lines for `select`."""
    table = basis_crosstab(rows, combined_key)
    lines = ["basis x combined rating (the combined star is what a reader sees; the basis is what selected the prompt):",
             "  " + " | ".join(["group", "basis", "n"] + list(BANDS))]
    lines += ["  " + " | ".join(str(c) for c in r) for r in crosstab_rows(table)]
    lines.append("  " + crosstab_note(table))
    return lines


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
    labelled = ([dict(r, group="go") for r in gos] + [dict(r, group="three") for r in sample])
    for line in crosstab_text(labelled, "rating"):
        print(line)
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


# ---- split: the trimmed Part D ("Option A, trimmed") ------------------------------------------------
# A stored production rating is one sample from the model that produced it, for that exact prompt. So a
# prompt never needs a replay on the model that already rated it, only on the other one.
#   need-sonnet    prompts a SONNET_55 rated    -> replay on SONNET (4.6), production prompt
#   need-sonnet55  prompts HAIKU (or SONNET) rated -> replay on SONNET_55, production prompt
#   r1-gos         the go group only             -> replay on SONNET_55 with the R1 system prompt
#   training       copied as is                  -> replayed on all models, README step c
#   control-sonnet55  (opt-in, --control-sample N)  a seeded random N of the SONNET_55-stored prompts, go and
#                  three drawn proportionally -> replayed on SONNET_55 with the PRODUCTION prompt. That is the one
#                  deliberate self-replay: a stored rating is chosen on its value (the `three` group is 3 by
#                  construction, the `go` group >= 4), so ANY replay compared with it regresses toward the mean
#                  even with no real difference; replaying the same model on the same prompt measures how far.
# HAIKU is not an arm of the D-2 comparison (SONNET_55 vs SONNET), so a HAIKU-stored prompt gets no SONNET
# replay; a stored model nobody recognises gets both replays, because neither stored arm is usable.
STORED_RUN = "stored"  # the run label of an arm that is a stored rating, never a replay
CONTROL_RUN = "control-sonnet55"  # the run label (= directory name) of the control replay, never an arm
CONTROL_MODEL = "SONNET_55"  # the control is drawn from this model's stored ratings and replayed on it
SPLIT_DIRS = {
    "need-sonnet": {"model": "SONNET", "system_prompt": "production", "role": "arm"},
    "need-sonnet55": {"model": "SONNET_55", "system_prompt": "production", "role": "arm"},
    "r1-gos": {"model": "SONNET_55", "system_prompt": "override", "role": "arm"},
    "training": {"model": None, "system_prompt": "production", "role": "training"},
    CONTROL_RUN: {"model": CONTROL_MODEL, "system_prompt": "production", "role": "control"},
}
SPLIT_REQUIRED = ("evaluation_id", "label", "model", "group", "training", "file")
BATCH_DISCOUNT = 0.5  # Message Batches bill at half the standard per-token price
CONTROL_THIN = 30  # a floor over fewer rated control prompts than this is printed with a warning


def models_needing_replay(stored_model):
    """Which production-prompt replays a prompt still needs, given the model that rated it."""
    if stored_model == "SONNET_55":
        return ["SONNET"]
    if stored_model in ("SONNET", "HAIKU"):
        return ["SONNET_55"]
    return ["SONNET", "SONNET_55"]


def draw_control(manifest, n, seed):
    """Pure. A seeded random N of the CONTROL_MODEL-stored go/three prompts (training rows never), the two
    groups drawn proportionally to their size in that pool. Drawn from the whole pool, never from what
    --three-sample kept, so the control does not move when the three-sample does. Returns
    (rows, {"go": pool size, "three": pool size})."""
    pool = {g: sorted((m for m in manifest if m["group"] == g and m["training"] != "1"
                       and m["model"] == CONTROL_MODEL), key=lambda m: int(m["evaluation_id"]))
            for g in ("go", "three")}
    sizes = {g: len(v) for g, v in pool.items()}
    total = sizes["go"] + sizes["three"]
    if n <= 0 or total == 0:
        return [], sizes
    if n >= total:
        return pool["go"] + pool["three"], sizes
    n_go = round(n * sizes["go"] / total)
    n_three = min(n - n_go, sizes["three"])
    n_go = min(n - n_three, sizes["go"])
    rng = random.Random(f"{CONTROL_RUN}:{seed}")  # its own stream: independent of the three-sample's
    rows = rng.sample(pool["go"], n_go) + rng.sample(pool["three"], n_three)
    return sorted(rows, key=lambda m: int(m["evaluation_id"])), sizes


def split_manifest(manifest, three_sample=None, seed=0, control_sample=0):
    """Pure. {directory: [manifest rows]} and an info dict. `three_sample` keeps a seeded random N of the
    `three` group (drawn once from the whole group, so both production-prompt directories see the same
    prompts); it never touches the go group or the training rows. `control_sample` > 0 adds the
    control directory (draw_control); the control prompts are ALSO in the other directories, by design."""
    threes = sorted((m for m in manifest if m["group"] == "three" and m["training"] != "1"),
                    key=lambda m: int(m["evaluation_id"]))
    kept = {m["evaluation_id"] for m in threes}
    if three_sample is not None and three_sample < len(threes):
        kept = {m["evaluation_id"] for m in random.Random(seed).sample(threes, three_sample)}
    dirs = {name: [] for name in SPLIT_DIRS if name != CONTROL_RUN or control_sample > 0}
    control, control_pool = ([], {"go": 0, "three": 0})
    if control_sample > 0:
        control, control_pool = draw_control(manifest, control_sample, seed)
        dirs[CONTROL_RUN] = control
    for m in manifest:
        if m["training"] == "1":
            dirs["training"].append(m)
            continue
        if m["group"] not in ("go", "three") or (m["group"] == "three" and m["evaluation_id"] not in kept):
            continue
        needed = models_needing_replay(m["model"])
        if "SONNET" in needed:
            dirs["need-sonnet"].append(m)
        if "SONNET_55" in needed:
            dirs["need-sonnet55"].append(m)
        if m["group"] == "go":
            dirs["r1-gos"].append(m)
    return dirs, {"three_candidates": len(threes), "three_kept": len(kept & {t["evaluation_id"] for t in threes}),
                  "control_pool": control_pool, "control_kept": len(control),
                  "control_go": sum(1 for m in control if m["group"] == "go"),
                  "control_three": sum(1 for m in control if m["group"] == "three")}


def self_replays(dirs):
    """Prompts that would be replayed, with the production prompt, on the model that already rated them.
    Always empty from split_manifest; checked anyway because the cost of being wrong is real money."""
    bad = []
    for name, rows in dirs.items():
        spec = SPLIT_DIRS[name]
        if spec["model"] is None or spec["system_prompt"] != "production" or spec["role"] == "control":
            continue  # the control replays a model on its own stored prompts on purpose
        bad += [(name, m["evaluation_id"]) for m in rows if m["model"] == spec["model"]]
    return bad


def batch_cost(n_calls, model, in_tok, out_tok):
    p_in, p_out = PRICES[model]
    return BATCH_DISCOUNT * n_calls * (in_tok * p_in + out_tok * p_out) / 1_000_000


def cmd_split(args):
    set_dir, out = Path(args.set), Path(args.out)
    try:
        manifest = read_csv(set_dir / "manifest.csv")
    except OSError as e:
        print(f"ERROR: cannot read the set's manifest: {e}", file=sys.stderr)
        return 2
    if not manifest or any(c not in manifest[0] for c in SPLIT_REQUIRED):
        print(f"ERROR: {set_dir / 'manifest.csv'} is empty or lacks one of {SPLIT_REQUIRED}", file=sys.stderr)
        return 2
    if args.three_sample is not None and args.three_sample < 0:
        print("ERROR: --three-sample must be >= 0", file=sys.stderr)
        return 2
    if args.control_sample < 0:
        print("ERROR: --control-sample must be >= 0", file=sys.stderr)
        return 2
    if out.resolve() == set_dir.resolve():
        print("ERROR: --out must not be the set directory", file=sys.stderr)
        return 2
    dirs, info = split_manifest(manifest, args.three_sample, args.seed, args.control_sample)
    clash = self_replays(dirs)
    if clash:
        print(f"ERROR: would replay a prompt on the model that rated it: {clash[:5]}", file=sys.stderr)
        return 2
    missing = [m["file"] for rows in dirs.values() for m in rows if not (set_dir / m["file"]).is_file()]
    if missing:
        print(f"ERROR: {len(missing)} prompt file(s) named by the manifest are missing from {set_dir}, "
              f"e.g. {missing[0]}", file=sys.stderr)
        return 2
    dirty = [n for n in dirs if (out / n).exists() and any((out / n).iterdir())]
    if dirty:  # leftovers would be replayed, and billed, with the new set
        print(f"ERROR: {', '.join(str(out / n) for n in dirty)} already holds files; delete it and re-run "
              "(nothing is overwritten or removed here)", file=sys.stderr)
        return 2
    meta = {"set": str(set_dir), "seed": args.seed, "three_sample": args.three_sample,
            "three_candidates": info["three_candidates"], "three_kept": info["three_kept"],
            "control_sample": args.control_sample, "control_pool": info["control_pool"],
            "control_kept": info["control_kept"], "input_tokens": args.input_tokens, "output_tokens": args.output_tokens, "dirs": {}}
    for name, rows in dirs.items():
        target = out / name
        target.mkdir(parents=True, exist_ok=True)
        local = []
        for m in rows:
            base = Path(m["file"]).name
            shutil.copy2(set_dir / m["file"], target / base)  # copy, never move: the set stays whole
            local.append(dict(m, file=base))
        with open(target / "manifest.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=MANIFEST_FIELDS, extrasaction="ignore")
            w.writeheader()
            w.writerows(local)
        spec = SPLIT_DIRS[name]
        meta["dirs"][name] = {"model": spec["model"], "system_prompt": spec["system_prompt"],
                              "role": spec["role"], "prompts": len(rows), "labels": [m["label"] for m in rows]}
    out.mkdir(parents=True, exist_ok=True)
    (out / "arms.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
    print(f"split of {set_dir} into {out} (prompt files copied, the set is untouched)")
    print(f"3-star group: {info['three_candidates']} prompts"
          + (f"; --three-sample {args.three_sample} (seed {args.seed}) keeps {info['three_kept']}"
             if args.three_sample is not None else "; all kept"))
    print(f"COST at N=1, one call per prompt, BATCH prices (half of the standard cost.* prices in "
          f"application-example.yml); same assumption as select: {args.input_tokens} input + "
          f"{args.output_tokens} output tokens per call, ASSUMED, thinking tokens and caching not included; "
          f"range x1 to x{COST_HIGH_FACTOR}")
    if args.control_sample > 0:
        pool = info["control_pool"]
        print(f"control: --control-sample {args.control_sample} (seed {args.seed}) draws {info['control_go']} go "
              f"+ {info['control_three']} three from the {pool['go']} go and {pool['three']} three prompts "
              f"{CONTROL_MODEL} rated" + ("; N exceeds the pool, all kept"
                                          if args.control_sample >= pool["go"] + pool["three"] else ""))
    tot_calls, tot_lo = 0, 0.0
    for name in ("need-sonnet", "need-sonnet55", "r1-gos", CONTROL_RUN):
        if name not in dirs:
            continue
        spec, n = SPLIT_DIRS[name], len(dirs[name])
        lo = batch_cost(n, spec["model"], args.input_tokens, args.output_tokens)
        tot_calls += n
        tot_lo += lo
        kind = ("production prompt, CONTROL (same model, same prompt: the noise floor)"
                if spec["role"] == "control" else
                "production prompt" if spec["system_prompt"] == "production" else "R1 system prompt")
        print(f"  {name}: {n} prompts ({sum(1 for m in dirs[name] if m['group'] == 'go')} go, "
              f"{sum(1 for m in dirs[name] if m['group'] == 'three')} three) -> {n} calls on "
              f"{spec['model']}, {kind}: ${lo:,.2f} to ${lo * COST_HIGH_FACTOR:,.2f}")
    print(f"  total: {tot_calls} calls: ${tot_lo:,.2f} to ${tot_lo * COST_HIGH_FACTOR:,.2f}"
          + (" (control included)" if CONTROL_RUN in dirs else "")
          + " (primer batches add 1 request per system prefix per batch)")
    print(f"  training: {len(dirs['training'])} prompts, replayed separately on all models (README step c), "
          "not in the total")
    print("  a stored rating is ONE production sample; a replay is not (P0: 20 of 20 identical on the Angel)")
    print(f"arms: {out / 'arms.json'} (pass --arms-json to compare, with each runs.csv labelled by its "
          "directory name)")
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


ARM_RE = re.compile(r"^(?P<run>.*)/(?P<model>[A-Z0-9_]+) \[(?P<sp>.*)\]$")


def parse_arm(arm):
    """'<run label>/<model> [<system prompt>]' -> (run label, model, system prompt)."""
    m = ARM_RE.match(arm)
    return (m.group("run"), m.group("model"), m.group("sp")) if m else (arm, "", "")


def is_stored_arm(arm):
    return parse_arm(arm)[0] == STORED_RUN


def is_control_arm(arm):
    """The control replay: a model replayed on its own stored prompts. It measures drift and is never pooled
    with, paired against or counted as an arm of any decision line."""
    return parse_arm(arm)[0] == CONTROL_RUN


def with_stored_arms(manifest, arms):
    """arms plus, per model that has at least one production-prompt replay of a go/three prompt loaded, a
    `stored/<model> [production]` arm holding the stored basis rating of every go/three prompt that model
    rated in production, that some loaded replay touched, and that no loaded production replay of that model
    covers. One sample, never a replay. A prompt no replay touched (dropped by --three-sample) and a training
    row get none; a run that replayed everything gets no stored arm, so its report is unchanged."""
    scored = {m["label"] for m in manifest if m["group"] in ("go", "three") and m["training"] != "1"}
    touched = set()
    covered = defaultdict(set)  # model -> go/three labels present in a production replay arm
    for arm, got in arms.items():
        run, model, sp = parse_arm(arm)
        if run in (STORED_RUN, CONTROL_RUN):
            continue
        touched |= set(got) & scored
        if sp == "production":
            covered[model] |= set(got) & scored
    out = dict(arms)
    for model, labels in covered.items():
        if not labels:
            continue
        stored = {m["label"]: [to_int(m["basis_rating"])] for m in manifest
                  if m["model"] == model and m["label"] in touched and m["label"] not in labels
                  and m["label"] in scored and to_int(m["basis_rating"]) is not None}
        if stored:
            out[f"{STORED_RUN}/{model} [production]"] = stored
    return out


def model_view(arms, model):
    """{label: (attempts, 'replayed'|'stored')} over the production-prompt arms of `model`: replays
    pooled across run labels, a stored rating only where no replay of that prompt exists."""
    view = {}
    for arm, got in arms.items():
        run, m, sp = parse_arm(arm)
        if m != model or sp != "production" or run == CONTROL_RUN:
            continue
        for label, vals in got.items():
            if run == STORED_RUN:
                view.setdefault(label, (list(vals), "stored"))
            else:
                prev = view.get(label)
                view[label] = ((prev[0] if prev and prev[1] == "replayed" else []) + list(vals), "replayed")
    return view


def _median(vals):
    return statistics.median(vals) if vals else None


def control_stats(manifest, arms):
    """The noise floor, or None when no control run is loaded. Over the control's go/three prompts (never a
    training row): `rated` have a successful attempt (the denominators), `moved` is the median differing
    from the stored basis rating, `go_below` the control gos whose median is below 4, `three_rose` the
    control threes whose median reaches 4. Several loaded control runs (a resume) pool their attempts."""
    # only the control's own model and prompt are a noise floor; check_arms refuses anything else
    runs = [a for a in arms if is_control_arm(a) and parse_arm(a)[1:] == (CONTROL_MODEL, "production")]
    if not runs:
        return None
    by_label = {m["label"]: m for m in manifest if m["group"] in ("go", "three") and m["training"] != "1"}
    attempts = defaultdict(list)
    for arm in runs:
        for lab, vals in arms[arm].items():
            if lab in by_label:
                attempts[lab] += vals
    st = {"prompts": len(attempts), "rated": 0, "unrated": 0, "moved": 0, "up": 0, "down": 0, "go_rated": 0,
          "go_below": 0, "three_rated": 0, "three_rose": 0, "moved_rows": []}
    for lab, vals in sorted(attempts.items()):
        m, med = by_label[lab], _median(vals)
        if med is None:
            st["unrated"] += 1
            continue
        stored = to_int(m["basis_rating"])
        st["rated"] += 1
        if m["group"] == "go":
            st["go_rated"] += 1
            st["go_below"] += 1 if med < 4 else 0
        else:
            st["three_rated"] += 1
            st["three_rose"] += 1 if med >= 4 else 0
        if med != stored:
            st["moved"] += 1
            st["up" if med > stored else "down"] += 1
            st["moved_rows"].append((lab, m["location"], m["date"], m["event"], stored, _num(med), vals))
    return st


def _pct(k, n):
    return f"{k}/{n} ({100 * k / n:.1f}%)" if n else f"{k}/{n}"


def _thin(n):
    """A warning clause for a floor measured on few prompts: one prompt is 100/n percentage points."""
    if n >= CONTROL_THIN:
        return ""
    return (f" THIN: only {n} rated control prompt(s), so one prompt moves it {100 / n:.1f} pp "
            f"(under {CONTROL_THIN}: read it as a rough size, not a figure)." if n else "")


def control_section(arm, st):
    L = [f"## Arm {arm}: CONTROL, the noise floor (not an arm of any decision)", "",
         "- SONNET_55 replayed on prompts SONNET_55 itself rated in production, with the production prompt. "
         "Nothing about the model or the prompt differs from the stored rating, so every move below is the "
         "stored rating's selection (a go is >= 4 and a 3-star is 3 by selection) plus sampling noise: the "
         "drift any replay compared against a stored rating shows with no real difference. The decision "
         "lines below set it beside their raw figures.",
         f"- {st['prompts']} control prompts; {st['rated']} with a successful attempt, "
         f"{st['unrated']} without (not in any figure here)",
         f"- replay moved off the stored rating: **{_pct(st['moved'], st['rated'])}** "
         f"({st['up']} up, {st['down']} down)",
         f"- control gos that fell below 4 stars: **{_pct(st['go_below'], st['go_rated'])}**"
         + _thin(st["go_rated"]),
         f"- control 3-stars that rose to 4 or more: **{_pct(st['three_rose'], st['three_rated'])}**"
         + _thin(st["three_rated"]), ""]
    if st["moved_rows"]:
        L += md_table(["evaluation", "location", "date", "event", "stored", "median", "attempts"],
                      st["moved_rows"])
        L.append("")
    return L


def decision_lines(manifest, arms, control=None):
    """The two numbers the plan's thresholds read, one line each, with numerator, denominator and which
    side is a stored rating and which a replay. `control` (control_stats) adds the noise floor beside
    each: the one-sided D-2 line and the D-1 part 2 line print their raw figure and the floor."""
    lines = []
    threes = [m["label"] for m in manifest if m["group"] == "three" and m["training"] != "1"]
    v55, vs = model_view(arms, "SONNET_55"), model_view(arms, "SONNET")
    pairs = []
    for lab in threes:
        a, b = v55.get(lab), vs.get(lab)
        if a and b and _median(a[0]) is not None and _median(b[0]) is not None:
            pairs.append((lab, a[1], _median(a[0]), b[1], _median(b[0])))
    both = [p for p in pairs if p[1] == "replayed" and p[3] == "replayed"]
    one_side = [p for p in pairs if p not in both]
    share = lambda ps, i: sum(1 for p in ps if p[i] >= 4)  # noqa: E731
    if both:
        n, k55, ks = len(both), share(both, 2), share(both, 4)
        lines.append(
            f"- **D-2 (4-star share of the 3-star sample, both sides replayed, paired on {n} prompts):** "
            f"SONNET_55 {k55}/{n} ({100 * k55 / n:.1f}%) [{n} replayed] vs SONNET {ks}/{n} "
            f"({100 * ks / n:.1f}%) [{n} replayed]; SONNET_55 minus SONNET {100 * (k55 - ks) / n:+.1f} pp "
            "(D-2.1: Batch Near-Term goes back to Sonnet 4.6 at +8 pp or more).")
    if one_side:
        n, k55, ks = len(one_side), share(one_side, 2), share(one_side, 4)
        tag = lambda i: ", ".join(f"{sum(1 for p in one_side if p[i] == k)} {k}"  # noqa: E731
                                  for k in ("replayed", "stored") if any(p[i] == k for p in one_side))
        lines.append(
            f"- **D-2, ONE-SIDED (not a test of D-2.1), {n} prompts where one side is a stored rating:** "
            f"SONNET_55 {k55}/{n} ({100 * k55 / n:.1f}%) [{tag(1)}] vs SONNET {ks}/{n} "
            f"({100 * ks / n:.1f}%) [{tag(3)}]. A stored 3-star is 3 by selection, so the stored side is "
            "0% 4-star by construction: no gap in the direction D-2.1 needs (+8 pp for SONNET_55) can "
            "show from a stored SONNET_55 side, and none is computed. It shows only how often the "
            "replayed model rates the other model's 3-stars 4 or higher.")
        # the floor is a share of REPLAYED SONNET_55 ratings over prompts SONNET_55 stored as 3, so it is read
        # against SONNET's replays of exactly those: a SONNET-stored pair has no replay and is not in `ks`
        like = [p for p in one_side if p[1] == "stored" and p[3] == "replayed"]
        if control and control["three_rated"] and like:
            kc, nc = control["three_rose"], control["three_rated"]
            kl = sum(1 for p in like if p[4] >= 4)
            lines.append(
                f"  - floor (control, SONNET_55 replayed on its own stored 3-stars, production prompt): "
                f"{_pct(kc, nc)} rose to 4 or more with no model change; SONNET replayed on SONNET_55's "
                f"stored 3-stars: {_pct(kl, len(like))}, which is {100 * kl / len(like) - 100 * kc / nc:+.1f} pp "
                f"against that floor.{_thin(nc)}")
        elif control and control["three_rated"]:
            lines.append("  - floor: no SONNET replay of a SONNET_55-stored 3-star is loaded to set beside "
                         "the control.")
        elif control:
            lines.append("  - floor: the control holds no rated 3-star, so no rise-to-4 floor exists.")
    if both or one_side:
        unpaired = sum(1 for lab in threes if lab in v55 and lab not in vs)
        if unpaired:
            lines.append(f"  - {unpaired} 3-star prompts have a SONNET_55 rating and no SONNET one "
                         "(not a pair, not counted above).")
    else:
        lines.append("- **D-2:** not computable here: no 3-star prompt has both a SONNET_55 and a SONNET rating.")
    gos = [m["label"] for m in manifest if m["group"] == "go" and m["training"] != "1"]
    r1 = sorted(a for a in arms if not is_stored_arm(a) and parse_arm(a)[1] == "SONNET_55"
                and parse_arm(a)[2] != "production")
    for arm in r1:
        got = arms[arm]
        vals = {lab: _median(got[lab]) for lab in gos if lab in got and _median(got[lab]) is not None}
        n, k = len(vals), sum(1 for v in vals.values() if v < 4)
        if n:
            lines.append(
                f"- **D-1 part 2 (arm {arm}):** {k}/{n} go prompts taken below 4 stars ({100 * k / n:.1f}%; "
                f"cap 15%: {'over' if 100 * k > 15 * n else 'within'}) [R1 side: replayed, median of attempts; "
                f"go side: stored sky rating >= 4 by selection]; {len(gos) - n} of {len(gos)} manifest gos "
                "have no rated attempt on this arm.")
            if control and control["go_rated"]:
                kc, nc = control["go_below"], control["go_rated"]
                net = 100 * k / n - 100 * kc / nc
                lines.append(
                    f"  - net of the control: SONNET_55 on its own stored gos, production prompt, took {_pct(kc, nc)} "
                    f"below 4 with no R1, so R1's share net of that floor is **{net:+.1f} pp** (raw "
                    f"{100 * k / n:.1f}% minus {100 * kc / nc:.1f}%). Descriptive: the 15% cap applies to the "
                    f"raw share above, not to this figure. The control holds SONNET_55-stored gos "
                    f"only.{_thin(nc)}")
            elif control:
                lines.append("  - net of the control: the control holds no rated go, so no floor exists.")
        else:
            lines.append(f"- **D-1 part 2 (arm {arm}):** not computable: no go prompt has a rated attempt.")
    return lines


def check_arms(arms_meta, manifest, arms):
    """Problems between arms.json (written by split) and the loaded runs. A run is matched by its run label
    being a directory name of the split."""
    problems = []
    by_label = {m["label"]: m for m in manifest}
    dirs = arms_meta.get("dirs", {})
    for arm, got in sorted(arms.items()):
        run, model, sp = parse_arm(arm)
        spec = dirs.get(run)
        if spec is None and run == CONTROL_RUN:  # no arms.json: the control's own rules still apply
            spec = {k: v for k, v in SPLIT_DIRS[CONTROL_RUN].items()}
        if spec is None or run == STORED_RUN:
            continue
        if spec["model"] is None:  # training: replayed on every model on purpose
            continue
        if model != spec["model"]:
            problems.append(f"{arm}: directory {run} is for {spec['model']}, the runs are on {model}")
        if (sp == "production") != (spec["system_prompt"] == "production"):
            problems.append(f"{arm}: directory {run} expects the {spec['system_prompt']} system prompt, "
                            f"the runs carry '{sp}'")
        if "labels" in spec:
            stray = sorted(set(got) - set(spec["labels"]))
            if stray:
                problems.append(f"{arm}: {len(stray)} prompt(s) not in directory {run}, e.g. {stray[0]}")
        if run == CONTROL_RUN:  # drawn only from prompts the control's model rated, never a training row
            alien = sorted(l for l in got if by_label.get(l, {}).get("group") not in ("go", "three")
                           or by_label[l].get("training") == "1" or by_label[l].get("model") != spec["model"])
            if alien:
                problems.append(f"{arm}: {len(alien)} prompt(s) not stored by {spec['model']} as a go or "
                                f"3-star, e.g. {alien[0]}")
        elif sp == "production":
            again = sorted(l for l in got if by_label.get(l, {}).get("model") == model)
            if again:
                problems.append(f"{arm}: {len(again)} prompt(s) replayed on the model that rated them, "
                                f"e.g. {again[0]}")
    return problems


def compare(manifest, arms, premises=None):
    """Markdown report text. `premises` is (by_id, by_slot) or None."""
    by_label = {m["label"]: m for m in manifest}
    L = ["# Part D replay: gos that fall, 3-stars that rise", "",
         "Stored basis: sky_rating where stored, else rating (the replay returns Claude's sky rating). "
         "Replay value per prompt = median of the successful attempts. A go is basis >= 4; a "
         "'fall' is a replay median below 4; a 'rise' is a 3-star whose median reaches 4. Training "
         "rows are never pooled.", ""]
    table = basis_crosstab([m for m in manifest if m.get("training") != "1"], "stored_rating")
    L += ["Basis against the combined rating the reader sees (training rows excluded; a manifest row's "
          "`stored_rating` is the combined star, `basis_rating` the one that selected it):", ""]
    L += md_table(["group", "basis", "n"] + list(BANDS), crosstab_rows(table))
    L += [crosstab_note(table), ""]
    arms = with_stored_arms(manifest, arms)
    control = control_stats(manifest, arms)
    for arm in sorted(arms):
        got = arms[arm]
        if is_control_arm(arm):
            if parse_arm(arm)[1:] == (CONTROL_MODEL, "production"):
                L += control_section(arm, control)
            continue
        if is_stored_arm(arm):
            n_go = sum(1 for m in manifest if m["label"] in got and m["group"] == "go")
            L += [f"## Arm {arm}: STORED RATINGS, NOT A REPLAY", "",
                  f"- {len(got)} prompts ({n_go} go, {len(got) - n_go} 3-star): the production rating that "
                  "selected each prompt, one sample from the model that made it. A go is >= 4 and a 3-star is "
                  "3 by selection, so this arm can show no fall and no rise. It stands in only where that "
                  "model was not replayed, in the decision lines below.", ""]
            continue
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
                             med[m["label"]], got[m["label"]], _hard_stop(m["location"], got[m["label"]])))
            for l in extra:
                rows.append((l, "(not in manifest)", "", med[l], got[l], _hard_stop(l, got[l])))
            L += md_table(["label", "case", "stored", "median", "attempts", "Low Hauxley hard stop"], rows)
            L.append("")
    scored = {m["label"] for m in manifest if m["group"] in ("go", "three") and m["training"] != "1"}
    replayed = any(not is_stored_arm(a) and not is_control_arm(a) and set(got) & scored
                   for a, got in arms.items())
    decisions = decision_lines(manifest, arms, control) if replayed else []
    if decisions:
        L += ["## Decision lines (plan section 6: D-2 and D-1 part 2)", ""] + decisions + [""]
    return "\n".join(L) + "\n"


def _num(x):
    return int(x) if x == int(x) else x


def _hard_stop(name, attempts):
    """Plan section 2 Part D: Low Hauxley must stay 4 in EVERY replay, not on the median."""
    if "hauxley" not in name.lower():
        return ""
    vals = [a for a in (attempts or []) if a is not None]
    if not vals:
        return "FAILS (no rated attempt)"
    return "ok (every attempt >= 4)" if min(vals) >= 4 else "FAILS (an attempt fell below 4; must stay 4)"


def cmd_compare(args):
    manifest = read_csv(args.manifest)
    arms = load_runs(args.runs)
    if any(is_stored_arm(a) for a in arms):
        print(f"ERROR: a run is labelled '{STORED_RUN}', which compare reserves for stored ratings; "
              "label it another way (runs.csv,label)", file=sys.stderr)
        return 2
    if args.arms_json:
        try:
            meta = json.loads(Path(args.arms_json).read_text(encoding="utf-8"))
        except (OSError, ValueError) as e:
            print(f"ERROR: cannot read --arms-json: {e}", file=sys.stderr)
            return 2
        problems = check_arms(meta, manifest, arms)
        if problems:
            print("ERROR: runs do not match arms.json:\n  " + "\n  ".join(problems), file=sys.stderr)
            return 2
    else:
        problems = check_arms({}, manifest, arms)  # only the control's own rules can fire without a split
        if problems:
            print("ERROR: control run is not valid:\n  " + "\n  ".join(problems), file=sys.stderr)
            return 2
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
    sp = sub.add_parser("split", help="split select's set into per-arm directories (trimmed Part D)")
    sp.add_argument("--set", required=True, help="select's output directory, with manifest.csv")
    sp.add_argument("--out", required=True, help="new directory: need-sonnet/, need-sonnet55/, r1-gos/, "
                    "training/, [control-sonnet55/], arms.json")
    sp.add_argument("--three-sample", type=int, default=None, metavar="N",
                    help="keep a seeded random N of the 3-star group (default: all)")
    sp.add_argument("--control-sample", type=int, default=0, metavar="N",
                    help="also write control-sonnet55/: a seeded random N of the SONNET_55-stored prompts "
                    "(go and three proportionally), to replay on SONNET_55 with the production prompt; "
                    "measures the regression to the mean a stored rating's selection puts under every "
                    "comparison with it (default 0 = off; 150 recommended)")
    sp.add_argument("--seed", type=int, default=20261014)
    sp.add_argument("--input-tokens", type=int, default=DEFAULT_INPUT_TOKENS)
    sp.add_argument("--output-tokens", type=int, default=DEFAULT_OUTPUT_TOKENS)
    sp.set_defaults(func=cmd_split)
    b = sub.add_parser("build-r1")
    b.add_argument("--system-prompt", required=True)
    b.add_argument("--patch", required=True)
    b.add_argument("--out", required=True)
    b.set_defaults(func=cmd_build_r1)
    c = sub.add_parser("compare")
    c.add_argument("--manifest", required=True, help="manifest.csv written by select")
    c.add_argument("--runs", nargs="+", required=True, metavar="runs.csv[,label]",
                   help="one harness runs.csv per arm run; optional ',label' names it (default: its folder)")
    c.add_argument("--arms-json", help="arms.json written by split: refuse runs that do not match it "
                   "(label each runs.csv with its directory name)")
    c.add_argument("--slots", help="premise.py's out/slots.csv, to show premise classes")
    c.add_argument("--out", default="out/part_d.md")
    c.set_defaults(func=cmd_compare)
    args = ap.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
